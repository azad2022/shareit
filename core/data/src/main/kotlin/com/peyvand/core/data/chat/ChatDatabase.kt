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
        LocalDeviceProfileEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversations(): ChatConversationDao
    abstract fun messages(): ChatMessageDao
    abstract fun outbox(): ChatOutboxDao
    abstract fun cryptoState(): ChatCryptoStateDao
    abstract fun trustedPeers(): ChatTrustedPeerDao
    abstract fun localDeviceProfile(): LocalDeviceProfileDao
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

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE chat_trusted_peers ADD COLUMN profile_name TEXT")
            database.execSQL("ALTER TABLE chat_trusted_peers ADD COLUMN profile_avatar_hash TEXT")
            database.execSQL("ALTER TABLE chat_trusted_peers ADD COLUMN profile_avatar_bytes BLOB")
            database.execSQL(
                "ALTER TABLE chat_trusted_peers ADD COLUMN profile_revision INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS local_device_profile (" +
                    "id INTEGER NOT NULL, " +
                    "device_name_override TEXT, " +
                    "profile_name TEXT, " +
                    "avatar_bytes BLOB, " +
                    "profile_revision INTEGER NOT NULL, " +
                    "share_with_trusted_peers INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))",
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
