package com.peyvand.core.transfer.chat

import com.peyvand.core.domain.chat.ChatMessage
import com.peyvand.core.domain.chat.ChatMessageDirection
import com.peyvand.core.domain.chat.ChatMessageId
import com.peyvand.core.domain.chat.ChatRepository
import com.peyvand.core.domain.chat.PeerIdentityId
import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the one exclusive authenticated stream while a conversation is active.
 *
 * Incoming messages are ACKed only after Room has committed them. Outgoing messages remain
 * in the durable outbox until a matching message-ID + sender-sequence ACK is received.
 * This coordinator must never run concurrently with the file-transfer coordinator.
 */
class ChatSessionCoordinator(
    private val scope: CoroutineScope,
    private val channel: AuthenticatedPeerChannel,
    private val peerIdentityId: PeerIdentityId,
    private val repository: ChatRepository,
    private val onIncomingCommitted: (ChatMessage) -> Unit = {},
    private val onOutgoingDelivered: (ChatMessageId, Long) -> Unit = { _, _ -> },
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {

    private val outputMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val sentMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var readerJob: Job? = null
    private var replayJob: Job? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "Chat session is already closed." }
        check(readerJob == null) { "Chat session has already started." }

        readerJob = scope.launch(Dispatchers.IO) {
            try {
                readLoop()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                fail(exception)
            }
        }
        replayJob = scope.launch(Dispatchers.IO) {
            try {
                repository.loadPendingOutgoingMessages(
                    peerIdentityId = peerIdentityId,
                    limit = CHAT_OUTBOX_BATCH_LIMIT,
                ).forEach { message ->
                    currentCoroutineContext().ensureActive()
                    sendPersistedMessage(message)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                fail(exception)
            }
        }
    }

    /** The message must already be durably enqueued before this method is called. */
    suspend fun sendPersistedMessage(message: ChatMessage) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            check(!closed.get()) { "Chat session is closed." }
            require(message.peerIdentityId == peerIdentityId)
            require(message.direction == ChatMessageDirection.OUTGOING)
            if (!sentMessageIds.add(message.id.value)) return@withContext

            try {
                val frame = ChatMessageFrameCodec.encodeMessage(
                    sessionId = channel.sessionId,
                    message = ChatWireMessage(
                        messageId = message.id,
                        senderSequence = message.senderSequence,
                        observedPeerSequence = message.observedPeerSequence,
                        createdAtEpochMillis = message.createdAtEpochMillis,
                        body = message.body,
                    ),
                )
                outputMutex.withLock {
                    check(!closed.get()) { "Chat session is closed." }
                    TransferFrameCodec.write(frame, channel.outputStream)
                    channel.outputStream.flush()
                }
            } catch (exception: Exception) {
                sentMessageIds.remove(message.id.value)
                if (exception !is CancellationException) fail(exception)
                throw exception
            }
        }

    private suspend fun readLoop() {
        while (!closed.get()) {
            val frame = TransferFrameCodec.read(channel.inputStream) ?: throw EOFException(
                "The authenticated peer closed the chat stream.",
            )
            if (frame.sessionId != channel.sessionId) {
                throw ChatWireProtocolException("Chat frame belongs to another session.")
            }

            when (frame.type) {
                TransferFrameType.CHAT_MESSAGE -> {
                    val wireMessage = ChatMessageFrameCodec.decodeMessage(frame)
                    val committed = repository.acceptIncomingMessage(
                        peerIdentityId = peerIdentityId,
                        messageId = wireMessage.messageId,
                        senderSequence = wireMessage.senderSequence,
                        observedPeerSequence = wireMessage.observedPeerSequence,
                        createdAtEpochMillis = wireMessage.createdAtEpochMillis,
                        body = wireMessage.body,
                    )

                    // The Room transaction has committed before the peer can be acknowledged.
                    val acknowledgement = ChatMessageFrameCodec.encodeAcknowledgement(
                        sessionId = channel.sessionId,
                        acknowledgement = ChatWireAcknowledgement(
                            messageId = committed.id,
                            senderSequence = committed.senderSequence,
                        ),
                    )
                    outputMutex.withLock {
                        if (!closed.get()) {
                            TransferFrameCodec.write(acknowledgement, channel.outputStream)
                            channel.outputStream.flush()
                        }
                    }
                    onIncomingCommitted(committed)
                }

                TransferFrameType.CHAT_ACK -> {
                    val ack = ChatMessageFrameCodec.decodeAcknowledgement(frame)
                    val confirmed = repository.confirmDeliveredAfterAuthenticatedReceipt(
                        peerIdentityId = peerIdentityId,
                        messageId = ack.messageId,
                        senderSequence = ack.senderSequence,
                    )
                    if (!confirmed) {
                        throw ChatWireProtocolException(
                            "The peer acknowledged an unknown or mismatched chat message.",
                        )
                    }
                    onOutgoingDelivered(ack.messageId, ack.senderSequence)
                }

                else -> throw ChatWireProtocolException(
                    "A non-chat frame arrived while the chat coordinator exclusively owns the channel.",
                )
            }
        }
    }

    private fun fail(exception: Throwable) {
        if (closed.compareAndSet(false, true)) {
            readerJob?.cancel()
            replayJob?.cancel()
            runCatching { channel.close() }
            if (failureReported.compareAndSet(false, true)) onFailure(exception)
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            readerJob?.cancel()
            replayJob?.cancel()
            runCatching { channel.close() }
        }
    }

    private companion object {
        const val CHAT_OUTBOX_BATCH_LIMIT = 1024
    }
}
