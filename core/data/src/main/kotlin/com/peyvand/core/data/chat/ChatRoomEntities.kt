package com.peyvand.core.data.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_conversations",
    indices = [
        Index(value = ["peer_identity_fingerprint"], unique = true),
    ],
)
data class ChatConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "peer_identity_fingerprint")
    val peerIdentityFingerprint: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMillis: Long,
    @ColumnInfo(name = "last_activity_at_ms")
    val lastActivityAtMillis: Long,
    @ColumnInfo(name = "next_local_sequence")
    val nextLocalSequence: Long,
    @ColumnInfo(name = "last_peer_sequence")
    val lastPeerSequence: Long,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["conversation_id", "direction", "sender_sequence"],
            unique = true,
        ),
        Index(value = ["conversation_id", "created_at_ms", "sender_sequence"]),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    /** 0 = local sender, 1 = paired peer. */
    val direction: Int,
    @ColumnInfo(name = "sender_sequence")
    val senderSequence: Long,
    @ColumnInfo(name = "observed_peer_sequence")
    val observedPeerSequence: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMillis: Long,
    @ColumnInfo(name = "payload_nonce")
    val payloadNonce: ByteArray,
    @ColumnInfo(name = "payload_ciphertext")
    val payloadCiphertext: ByteArray,
    @ColumnInfo(name = "delivery_state")
    val deliveryState: String,
)

@Entity(
    tableName = "chat_outbox",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["queued_at_ms", "message_id"]),
    ],
)
data class ChatOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "queued_at_ms")
    val queuedAtMillis: Long,
)

/**
 * Binds the local database to the non-exportable Android Keystore key.
 * If this row exists while the key is absent, existing data is unavailable and the
 * repository must fail closed rather than silently initialize a replacement key.
 */
@Entity(tableName = "chat_trusted_peers")
data class ChatTrustedPeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "identity_fingerprint")
    val identityFingerprint: String,
    @ColumnInfo(name = "public_key_encoded")
    val publicKeyEncoded: ByteArray,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "first_seen_at_ms")
    val firstSeenAtMillis: Long,
    @ColumnInfo(name = "last_authenticated_at_ms")
    val lastAuthenticatedAtMillis: Long,
)

@Entity(tableName = "chat_crypto_state")
data class ChatCryptoStateEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "key_version")
    val keyVersion: Int,
    @ColumnInfo(name = "initialized_at_ms")
    val initializedAtMillis: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val CURRENT_KEY_VERSION = 1
    }
}
