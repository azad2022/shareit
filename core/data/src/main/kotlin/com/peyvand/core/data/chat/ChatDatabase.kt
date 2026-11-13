package com.peyvand.core.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatConversationEntity::class,
        ChatMessageEntity::class,
        ChatOutboxEntity::class,
        ChatCryptoStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversations(): ChatConversationDao
    abstract fun messages(): ChatMessageDao
    abstract fun outbox(): ChatOutboxDao
    abstract fun cryptoState(): ChatCryptoStateDao
}

object ChatDatabaseFactory {
    const val DATABASE_NAME = "peyvand_local_chat.db"

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
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
