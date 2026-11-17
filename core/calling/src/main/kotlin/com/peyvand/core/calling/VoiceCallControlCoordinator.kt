package com.peyvand.core.calling

import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns one CALL_CONTROL lease for one authenticated peer session, not the parent TLS socket. */
class VoiceCallControlCoordinator(
    private val channel: AuthenticatedPeerChannel,
    private val scope: CoroutineScope,
    private val onSignal: (VoiceCallSignal) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {

    private val outputMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private var readerJob: Job? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "Voice call control channel is closed." }
        check(readerJob == null) { "Voice call control reader already started." }
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                readLoop()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                fail(exception)
            }
        }
    }

    suspend fun send(signal: VoiceCallSignal) = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Voice call control channel is closed." }
        outputMutex.withLock {
            check(!closed.get()) { "Voice call control channel is closed." }
            val frame = VoiceCallSignalCodec.encode(channel.sessionId, signal)
            TransferFrameCodec.write(frame, channel.outputStream)
            channel.outputStream.flush()
        }
    }

    private fun readLoop() {
        while (!closed.get()) {
            val frame = TransferFrameCodec.read(channel.inputStream)
                ?: throw EOFException("The authenticated peer closed the voice call control channel.")
            val signal = VoiceCallSignalCodec.decode(frame, channel.sessionId)
            onSignal(signal)
        }
    }

    private fun fail(exception: Throwable) {
        if (closed.compareAndSet(false, true)) {
            readerJob?.cancel()
            runCatching { channel.close() }
            if (failureReported.compareAndSet(false, true)) runCatching { onFailure(exception) }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            readerJob?.cancel()
            runCatching { channel.close() }
        }
    }
}
