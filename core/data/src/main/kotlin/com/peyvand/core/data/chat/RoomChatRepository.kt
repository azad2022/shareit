package com.peyvand.core.data.chat

import androidx.room.withTransaction
import com.peyvand.core.domain.chat.ChatConversationId
import com.peyvand.core.domain.chat.ChatMessage
import com.peyvand.core.domain.chat.ChatMessageDeliveryState
import com.peyvand.core.domain.chat.ChatMessageDirection
import com.peyvand.core.domain.chat.ChatMessageId
import com.peyvand.core.domain.chat.ChatMessageRules
import com.peyvand.core.domain.chat.ChatOutboxLimitExceededException
import com.peyvand.core.domain.chat.ChatRepository
import com.peyvand.core.domain.chat.PeerIdentityId
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ChatLocalDataCorruptionException(message: String) : IllegalStateException(message)

class ChatMessageSequenceException(
    val expected: Long,
    val received: Long,
) : IllegalStateException("Expected incoming chat sequence $expected but received $received.")

class RoomChatRepository(
    private val database: ChatDatabase,
    private val cipher: AndroidKeystoreChatPayloadCipher = AndroidKeystoreChatPayloadCipher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatRepository {

    private val conversations = database.conversations()
    private val messages = database.messages()
    private val outbox = database.outbox()
    private val cryptoState = database.cryptoState()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecentMessages(
        peerIdentityId: PeerIdentityId,
        limit: Int,
    ): Flow<List<ChatMessage>> {
        require(limit in MIN_QUERY_LIMIT..MAX_QUERY_LIMIT)

        return conversations.observeByPeerFingerprint(peerIdentityId.sha256Fingerprint)
            .flatMapLatest { conversation ->
                if (conversation == null) {
                    flowOf(emptyList())
                } else {
                    messages.observeRecent(conversation.id, limit).map { rows ->
                        if (rows.isEmpty()) {
                            emptyList()
                        } else {
                            val key = requireExistingEncryptionKey()
                            rows.asReversed().map { row ->
                                row.toDomain(conversation, key)
                            }
                        }
                    }
                }
            }
            .flowOn(ioDispatcher)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePendingOutboxCount(
        peerIdentityId: PeerIdentityId,
    ): Flow<Int> =
        conversations.observeByPeerFingerprint(peerIdentityId.sha256Fingerprint)
            .flatMapLatest { conversation ->
                if (conversation == null) {
                    flowOf(0)
                } else {
                    outbox.observeCount(conversation.id)
                }
            }
            .flowOn(ioDispatcher)

    override suspend fun enqueueOutgoingMessage(
        peerIdentityId: PeerIdentityId,
        body: String,
        createdAtEpochMillis: Long,
    ): ChatMessage {
        ChatMessageRules.validateBody(body)
        require(createdAtEpochMillis >= 0L)

        return withContext(ioDispatcher) {
            database.withTransaction {
                val conversation = getOrCreateConversation(
                    peerIdentityId = peerIdentityId,
                    createdAtEpochMillis = createdAtEpochMillis,
                )

                if (outbox.countPending(conversation.id) >=
                    ChatMessageRules.MAX_PENDING_OUTGOING_MESSAGES_PER_PEER
                ) {
                    throw ChatOutboxLimitExceededException()
                }

                val key = encryptionKeyForWrite()
                val sequence = conversation.nextLocalSequence
                check(sequence > 0L && sequence < Long.MAX_VALUE) {
                    "The local chat sequence is exhausted."
                }

                val messageId = ChatMessageId.new()
                val associatedData = associatedData(
                    peerIdentityFingerprint = peerIdentityId.sha256Fingerprint,
                    conversationId = conversation.id,
                    messageId = messageId.value,
                    direction = LOCAL_SENDER,
                    senderSequence = sequence,
                    observedPeerSequence = conversation.lastPeerSequence,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
                val encrypted = body.toByteArray(StandardCharsets.UTF_8).useAndErase { plaintext ->
                    cipher.encrypt(
                        plaintext = plaintext,
                        associatedData = associatedData,
                        key = key,
                    )
                }

                messages.insert(
                    ChatMessageEntity(
                        id = messageId.value,
                        conversationId = conversation.id,
                        direction = LOCAL_SENDER,
                        senderSequence = sequence,
                        observedPeerSequence = conversation.lastPeerSequence,
                        createdAtMillis = createdAtEpochMillis,
                        payloadNonce = encrypted.nonce,
                        payloadCiphertext = encrypted.ciphertext,
                        deliveryState = STATE_WAITING_FOR_CONNECTION,
                    ),
                )
                outbox.insert(
                    ChatOutboxEntity(
                        messageId = messageId.value,
                        queuedAtMillis = createdAtEpochMillis,
                    ),
                )

                val updated = conversations.advanceLocalSequence(
                    conversationId = conversation.id,
                    expectedSequence = sequence,
                    nextSequence = sequence + 1L,
                    activityAtMillis = createdAtEpochMillis,
                )
                check(updated == 1) {
                    "The local chat sequence changed unexpectedly inside its transaction."
                }

                ChatMessage(
                    id = messageId,
                    conversationId = ChatConversationId(conversation.id),
                    peerIdentityId = peerIdentityId,
                    direction = ChatMessageDirection.OUTGOING,
                    senderSequence = sequence,
                    observedPeerSequence = conversation.lastPeerSequence,
                    createdAtEpochMillis = createdAtEpochMillis,
                    body = body,
                    deliveryState = ChatMessageDeliveryState.WAITING_FOR_CONNECTION,
                )
            }
        }
    }

    override suspend fun acceptIncomingMessage(
        peerIdentityId: PeerIdentityId,
        messageId: ChatMessageId,
        senderSequence: Long,
        observedPeerSequence: Long,
        createdAtEpochMillis: Long,
        body: String,
    ): ChatMessage {
        ChatMessageRules.validateBody(body)
        require(createdAtEpochMillis >= 0L)
        require(senderSequence > 0L)
        require(observedPeerSequence >= 0L)
        require(observedPeerSequence < Long.MAX_VALUE)
        require(runCatching { UUID.fromString(messageId.value) }.isSuccess)

        return withContext(ioDispatcher) {
            database.withTransaction {
                val conversation = getOrCreateConversation(peerIdentityId, createdAtEpochMillis)
                val existing = messages.findById(messageId.value)
                if (existing != null) {
                    if (existing.conversationId != conversation.id ||
                        existing.direction != PEER_SENDER ||
                        existing.senderSequence != senderSequence ||
                        existing.observedPeerSequence != observedPeerSequence ||
                        existing.createdAtMillis != createdAtEpochMillis
                    ) {
                        throw ChatLocalDataCorruptionException(
                            "A duplicate chat message ID has conflicting immutable metadata.",
                        )
                    }
                    return@withTransaction existing.toDomain(
                        conversation,
                        requireExistingEncryptionKey(),
                    )
                }

                if (senderSequence != conversation.lastPeerSequence + 1L) {
                    throw ChatMessageSequenceException(
                        expected = conversation.lastPeerSequence + 1L,
                        received = senderSequence,
                    )
                }
                if (observedPeerSequence >= conversation.nextLocalSequence) {
                    throw ChatLocalDataCorruptionException(
                        "The remote message acknowledges a local sequence that was never allocated.",
                    )
                }

                val key = encryptionKeyForWrite()
                val aad = associatedData(
                    peerIdentityFingerprint = peerIdentityId.sha256Fingerprint,
                    conversationId = conversation.id,
                    messageId = messageId.value,
                    direction = PEER_SENDER,
                    senderSequence = senderSequence,
                    observedPeerSequence = observedPeerSequence,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
                val encrypted = body.toByteArray(StandardCharsets.UTF_8).useAndErase { plaintext ->
                    cipher.encrypt(plaintext, aad, key)
                }
                messages.insert(
                    ChatMessageEntity(
                        id = messageId.value,
                        conversationId = conversation.id,
                        direction = PEER_SENDER,
                        senderSequence = senderSequence,
                        observedPeerSequence = observedPeerSequence,
                        createdAtMillis = createdAtEpochMillis,
                        payloadNonce = encrypted.nonce,
                        payloadCiphertext = encrypted.ciphertext,
                        deliveryState = STATE_RECEIVED,
                    ),
                )
                val advanced = conversations.advancePeerSequence(
                    conversationId = conversation.id,
                    expectedSequence = conversation.lastPeerSequence,
                    nextSequence = senderSequence,
                    activityAtMillis = maxOf(conversation.lastActivityAtMillis, createdAtEpochMillis),
                )
                check(advanced == 1) {
                    "The remote chat sequence changed unexpectedly inside its transaction."
                }
                ChatMessage(
                    id = messageId,
                    conversationId = ChatConversationId(conversation.id),
                    peerIdentityId = peerIdentityId,
                    direction = ChatMessageDirection.INCOMING,
                    senderSequence = senderSequence,
                    observedPeerSequence = observedPeerSequence,
                    createdAtEpochMillis = createdAtEpochMillis,
                    body = body,
                    deliveryState = ChatMessageDeliveryState.RECEIVED,
                )
            }
        }
    }

    override suspend fun loadPendingOutgoingMessages(
        peerIdentityId: PeerIdentityId,
        limit: Int,
    ): List<ChatMessage> {
        require(limit in MIN_QUERY_LIMIT..MAX_QUERY_LIMIT)

        return withContext(ioDispatcher) {
            database.withTransaction {
                val conversation = conversations.findByPeerFingerprint(
                    peerIdentityId.sha256Fingerprint,
                ) ?: return@withTransaction emptyList()

                val rows = outbox.loadPending(conversation.id, limit)
                if (rows.isEmpty()) {
                    return@withTransaction emptyList()
                }

                val key = requireExistingEncryptionKey()
                rows.map { row -> row.toDomain(conversation, key) }
            }
        }
    }

    /**
     * The synchronization coordinator may call this only after authenticating the expected
     * persistent peer identity and validating that the peer's receipt means durable commit.
     */
    override suspend fun confirmDeliveredAfterAuthenticatedReceipt(
        peerIdentityId: PeerIdentityId,
        messageId: ChatMessageId,
    ): Boolean = withContext(ioDispatcher) {
        database.withTransaction {
            val conversation = conversations.findByPeerFingerprint(
                peerIdentityId.sha256Fingerprint,
            ) ?: return@withTransaction false

            val message = messages.findById(messageId.value)
                ?: return@withTransaction false

            if (message.conversationId != conversation.id ||
                message.direction != LOCAL_SENDER
            ) {
                return@withTransaction false
            }

            if (message.deliveryState == STATE_DELIVERED) {
                return@withTransaction true
            }
            if (message.deliveryState != STATE_WAITING_FOR_CONNECTION) {
                return@withTransaction false
            }

            val updated = messages.markDelivered(messageId.value, conversation.id)
            if (updated != 1) {
                return@withTransaction false
            }

            outbox.delete(messageId.value)
            true
        }
    }

    private suspend fun getOrCreateConversation(
        peerIdentityId: PeerIdentityId,
        createdAtEpochMillis: Long,
    ): ChatConversationEntity {
        conversations.findByPeerFingerprint(peerIdentityId.sha256Fingerprint)?.let {
            return it
        }

        val candidate = ChatConversationEntity(
            id = UUID.randomUUID().toString(),
            peerIdentityFingerprint = peerIdentityId.sha256Fingerprint,
            createdAtMillis = createdAtEpochMillis,
            lastActivityAtMillis = createdAtEpochMillis,
            nextLocalSequence = 1L,
            lastPeerSequence = 0L,
        )
        conversations.insertIfAbsent(candidate)

        return conversations.findByPeerFingerprint(peerIdentityId.sha256Fingerprint)
            ?: throw ChatLocalDataCorruptionException(
                "The conversation row was not available after its insert transaction.",
            )
    }

    private suspend fun encryptionKeyForWrite(): SecretKey {
        val state = cryptoState.getState()
        if (state == null) {
            if (messages.countAll() > 0) {
                throw ChatLocalDataCorruptionException(
                    "Chat messages exist without the encryption-key metadata row.",
                )
            }

            val key = cipher.getOrCreateKey(allowCreate = true)
            cryptoState.insertIfAbsent(
                ChatCryptoStateEntity(
                    keyVersion = ChatCryptoStateEntity.CURRENT_KEY_VERSION,
                    initializedAtMillis = System.currentTimeMillis(),
                ),
            )
            return key
        }

        if (state.keyVersion != ChatCryptoStateEntity.CURRENT_KEY_VERSION) {
            throw ChatLocalDataCorruptionException("Unsupported local chat key version.")
        }
        return cipher.getOrCreateKey(allowCreate = false)
    }

    private suspend fun requireExistingEncryptionKey(): SecretKey {
        val state = cryptoState.getState()
        if (state == null) {
            if (messages.countAll() > 0) {
                throw ChatLocalDataCorruptionException(
                    "Chat messages exist without the encryption-key metadata row.",
                )
            }
            throw ChatEncryptionKeyUnavailableException()
        }

        if (state.keyVersion != ChatCryptoStateEntity.CURRENT_KEY_VERSION) {
            throw ChatLocalDataCorruptionException("Unsupported local chat key version.")
        }
        return cipher.getOrCreateKey(allowCreate = false)
    }

    private fun ChatMessageEntity.toDomain(
        conversation: ChatConversationEntity,
        key: SecretKey,
    ): ChatMessage {
        val messageDirection = when (this.direction) {
            LOCAL_SENDER -> ChatMessageDirection.OUTGOING
            PEER_SENDER -> ChatMessageDirection.INCOMING
            else -> throw ChatLocalDataCorruptionException("Unknown chat message direction.")
        }
        val state = when (deliveryState) {
            STATE_WAITING_FOR_CONNECTION -> ChatMessageDeliveryState.WAITING_FOR_CONNECTION
            STATE_DELIVERED -> ChatMessageDeliveryState.DELIVERED
            STATE_RECEIVED -> ChatMessageDeliveryState.RECEIVED
            else -> throw ChatLocalDataCorruptionException("Unknown chat delivery state.")
        }
        val aad = associatedData(
            peerIdentityFingerprint = conversation.peerIdentityFingerprint,
            conversationId = conversationId,
            messageId = id,
            direction = this.direction,
            senderSequence = senderSequence,
            observedPeerSequence = observedPeerSequence,
            createdAtEpochMillis = createdAtMillis,
        )
        val plaintext = cipher.decrypt(
            nonce = payloadNonce,
            ciphertext = payloadCiphertext,
            associatedData = aad,
            key = key,
        )
        val body = plaintext.useAndErase { decryptedBytes ->
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decryptedBytes))
                    .toString()
            } catch (exception: Exception) {
                throw ChatPayloadIntegrityException(exception)
            }
        }

        return ChatMessage(
            id = ChatMessageId(id),
            conversationId = ChatConversationId(conversationId),
            peerIdentityId = PeerIdentityId.fromSha256Fingerprint(
                conversation.peerIdentityFingerprint,
            ),
            direction = messageDirection,
            senderSequence = senderSequence,
            observedPeerSequence = observedPeerSequence,
            createdAtEpochMillis = createdAtMillis,
            body = body,
            deliveryState = state,
        )
    }

    private fun associatedData(
        peerIdentityFingerprint: String,
        conversationId: String,
        messageId: String,
        direction: Int,
        senderSequence: Long,
        observedPeerSequence: Long,
        createdAtEpochMillis: Long,
    ): ByteArray {
        val fields = listOf(
            AAD_VERSION.toByteArray(StandardCharsets.UTF_8),
            peerIdentityFingerprint.toByteArray(StandardCharsets.US_ASCII),
            conversationId.toByteArray(StandardCharsets.UTF_8),
            messageId.toByteArray(StandardCharsets.UTF_8),
        )
        val fixedBytes = Int.SIZE_BYTES + Long.SIZE_BYTES * 3
        val stringBytes = fields.sumOf { Int.SIZE_BYTES + it.size }
        return ByteBuffer.allocate(fixedBytes + stringBytes).apply {
            fields.forEach { field ->
                putInt(field.size)
                put(field)
            }
            putInt(direction)
            putLong(senderSequence)
            putLong(observedPeerSequence)
            putLong(createdAtEpochMillis)
        }.array()
    }

    companion object {
        private const val MIN_QUERY_LIMIT = 1
        private const val MAX_QUERY_LIMIT = 1024
        private const val AAD_VERSION = "peyvand-chat-payload-v1"
        private const val LOCAL_SENDER = 0
        private const val PEER_SENDER = 1
        private const val STATE_WAITING_FOR_CONNECTION = "WAITING_FOR_CONNECTION"
        private const val STATE_DELIVERED = "DELIVERED"
        private const val STATE_RECEIVED = "RECEIVED"
    }
}
