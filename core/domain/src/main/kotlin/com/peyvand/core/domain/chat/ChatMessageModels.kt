package com.peyvand.core.domain.chat

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Stable peer identity represented by the SHA-256 fingerprint of a persistent public key.
 * Discovery names, MAC/IP addresses and session-scoped certificates must never be used.
 */
@JvmInline
value class PeerIdentityId private constructor(val sha256Fingerprint: String) {
    companion object {
        fun fromSha256Fingerprint(value: String): PeerIdentityId {
            val normalized = value.lowercase(Locale.ROOT)
            require(normalized.length == 64) {
                "Peer identity fingerprint must contain exactly 64 hexadecimal characters."
            }
            require(normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Peer identity fingerprint contains a non-hexadecimal character."
            }
            return PeerIdentityId(normalized)
        }
    }
}

@JvmInline
value class ChatConversationId(val value: String) {
    companion object {
        fun new(): ChatConversationId = ChatConversationId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class ChatMessageId(val value: String) {
    companion object {
        fun new(): ChatMessageId = ChatMessageId(UUID.randomUUID().toString())
    }
}

enum class ChatMessageDirection {
    OUTGOING,
    INCOMING,
}

enum class ChatMessageDeliveryState {
    WAITING_FOR_CONNECTION,
    SYNCHRONIZING,
    DELIVERED,
    RECEIVED,
}

data class ChatMessage(
    val id: ChatMessageId,
    val conversationId: ChatConversationId,
    val peerIdentityId: PeerIdentityId,
    val direction: ChatMessageDirection,
    /** Monotonic sequence allocated by this sender for this conversation. */
    val senderSequence: Long,
    /** Highest contiguous sequence from the peer observed when this message was created. */
    val observedPeerSequence: Long,
    val createdAtEpochMillis: Long,
    val body: String,
    val deliveryState: ChatMessageDeliveryState,
)

enum class ChatMessageValidationError {
    EMPTY,
    TOO_LARGE,
    INVALID_UNICODE,
    FORBIDDEN_CONTROL_CHARACTER,
}

class InvalidChatMessageException(
    val reason: ChatMessageValidationError,
) : IllegalArgumentException("Chat message failed input validation: " + reason.name)

class ChatOutboxLimitExceededException :
    IllegalStateException("The local outgoing message queue is full. Existing messages were preserved.")

/**
 * The original message text is preserved. It is not trimmed or normalized before storage.
 */
object ChatMessageRules {
    const val MAX_MESSAGE_UTF8_BYTES = 4096
    const val MAX_PENDING_OUTGOING_MESSAGES_PER_PEER = 1024

    fun validateBody(body: String) {
        if (body.isBlank()) {
            throw InvalidChatMessageException(ChatMessageValidationError.EMPTY)
        }

        var index = 0
        while (index < body.length) {
            val current = body[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= body.length || !Character.isLowSurrogate(body[index + 1])) {
                        throw InvalidChatMessageException(ChatMessageValidationError.INVALID_UNICODE)
                    }
                    index += 2
                    continue
                }
                Character.isLowSurrogate(current) -> {
                    throw InvalidChatMessageException(ChatMessageValidationError.INVALID_UNICODE)
                }
                current == '\u0000' -> {
                    throw InvalidChatMessageException(
                        ChatMessageValidationError.FORBIDDEN_CONTROL_CHARACTER,
                    )
                }
                Character.isISOControl(current) && current != '\n' &&
                    current != '\r' && current != '\t' -> {
                    throw InvalidChatMessageException(
                        ChatMessageValidationError.FORBIDDEN_CONTROL_CHARACTER,
                    )
                }
            }
            index += 1
        }

        if (body.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_UTF8_BYTES) {
            throw InvalidChatMessageException(ChatMessageValidationError.TOO_LARGE)
        }
    }
}

/**
 * Local persistence contract. Implementations must atomically persist an outgoing message
 * and its outbox row before enqueueOutgoingMessage() returns.
 *
 * Delivery confirmation may only be invoked after the sync layer validates a receipt
 * from the authenticated expected peer proving durable receiver-side commit.
 */
interface ChatRepository {
    fun observeRecentMessages(
        peerIdentityId: PeerIdentityId,
        limit: Int = 100,
    ): Flow<List<ChatMessage>>

    fun observePendingOutboxCount(peerIdentityId: PeerIdentityId): Flow<Int>

    suspend fun enqueueOutgoingMessage(
        peerIdentityId: PeerIdentityId,
        body: String,
        createdAtEpochMillis: Long,
    ): ChatMessage

    suspend fun loadPendingOutgoingMessages(
        peerIdentityId: PeerIdentityId,
        limit: Int = 32,
    ): List<ChatMessage>

    suspend fun confirmDeliveredAfterAuthenticatedReceipt(
        peerIdentityId: PeerIdentityId,
        messageId: ChatMessageId,
    ): Boolean
}
