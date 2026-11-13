package com.peyvand.core.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatConversationEntity::class,
        ChatMessageEntity::class,
        ChatOutboxEntity::class,
        ChatCryptoStateEntity::class,
        ChatTrustedPeerEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversations(): ChatConversationDao
    abstract fun messages(): ChatMessageDao
    abstract fun outbox(): ChatOutboxDao
    abstract fun cryptoState(): ChatCryptoStateDao
    abstract fun trustedPeers(): ChatTrustedPeerDao
}

object ChatDatabaseFactory {
    const val DATABASE_NAME = "peyvand_local_chat.db"

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS chat_trusted_peers (" +
                    "identity_fingerprint TEXT NOT NULL, " +
                    "public_key_encoded BLOB NOT NULL, " +
                    "display_name TEXT NOT NULL, " +
                    "first_seen_at_ms INTEGER NOT NULL, " +
                    "last_authenticated_at_ms INTEGER NOT NULL, " +
                    "PRIMARY KEY(identity_fingerprint))",
            )
        }
    }

    fun open(
        context: Context,
        databaseName: String = DATABASE_NAME,
    ): ChatDatabase {
        require(databaseName.isNotBlank())
        return Room.databaseBuilder(
            context.applicationContext,
            ChatDatabase::class.java,
            databaseName,
        )
            .addMigrations(MIGRATION_1_2)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
