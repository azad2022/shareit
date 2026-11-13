package com.peyvand.core.transfer.session

import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.domain.connection.AuthenticatedPeerChannelPurpose
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A single socket reader routes complete bounded protocol frames to dedicated logical
 * channels. Child channels never read or write the underlying TLS socket directly.
 *
 * File and chat frames are mutually routed, while writes are serialized one complete
 * frame at a time. Per-channel input pipes are bounded and propagate backpressure.
 */
class AuthenticatedPeerChannelMultiplexer(
    private val sessionId: java.util.UUID,
    private val socketInput: InputStream,
    private val socketOutput: OutputStream,
    private val closeUnderlying: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {

    private val lock = Any()
    private val socketWriteLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val leases = EnumMap<AuthenticatedPeerChannelPurpose, ChannelLease>(
        AuthenticatedPeerChannelPurpose::class.java,
    )
    private var readerJob: Job? = null
    private var closed = false

    fun openChannel(purpose: AuthenticatedPeerChannelPurpose): AuthenticatedPeerChannel? =
        synchronized(lock) {
            if (closed || leases.containsKey(purpose)) return@synchronized null
            val lease = try {
                ChannelLease(purpose)
            } catch (_: IOException) {
                return@synchronized null
            }
            leases[purpose] = lease
            if (readerJob == null) {
                readerJob = scope.launch { readAndRouteFrames() }
            }
            lease.asPublicChannel()
        }

    private suspend fun readAndRouteFrames() {
        try {
            while (true) {
                val frame = TransferFrameCodec.read(socketInput)
                    ?: throw EOFException("The authenticated peer closed the multiplexed channel.")
                if (frame.sessionId != sessionId) {
                    throw IOException("An authenticated frame has an unexpected session identifier.")
                }
                val purpose = purposeFor(frame.type)
                val lease = synchronized(lock) {
                    if (closed) return
                    leases[purpose]
                } ?: throw IOException(
                    "The peer sent a ${frame.type.name} frame while its logical channel was not open.",
                )

                try {
                    TransferFrameCodec.write(frame, lease.pipeOutput)
                    lease.pipeOutput.flush()
                } catch (exception: IOException) {
                    if (!lease.isClosed()) throw exception
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            fail(exception)
        }
    }

    private fun sendEncodedFrame(
        purpose: AuthenticatedPeerChannelPurpose,
        encoded: ByteArray,
    ) {
        val frame = TransferFrameCodec.read(ByteArrayInputStream(encoded))
            ?: throw IOException("An empty frame cannot be sent.")
        if (purposeFor(frame.type) != purpose) {
            throw IOException("A logical channel attempted to send a frame owned by another channel.")
        }
        synchronized(socketWriteLock) {
            synchronized(lock) {
                if (closed) throw IOException("The authenticated channel multiplexer is closed.")
            }
            socketOutput.write(encoded)
            socketOutput.flush()
        }
    }

    private fun release(lease: ChannelLease) {
        synchronized(lock) {
            if (leases[lease.purpose] === lease) {
                leases.remove(lease.purpose)
            }
        }
        lease.closePipes()
    }

    private fun fail(exception: Throwable) {
        val shouldNotify = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!shouldNotify) return
        closeResources()
        runCatching { onFailure(exception) }
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
        scope.coroutineContext[Job]?.cancel()
        val current = synchronized(lock) {
            leases.values.toList().also { leases.clear() }
        }
        current.forEach(ChannelLease::closePipes)
        runCatching { closeUnderlying() }
    }

    private inner class ChannelLease(
        val purpose: AuthenticatedPeerChannelPurpose,
    ) {
        private val pipeInput = java.io.PipedInputStream(PIPE_BUFFER_BYTES)
        val pipeOutput = java.io.PipedOutputStream(pipeInput)
        private var released = false
        private val framedOutput = MultiplexedFrameOutputStream(purpose)

        fun isClosed(): Boolean = synchronized(lock) { released || closed }

        fun asPublicChannel(): AuthenticatedPeerChannel = object : AuthenticatedPeerChannel {
            override val sessionId: java.util.UUID = this@AuthenticatedPeerChannelMultiplexer.sessionId
            override val inputStream: InputStream = pipeInput
            override val outputStream: OutputStream = framedOutput

            override fun close() {
                release(this@ChannelLease)
            }
        }

        fun closePipes() {
            synchronized(lock) {
                if (released) return
                released = true
            }
            runCatching { pipeInput.close() }
            runCatching { pipeOutput.close() }
            runCatching { framedOutput.discardPending() }
        }

        private inner class MultiplexedFrameOutputStream(
            private val channelPurpose: AuthenticatedPeerChannelPurpose,
        ) : OutputStream() {
            private val outputLock = Any()
            private val pending = ByteArrayOutputStream()

            override fun write(value: Int) {
                val single = byteArrayOf(value.toByte())
                write(single, 0, single.size)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
                synchronized(outputLock) {
                    checkLeaseOpen()
                    var consumed = 0
                    while (consumed < length) {
                        val count = minOf(OUTPUT_APPEND_CHUNK_BYTES, length - consumed)
                        pending.write(bytes, offset + consumed, count)
                        consumed += count
                        emitCompleteFrames()
                    }
                }
            }

            override fun flush() {
                synchronized(outputLock) {
                    checkLeaseOpen()
                    if (pending.size() != 0) {
                        throw IOException("A logical channel flushed an incomplete protocol frame.")
                    }
                    synchronized(socketWriteLock) { socketOutput.flush() }
                }
            }

            override fun close() {
                release(this@ChannelLease)
            }

            fun discardPending() {
                synchronized(outputLock) { pending.reset() }
            }

            private fun checkLeaseOpen() {
                if (isClosed()) throw IOException("The logical channel is closed.")
            }

            private fun emitCompleteFrames() {
                while (true) {
                    val bytes = pending.toByteArray()
                    if (bytes.size < TransferFrameCodec.HEADER_BYTES) return
                    val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    if (header.int != FRAME_MAGIC) {
                        throw IOException("Invalid frame magic on a logical channel.")
                    }
                    val version = header.get().toInt() and 0xff
                    val typeCode = header.get().toInt() and 0xff
                    header.position(22)
                    val payloadLength = header.int
                    if (payloadLength < 0 || payloadLength > MAX_FRAME_PAYLOAD_BYTES) {
                        throw IOException("Frame payload length exceeds the channel limit.")
                    }
                    val frameLength = TransferFrameCodec.HEADER_BYTES + payloadLength
                    if (bytes.size < frameLength) return

                    val encodedFrame = bytes.copyOfRange(0, frameLength)
                    val decoded = try {
                        TransferFrameCodec.read(ByteArrayInputStream(encodedFrame))
                    } catch (exception: Exception) {
                        throw IOException("Invalid frame produced by a logical channel.", exception)
                    } ?: throw IOException("A logical channel produced an empty frame.")

                    if (decoded.protocolVersion != version || decoded.type.wireCode != typeCode ||
                        purposeFor(decoded.type) != channelPurpose
                    ) {
                        throw IOException("A logical channel attempted an invalid frame route.")
                    }

                    val remaining = bytes.copyOfRange(frameLength, bytes.size)
                    pending.reset()
                    if (remaining.isNotEmpty()) pending.write(remaining)
                    sendEncodedFrame(channelPurpose, encodedFrame)
                }
            }
        }
    }

    private fun purposeFor(type: TransferFrameType): AuthenticatedPeerChannelPurpose =
        when (type) {
            TransferFrameType.CHAT_MESSAGE,
            TransferFrameType.CHAT_ACK,
            -> AuthenticatedPeerChannelPurpose.CHAT
            else -> AuthenticatedPeerChannelPurpose.FILE_TRANSFER
        }

    private companion object {
        const val PIPE_BUFFER_BYTES = 128 * 1024
        const val OUTPUT_APPEND_CHUNK_BYTES = 4096
        const val MAX_FRAME_PAYLOAD_BYTES =
            TransferFrameCodec.MAX_DATA_CHUNK_BYTES + 40
        const val FRAME_MAGIC = 0x50595644
    }
}
