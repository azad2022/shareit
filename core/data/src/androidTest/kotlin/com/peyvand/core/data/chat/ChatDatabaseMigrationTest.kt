package com.peyvand.core.data.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.peyvand.core.data.profile.RoomDeviceProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "chat-migration-v2-" + UUID.randomUUID() + ".db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromVersionTwoToThreePreservesChatAndTrustedPeerData() {
        createVersionTwoDatabase()

        val migrated = ChatDatabaseFactory.open(context, databaseName)
        try {
            runBlocking {
                val peer = migrated.trustedPeers().findByFingerprint(PEER_FINGERPRINT)
                assertNotNull(peer)
                requireNotNull(peer)
                assertArrayEquals(byteArrayOf(1, 2, 3), peer.publicKeyEncoded)
                assertEquals("Galaxy A12", peer.displayName)
                assertEquals(10L, peer.firstSeenAtMillis)
                assertEquals(20L, peer.lastAuthenticatedAtMillis)
                assertNull(peer.profileName)
                assertNull(peer.profileAvatarHash)
                assertNull(peer.profileAvatarBytes)
                assertEquals(0L, peer.profileRevision)

                val conversations = migrated.conversations().observeAll().first()
                assertEquals(1, conversations.size)
                assertEquals(CONVERSATION_ID, conversations.single().id)
                assertEquals(PEER_FINGERPRINT, conversations.single().peerIdentityFingerprint)
                assertEquals(1, migrated.messages().countAll())
                assertEquals(1, migrated.outbox().countPending(CONVERSATION_ID))

                val cryptoState = migrated.cryptoState().getState()
                assertNotNull(cryptoState)
                assertEquals(1, requireNotNull(cryptoState).keyVersion)
                assertEquals(10L, requireNotNull(cryptoState).initializedAtMillis)

                val profile = RoomDeviceProfileRepository(context, migrated).getLocalProfile()
                assertNull(profile.deviceNameOverride)
                assertNull(profile.profileName)
                assertNull(profile.avatarBytes)
                assertEquals(0L, profile.revision)
                assertTrue(profile.shareWithTrustedPeers)
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * Builds the version-2 schema as it existed before CP-PROFILE-001. Opening it
     * through ChatDatabaseFactory must execute the real 2->3 Room migration and
     * validate the resulting version-3 schema without destructive fallback.
     */
    private fun createVersionTwoDatabase() {
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
                "CREATE TABLE chat_conversations (" +
                    "id TEXT NOT NULL, " +
                    "peer_identity_fingerprint TEXT NOT NULL, " +
                    "created_at_ms INTEGER NOT NULL, " +
                    "last_activity_at_ms INTEGER NOT NULL, " +
                    "next_local_sequence INTEGER NOT NULL, " +
                    "last_peer_sequence INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))",
            )
            database.execSQL(
                "CREATE UNIQUE INDEX index_chat_conversations_peer_identity_fingerprint " +
                    "ON chat_conversations(peer_identity_fingerprint)",
            )
            database.execSQL(
                "CREATE TABLE chat_messages (" +
                    "id TEXT NOT NULL, " +
                    "conversation_id TEXT NOT NULL, " +
                    "direction INTEGER NOT NULL, " +
                    "sender_sequence INTEGER NOT NULL, " +
                    "observed_peer_sequence INTEGER NOT NULL, " +
                    "created_at_ms INTEGER NOT NULL, " +
                    "payload_nonce BLOB NOT NULL, " +
                    "payload_ciphertext BLOB NOT NULL, " +
                    "delivery_state TEXT NOT NULL, " +
                    "PRIMARY KEY(id), " +
                    "FOREIGN KEY(conversation_id) REFERENCES chat_conversations(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            database.execSQL(
                "CREATE UNIQUE INDEX index_chat_messages_conversation_id_direction_sender_sequence " +
                    "ON chat_messages(conversation_id, direction, sender_sequence)",
            )
            database.execSQL(
                "CREATE INDEX index_chat_messages_conversation_id_created_at_ms_sender_sequence " +
                    "ON chat_messages(conversation_id, created_at_ms, sender_sequence)",
            )
            database.execSQL(
                "CREATE TABLE chat_outbox (" +
                    "message_id TEXT NOT NULL, " +
                    "queued_at_ms INTEGER NOT NULL, " +
                    "PRIMARY KEY(message_id), " +
                    "FOREIGN KEY(message_id) REFERENCES chat_messages(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            database.execSQL(
                "CREATE INDEX index_chat_outbox_queued_at_ms_message_id " +
                    "ON chat_outbox(queued_at_ms, message_id)",
            )
            database.execSQL(
                "CREATE TABLE chat_crypto_state (" +
                    "id INTEGER NOT NULL, " +
                    "key_version INTEGER NOT NULL, " +
                    "initialized_at_ms INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))",
            )
            database.execSQL(
                "CREATE TABLE chat_trusted_peers (" +
                    "identity_fingerprint TEXT NOT NULL, " +
                    "public_key_encoded BLOB NOT NULL, " +
                    "display_name TEXT NOT NULL, " +
                    "first_seen_at_ms INTEGER NOT NULL, " +
                    "last_authenticated_at_ms INTEGER NOT NULL, " +
                    "PRIMARY KEY(identity_fingerprint))",
            )
            database.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            database.execSQL(
                "INSERT INTO room_master_table(id, identity_hash) VALUES(42, 'legacy-v2-test-hash')",
            )

            database.execSQL(
                "INSERT INTO chat_trusted_peers VALUES(?, ?, ?, ?, ?)",
                arrayOf(PEER_FINGERPRINT, byteArrayOf(1, 2, 3), "Galaxy A12", 10L, 20L),
            )
            database.execSQL(
                "INSERT INTO chat_conversations VALUES(?, ?, ?, ?, ?, ?)",
                arrayOf(CONVERSATION_ID, PEER_FINGERPRINT, 10L, 20L, 2L, 0L),
            )
            database.execSQL(
                "INSERT INTO chat_messages VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "message-v2",
                    CONVERSATION_ID,
                    0,
                    1L,
                    0L,
                    10L,
                    ByteArray(12) { it.toByte() },
                    ByteArray(16) { it.toByte() },
                    "WAITING_FOR_CONNECTION",
                ),
            )
            database.execSQL(
                "INSERT INTO chat_outbox VALUES(?, ?)",
                arrayOf("message-v2", 10L),
            )
            database.execSQL(
                "INSERT INTO chat_crypto_state VALUES(?, ?, ?)",
                arrayOf(1, 1, 10L),
            )
            database.version = 2
        } finally {
            database.close()
        }
    }

    private companion object {
        const val PEER_FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CONVERSATION_ID = "conversation-v2"
    }
}
