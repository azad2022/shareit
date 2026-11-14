package com.peyvand.core.transfer.chat

import com.peyvand.core.domain.chat.ChatConversationId
import com.peyvand.core.domain.chat.ChatMessage
import com.peyvand.core.domain.chat.ChatMessageDeliveryState
import com.peyvand.core.domain.chat.ChatMessageDirection
import com.peyvand.core.domain.chat.ChatMessageId
import com.peyvand.core.domain.chat.ChatRepository
import com.peyvand.core.domain.chat.PeerIdentityId
import com.peyvand.core.domain.connection.AuthenticatedPeerChannelPurpose
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.session.AuthenticatedPeerChannelMultiplexer
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionCoordinatorTest {
    private val sessionId = UUID.fromString("e6b90d7a-0a6c-4bd7-a2ab-7f4e3f27e1db")
    private val peerIdentity = PeerIdentityId.fromSha256Fingerprint("a".repeat(64))

    @Test
    fun `receiver sends acknowledgement only after durable incoming commit returns`() = runBlocking {
        val pair = createPair()
        val senderChannel = pair.first.openChannel(AuthenticatedPeerChannelPurpose.CHAT)!!
        val receiverChannel = pair.second.openChannel(AuthenticatedPeerChannelPurpose.CHAT)!!
        val commitGate = CompletableDeferred<Unit>()
        val receiverRepository = FakeChatRepository(
            peerIdentityId = peerIdentity,
            incomingCommitGate = commitGate,
        )
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receiver = ChatSessionCoordinator(
            scope = receiverScope,
            channel = receiverChannel,
            peerIdentityId = peerIdentity,
            repository = receiverRepository,
        )
        val acknowledgementReader = async(Dispatchers.IO) {
            TransferFrameCodec.read(senderChannel.inputStream)
        }

        try {
            receiver.start()
            val outgoing = ChatWireMessage(
                messageId = ChatMessageId("b8b31ac0-671e-474d-ad8f-12981a0e45cb"),
                senderSequence = 1L,
                observedPeerSequence = 0L,
                createdAtEpochMillis = 1_800_000_000_000L,
                body = "این پیام باید پیش از تأیید، پایدار ذخیره شود.",
            )
            TransferFrameCodec.write(
                ChatMessageFrameCodec.encodeMessage(sessionId, outgoing),
                senderChannel.outputStream,
            )
            senderChannel.outputStream.flush()

            withTimeout(2_000) { receiverRepository.incomingCommitStarted.await() }

            // The repository is intentionally blocked before durable commit returns.
            // An ACK arriving during this interval would violate the delivery contract.
            assertNull(withTimeoutOrNull(150) { acknowledgementReader.await() })

            commitGate.complete(Unit)
            val acknowledgement = withTimeout(3_000) { acknowledgementReader.await() }
            assertEquals(TransferFrameType.CHAT_ACK, acknowledgement?.type)
            assertEquals(sessionId, acknowledgement?.sessionId)
            val decoded = ChatMessageFrameCodec.decodeAcknowledgement(acknowledgement!!)
            assertEquals(outgoing.messageId, decoded.messageId)
            assertEquals(outgoing.senderSequence, decoded.senderSequence)

            val committed = withTimeout(2_000) { receiverRepository.incomingCommitted.await() }
            assertEquals(outgoing.messageId, committed.id)
            assertEquals(ChatMessageDirection.INCOMING, committed.direction)
            assertEquals(ChatMessageDeliveryState.RECEIVED, committed.deliveryState)
        } finally {
            commitGate.complete(Unit)
            receiver.close()
            senderChannel.close()
            pair.first.close()
            pair.second.close()
            receiverScope.cancel()
        }
    }

    @Test
    fun `outbox is marked delivered only after matching authenticated channel acknowledgement`() = runBlocking {
        val pair = createPair()
        val senderChannel = pair.first.openChannel(AuthenticatedPeerChannelPurpose.CHAT)!!
        val receiverChannel = pair.second.openChannel(AuthenticatedPeerChannelPurpose.CHAT)!!
        val senderRepository = FakeChatRepository(peerIdentity)
        val receiverRepository = FakeChatRepository(peerIdentity)
        val message = senderRepository.enqueueOutgoingMessage(
            peerIdentityId = peerIdentity,
            body = "پیام خروجی باید با ACK متناظر تحویل‌شده شود.",
            createdAtEpochMillis = 1_800_000_000_100L,
        )
        val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sender = ChatSessionCoordinator(
            scope = senderScope,
            channel = senderChannel,
            peerIdentityId = peerIdentity,
            repository = senderRepository,
        )
        val receiver = ChatSessionCoordinator(
            scope = receiverScope,
            channel = receiverChannel,
            peerIdentityId = peerIdentity,
            repository = receiverRepository,
        )

        try {
            sender.start()
            receiver.start()

            val delivered = withTimeout(4_000) { senderRepository.delivered.await() }
            val received = withTimeout(2_000) { receiverRepository.incomingCommitted.await() }

            assertEquals(message.id, delivered.id)
            assertEquals(message.senderSequence, delivered.senderSequence)
            assertEquals(message.id, received.id)
            assertEquals(message.body, received.body)
            assertTrue(senderRepository.loadPendingOutgoingMessages(peerIdentity).isEmpty())
            assertEquals(ChatMessageDeliveryState.DELIVERED, senderRepository.history.value.single().deliveryState)
        } finally {
            sender.close()
            receiver.close()
            pair.first.close()
            pair.second.close()
            senderScope.cancel()
            receiverScope.cancel()
        }
    }

    private data class MultiplexerPair(
        val first: AuthenticatedPeerChannelMultiplexer,
        val second: AuthenticatedPeerChannelMultiplexer,
    )

    private fun createPair(): MultiplexerPair {
        val firstInput = PipedInputStream(256 * 1024)
        val secondOutput = PipedOutputStream(firstInput)
        val secondInput = PipedInputStream(256 * 1024)
        val firstOutput = PipedOutputStream(secondInput)

        val first = AuthenticatedPeerChannelMultiplexer(
            sessionId = sessionId,
            socketInput = firstInput,
            socketOutput = firstOutput,
            closeUnderlying = {
                runCatching { firstInput.close() }
                runCatching { firstOutput.close() }
            },
        )
        val second = AuthenticatedPeerChannelMultiplexer(
            sessionId = sessionId,
            socketInput = secondInput,
            socketOutput = secondOutput,
            closeUnderlying = {
                runCatching { secondInput.close() }
                runCatching { secondOutput.close() }
            },
        )
        return MultiplexerPair(first, second)
    }

    private class FakeChatRepository(
        private val peerIdentityId: PeerIdentityId,
        private val incomingCommitGate: CompletableDeferred<Unit>? = null,
    ) : ChatRepository {
        private val lock = Any()
        private var nextLocalSequence = 1L
        private var lastPeerSequence = 0L
        private val outbox = mutableListOf<ChatMessage>()
        val history = MutableStateFlow<List<ChatMessage>>(emptyList())
        private val pendingCount = MutableStateFlow(0)
        val incomingCommitStarted = CompletableDeferred<Unit>()
        val incomingCommitted = CompletableDeferred<ChatMessage>()
        val delivered = CompletableDeferred<ChatMessage>()

        override fun observeRecentMessages(
            peerIdentityId: PeerIdentityId,
            limit: Int,
        ): Flow<List<ChatMessage>> = history

        override fun observePendingOutboxCount(peerIdentityId: PeerIdentityId): Flow<Int> = pendingCount

        override suspend fun enqueueOutgoingMessage(
            peerIdentityId: PeerIdentityId,
            body: String,
            createdAtEpochMillis: Long,
        ): ChatMessage = synchronized(lock) {
            require(peerIdentityId == this.peerIdentityId)
            val message = ChatMessage(
                id = ChatMessageId.new(),
                conversationId = ChatConversationId("test-conversation"),
                peerIdentityId = peerIdentityId,
                direction = ChatMessageDirection.OUTGOING,
                senderSequence = nextLocalSequence++,
                observedPeerSequence = lastPeerSequence,
                createdAtEpochMillis = createdAtEpochMillis,
                body = body,
                deliveryState = ChatMessageDeliveryState.WAITING_FOR_CONNECTION,
            )
            outbox += message
            history.value = history.value + message
            pendingCount.value = outbox.size
            message
        }

        override suspend fun loadPendingOutgoingMessages(
            peerIdentityId: PeerIdentityId,
            limit: Int,
        ): List<ChatMessage> = synchronized(lock) {
            require(peerIdentityId == this.peerIdentityId)
            outbox.sortedBy { it.senderSequence }.take(limit)
        }

        override suspend fun acceptIncomingMessage(
            peerIdentityId: PeerIdentityId,
            messageId: ChatMessageId,
            senderSequence: Long,
            observedPeerSequence: Long,
            createdAtEpochMillis: Long,
            body: String,
        ): ChatMessage {
            require(peerIdentityId == this.peerIdentityId)
            incomingCommitStarted.complete(Unit)
            incomingCommitGate?.await()
            val committed = synchronized(lock) {
                history.value.firstOrNull { it.id == messageId } ?: ChatMessage(
                    id = messageId,
                    conversationId = ChatConversationId("test-conversation"),
                    peerIdentityId = peerIdentityId,
                    direction = ChatMessageDirection.INCOMING,
                    senderSequence = senderSequence,
                    observedPeerSequence = observedPeerSequence,
                    createdAtEpochMillis = createdAtEpochMillis,
                    body = body,
                    deliveryState = ChatMessageDeliveryState.RECEIVED,
                ).also {
                    history.value = history.value + it
                    lastPeerSequence = maxOf(lastPeerSequence, senderSequence)
                }
            }
            incomingCommitted.complete(committed)
            return committed
        }

        override suspend fun confirmDeliveredAfterAuthenticatedReceipt(
            peerIdentityId: PeerIdentityId,
            messageId: ChatMessageId,
            senderSequence: Long,
        ): Boolean = synchronized(lock) {
            if (peerIdentityId != this.peerIdentityId) return@synchronized false
            val queued = outbox.firstOrNull {
                it.id == messageId && it.senderSequence == senderSequence
            } ?: return@synchronized false
            outbox.remove(queued)
            val deliveredMessage = queued.copy(deliveryState = ChatMessageDeliveryState.DELIVERED)
            history.value = history.value.map {
                if (it.id == messageId) deliveredMessage else it
            }
            pendingCount.value = outbox.size
            delivered.complete(deliveredMessage)
            true
        }
    }
}
