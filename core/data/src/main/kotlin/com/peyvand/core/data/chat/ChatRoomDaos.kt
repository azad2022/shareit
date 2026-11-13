package com.peyvand.core.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatConversationDao {
    @Query(
        "SELECT * FROM chat_conversations " +
            "WHERE peer_identity_fingerprint = :peerFingerprint LIMIT 1",
    )
    suspend fun findByPeerFingerprint(peerFingerprint: String): ChatConversationEntity?

    @Query(
        "SELECT * FROM chat_conversations " +
            "WHERE peer_identity_fingerprint = :peerFingerprint LIMIT 1",
    )
    fun observeByPeerFingerprint(peerFingerprint: String): Flow<ChatConversationEntity?>

    @Query("SELECT * FROM chat_conversations ORDER BY last_activity_at_ms DESC")
    fun observeAll(): Flow<List<ChatConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(conversation: ChatConversationEntity)

    @Query(
        "UPDATE chat_conversations SET last_peer_sequence = :nextSequence, " +
            "last_activity_at_ms = :activityAtMillis " +
            "WHERE id = :conversationId AND last_peer_sequence = :expectedSequence",
    )
    suspend fun advancePeerSequence(
        conversationId: String,
        expectedSequence: Long,
        nextSequence: Long,
        activityAtMillis: Long,
    ): Int

    @Query(
        "UPDATE chat_conversations " +
            "SET next_local_sequence = :nextSequence, last_activity_at_ms = :activityAtMillis " +
            "WHERE id = :conversationId AND next_local_sequence = :expectedSequence",
    )
    suspend fun advanceLocalSequence(
        conversationId: String,
        expectedSequence: Long,
        nextSequence: Long,
        activityAtMillis: Long,
    ): Int
}

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: ChatMessageEntity)

    @Query(
        "SELECT * FROM chat_messages WHERE conversation_id = :conversationId " +
            "ORDER BY created_at_ms DESC, direction DESC, sender_sequence DESC LIMIT :limit",
    )
    fun observeRecent(conversationId: String, limit: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE id = :messageId LIMIT 1")
    suspend fun findById(messageId: String): ChatMessageEntity?

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun countAll(): Int

    @Query(
        "UPDATE chat_messages SET delivery_state = 'DELIVERED' " +
            "WHERE id = :messageId AND conversation_id = :conversationId " +
            "AND direction = 0 AND delivery_state = 'WAITING_FOR_CONNECTION'",
    )
    suspend fun markDelivered(messageId: String, conversationId: String): Int
}

@Dao
interface ChatOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(outbox: ChatOutboxEntity)

    @Query(
        "SELECT m.* FROM chat_outbox AS o " +
            "INNER JOIN chat_messages AS m ON m.id = o.message_id " +
            "WHERE m.conversation_id = :conversationId " +
            "AND m.delivery_state = 'WAITING_FOR_CONNECTION' " +
            "ORDER BY m.sender_sequence ASC LIMIT :limit",
    )
    suspend fun loadPending(conversationId: String, limit: Int): List<ChatMessageEntity>

    @Query(
        "SELECT COUNT(*) FROM chat_outbox AS o " +
            "INNER JOIN chat_messages AS m ON m.id = o.message_id " +
            "WHERE m.conversation_id = :conversationId",
    )
    fun observeCount(conversationId: String): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM chat_outbox AS o " +
            "INNER JOIN chat_messages AS m ON m.id = o.message_id " +
            "WHERE m.conversation_id = :conversationId",
    )
    suspend fun countPending(conversationId: String): Int

    @Query("DELETE FROM chat_outbox WHERE message_id = :messageId")
    suspend fun delete(messageId: String)
}

@Dao
interface ChatTrustedPeerDao {
    @Query("SELECT * FROM chat_trusted_peers ORDER BY last_authenticated_at_ms DESC")
    fun observeAll(): Flow<List<ChatTrustedPeerEntity>>

    @Query("SELECT * FROM chat_trusted_peers WHERE identity_fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): ChatTrustedPeerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(peer: ChatTrustedPeerEntity)

    @Query(
        "UPDATE chat_trusted_peers SET last_authenticated_at_ms = :lastAuthenticatedAtMillis " +
            "WHERE identity_fingerprint = :identityFingerprint",
    )
    suspend fun updateLastAuthenticated(
        identityFingerprint: String,
        lastAuthenticatedAtMillis: Long,
    ): Int
}

@Dao
interface ChatCryptoStateDao {
    @Query("SELECT * FROM chat_crypto_state WHERE id = 1 LIMIT 1")
    suspend fun getState(): ChatCryptoStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(state: ChatCryptoStateEntity)
}
