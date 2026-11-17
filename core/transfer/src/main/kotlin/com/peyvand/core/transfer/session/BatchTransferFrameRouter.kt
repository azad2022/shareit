package com.peyvand.core.transfer.session

import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.io.EOFException
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the single inbound reader for a FILE_TRANSFER logical channel and routes
 * complete batch-protocol frames to a bounded per-batch input pipe.
 *
 * Every batch frame starts with its batch UUID. An inbound BATCH_OFFER creates a
 * route and is delivered to [onIncomingOffer]; the handler receives that first
 * frame separately because the batch receiver protocol consumes it out-of-band.
 *
 * The router owns only the logical child channel, never the parent TLS socket.
 */
internal class BatchTransferFrameRouter(
    private val channel: AuthenticatedPeerChannel,
    private val scope: CoroutineScope,
    private val onIncomingOffer: (RoutedBatchChannel, TransferFrame) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {

    private val lock = Any()
    private val routes = LinkedHashMap<UUID, RoutedBatchChannel>()
    private var readerJob: Job? = null
    private var closed = false

    fun start() {
        synchronized(lock) {
            check(!closed) { "The batch frame router is closed" }
            if (readerJob != null) return
            readerJob = scope.launch(Dispatchers.IO) { readAndRouteFrames() }
        }
    }

    /** Registers a locally initiated batch before its BATCH_OFFER is written. */
    fun registerOutgoing(batchId: UUID): RoutedBatchChannel = synchronized(lock) {
        check(!closed) { "The batch frame router is closed" }
        check(!routes.containsKey(batchId)) { "A route already exists for this batch" }
        check(routes.size < MAX_ACTIVE_BATCHES) { "The active batch limit has been reached" }
        createRouteLocked(batchId)
    }

    private suspend fun readAndRouteFrames() {
        try {
            while (true) {
                val frame = TransferFrameCodec.read(channel.inputStream)
                    ?: throw EOFException("Authenticated peer closed the file-transfer channel")
                routeFrame(frame)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            fail(failure)
        }
    }

    private fun routeFrame(frame: TransferFrame) {
        if (frame.sessionId != channel.sessionId) {
            throw TransferProtocolException("Batch frame belongs to a different authenticated session")
        }
        if (frame.protocolVersion != TransferFrameCodec.BATCH_PROTOCOL_VERSION ||
            frame.type.wireCode !in FIRST_BATCH_FRAME_CODE..LAST_BATCH_FRAME_CODE
        ) {
            throw TransferProtocolException("Unsupported frame on the batch exchange channel")
        }

        val batchId = decodeBatchId(frame.payloadBytes())
        if (frame.type == TransferFrameType.BATCH_OFFER) {
            val route = synchronized(lock) {
                if (closed) throw IOException("The batch frame router is closed")
                if (routes.containsKey(batchId)) {
                    throw TransferProtocolException("A batch offer reused an active batch identifier")
                }
                if (routes.size >= MAX_ACTIVE_BATCHES) {
                    throw TransferProtocolException("The peer exceeded the active batch limit")
                }
                createRouteLocked(batchId)
            }
            // The offer itself is passed separately. The receiver protocol must not
            // read it again from the route before expecting the manifest.
            scope.launch(Dispatchers.IO) {
                try {
                    onIncomingOffer(route, frame)
                } catch (failure: Exception) {
                    route.close()
                    fail(failure)
                }
            }
            return
        }

        val route = synchronized(lock) {
            if (closed) throw IOException("The batch frame router is closed")
            routes[batchId]
        } ?: throw TransferProtocolException("Received a frame for an unknown batch identifier")

        route.deliver(frame)
    }

    private fun createRouteLocked(batchId: UUID): RoutedBatchChannel {
        val route = RoutedBatchChannel(batchId, ROUTE_BUFFER_BYTES, ::releaseRoute)
        routes[batchId] = route
        return route
    }

    private fun releaseRoute(route: RoutedBatchChannel) {
        synchronized(lock) {
            if (routes[route.batchId] === route) routes.remove(route.batchId)
        }
        route.closePipes()
    }

    private fun fail(failure: Throwable) {
        val shouldNotify = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldNotify) return
        closeResources()
        runCatching { onFailure(failure) }
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) closeResources()
    }

    private fun closeResources() {
        readerJob?.cancel()
        // Closing the logical channel unblocks a blocking pipe read without
        // closing the parent authenticated TLS session.
        runCatching { channel.close() }
        val current = synchronized(lock) {
            routes.values.toList().also { routes.clear() }
        }
        current.forEach(RoutedBatchChannel::closePipes)
    }

    private fun decodeBatchId(payload: ByteArray): UUID {
        if (payload.size < BATCH_ID_BYTES) {
            throw TransferProtocolException("Batch frame does not contain a complete batch identifier")
        }
        val buffer = ByteBuffer.wrap(payload, 0, BATCH_ID_BYTES).order(ByteOrder.BIG_ENDIAN)
        return UUID(buffer.long, buffer.long)
    }

    private companion object {
        const val FIRST_BATCH_FRAME_CODE = 12
        const val LAST_BATCH_FRAME_CODE = 31
        const val BATCH_ID_BYTES = 16
        const val MAX_ACTIVE_BATCHES = 2
        const val ROUTE_BUFFER_BYTES = 128 * 1024
    }
}

/** Bounded virtual input stream for one batch. */
internal class RoutedBatchChannel(
    val batchId: UUID,
    bufferBytes: Int,
    private val onClose: (RoutedBatchChannel) -> Unit,
) : AutoCloseable {
    private val input = PipedInputStream(bufferBytes)
    private val output = PipedOutputStream(input)
    private val lock = Any()
    private var closed = false
    private var pipesClosed = false

    val inputStream = input

    internal fun deliver(frame: TransferFrame) {
        synchronized(lock) {
            if (closed) throw IOException("The batch route is closed")
        }
        // Do not hold the route lock while a bounded pipe applies backpressure.
        TransferFrameCodec.write(frame, output)
        output.flush()
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) onClose(this)
    }

    internal fun closePipes() {
        val shouldClose = synchronized(lock) {
            if (pipesClosed) false else {
                pipesClosed = true
                closed = true
                true
            }
        }
        if (!shouldClose) return
        // Closing the reader first wakes a writer blocked by a full bounded pipe.
        runCatching { input.close() }
        runCatching { output.close() }
    }
}
