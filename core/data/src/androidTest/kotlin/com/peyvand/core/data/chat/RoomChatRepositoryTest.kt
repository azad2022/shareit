package com.peyvand.core.data.chat

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.peyvand.core.domain.chat.ChatMessageDeliveryState
import com.peyvand.core.domain.chat.ChatMessageRules
import com.peyvand.core.domain.chat.ChatOutboxLimitExceededException
import com.peyvand.core.domain.chat.ChatRepository
import com.peyvand.core.domain.chat.PeerIdentityId
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomChatRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "chat-test-" + UUID.randomUUID() + ".db"
    private val keyAlias = "peyvand.chat.test." + UUID.randomUUID()
    private val peerIdentity = PeerIdentityId.fromSha256Fingerprint("a".repeat(64))

    private lateinit var database: ChatDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        openRepository()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
        deleteKeystoreAlias()
    }

    @Test
    fun outgoingMessageAndOutboxSurviveDatabaseReopen() = runBlocking {
        val first = repository.enqueueOutgoingMessage(
            peerIdentityId = peerIdentity,
            body = "سلام، این پیام باید روی همین گوشی باقی بماند.",
            createdAtEpochMillis = 1_800_000_000_000L,
        )

        assertEquals(1, repository.loadPendingOutgoingMessages(peerIdentity).size)
        database.close()
        openRepository()

        val pending = repository.loadPendingOutgoingMessages(peerIdentity)

        assertEquals(1, pending.size)
        assertEquals(first.id, pending.single().id)
        assertEquals(first.body, pending.single().body)
        assertEquals(1L, pending.single().senderSequence)
        assertEquals(1, repository.observePendingOutboxCount(peerIdentity).first())
    }

    @Test
    fun outgoingSequencesAreMonotonicAcrossMessagesAndReopen() = runBlocking {
        val first = repository.enqueueOutgoingMessage(peerIdentity, "one", 100L)
        val second = repository.enqueueOutgoingMessage(peerIdentity, "two", 101L)
        database.close()
        openRepository()
        val third = repository.enqueueOutgoingMessage(peerIdentity, "three", 102L)

        assertEquals(1L, first.senderSequence)
        assertEquals(2L, second.senderSequence)
        assertEquals(3L, third.senderSequence)
    }

    @Test
    fun authenticatedDeliveryReceiptRemovesOutboxEntryAndKeepsLocalHistory() = runBlocking {
        val message = repository.enqueueOutgoingMessage(peerIdentity, "receipt", 100L)

        assertTrue(
            repository.confirmDeliveredAfterAuthenticatedReceipt(peerIdentity, message.id),
        )
        assertTrue(repository.loadPendingOutgoingMessages(peerIdentity).isEmpty())
        val history = repository.observeRecentMessages(peerIdentity).first()
        assertEquals(1, history.size)
        assertEquals(ChatMessageDeliveryState.DELIVERED, history.single().deliveryState)
        assertTrue(
            repository.confirmDeliveredAfterAuthenticatedReceipt(peerIdentity, message.id),
        )
    }

    @Test
    fun aDifferentPeerCannotAcknowledgeOrReadTheMessage() = runBlocking {
        val message = repository.enqueueOutgoingMessage(peerIdentity, "private", 100L)
        val otherPeer = PeerIdentityId.fromSha256Fingerprint("b".repeat(64))

        assertFalse(
            repository.confirmDeliveredAfterAuthenticatedReceipt(otherPeer, message.id),
        )
        assertEquals(1, repository.loadPendingOutgoingMessages(peerIdentity).size)
        assertTrue(repository.observeRecentMessages(otherPeer).first().isEmpty())
    }

    @Test
    fun messageBodyIsNotStoredAsPlaintextInRoomPayloadColumn() = runBlocking {
        val body = "PEYVAND-PRIVATE-MESSAGE-" + UUID.randomUUID()
        repository.enqueueOutgoingMessage(peerIdentity, body, 100L)

        val cursor = database.openHelper.readableDatabase.query(
            "SELECT payload_ciphertext FROM chat_messages LIMIT 1",
        )
        try {
            assertTrue(cursor.moveToFirst())
            val encrypted = String(cursor.getBlob(0), Charsets.ISO_8859_1)
            assertFalse(encrypted.contains(body))
        } finally {
            cursor.close()
        }
    }

    @Test
    fun missingKeystoreKeyDoesNotSilentlyGenerateAReplacement() = runBlocking {
        repository.enqueueOutgoingMessage(peerIdentity, "key loss check", 100L)
        database.close()
        deleteKeystoreAlias()
        openRepository()

        var failedClosed = false
        try {
            repository.loadPendingOutgoingMessages(peerIdentity)
        } catch (_: ChatEncryptionKeyUnavailableException) {
            failedClosed = true
        }

        assertTrue(failedClosed)
        assertNotNull(database.cryptoState().getState())
    }

    @Test
    fun oversizedMessageIsRejectedBeforeAnyDatabaseWrite() = runBlocking {
        val oversized = "پ".repeat((ChatMessageRules.MAX_MESSAGE_UTF8_BYTES / 2) + 1)
        try {
            repository.enqueueOutgoingMessage(peerIdentity, oversized, 100L)
            throw AssertionError("Oversized message unexpectedly persisted.")
        } catch (exception: com.peyvand.core.domain.chat.InvalidChatMessageException) {
            assertEquals(
                com.peyvand.core.domain.chat.ChatMessageValidationError.TOO_LARGE,
                exception.reason,
            )
        }

        assertTrue(repository.loadPendingOutgoingMessages(peerIdentity).isEmpty())
    }

    @Test
    fun pendingOutboxLimitPreservesExistingMessagesForThatPeer() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        val maximumPending = ChatMessageRules.MAX_PENDING_OUTGOING_MESSAGES_PER_PEER

        database.withTransaction {
            database.conversations().insertIfAbsent(
                ChatConversationEntity(
                    id = conversationId,
                    peerIdentityFingerprint = peerIdentity.sha256Fingerprint,
                    createdAtMillis = 100L,
                    lastActivityAtMillis = 100L,
                    nextLocalSequence = maximumPending.toLong() + 1L,
                    lastPeerSequence = 0L,
                ),
            )

            repeat(maximumPending) { index ->
                val messageId = "seeded-outbox-$index"
                database.messages().insert(
                    ChatMessageEntity(
                        id = messageId,
                        conversationId = conversationId,
                        direction = 0,
                        senderSequence = index.toLong() + 1L,
                        observedPeerSequence = 0L,
                        createdAtMillis = 100L + index,
                        payloadNonce = ByteArray(12),
                        payloadCiphertext = ByteArray(16),
                        deliveryState = "WAITING_FOR_CONNECTION",
                    ),
                )
                database.outbox().insert(
                    ChatOutboxEntity(
                        messageId = messageId,
                        queuedAtMillis = 100L + index,
                    ),
                )
            }
        }

        var rejectedByQuota = false
        try {
            repository.enqueueOutgoingMessage(
                peerIdentityId = peerIdentity,
                body = "This message must not replace queued data.",
                createdAtEpochMillis = 10_000L,
            )
        } catch (_: ChatOutboxLimitExceededException) {
            rejectedByQuota = true
        }

        assertTrue(rejectedByQuota)
        assertEquals(maximumPending, database.outbox().countPending(conversationId))
    }

    @Test
    fun corruptCiphertextFailsAuthenticationInsteadOfReturningGarbage() = runBlocking {
        repository.enqueueOutgoingMessage(peerIdentity, "integrity check", 100L)

        val cursor = database.openHelper.readableDatabase.query(
            "SELECT id, payload_ciphertext FROM chat_messages LIMIT 1",
        )
        val messageId: String
        val ciphertext: ByteArray
        try {
            assertTrue(cursor.moveToFirst())
            messageId = cursor.getString(0)
            ciphertext = cursor.getBlob(1)
        } finally {
            cursor.close()
        }
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 0x01).toByte()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE chat_messages SET payload_ciphertext = ? WHERE id = ?",
            arrayOf(ciphertext, messageId),
        )

        var failedAuthentication = false
        try {
            repository.observeRecentMessages(peerIdentity).first()
        } catch (_: ChatPayloadIntegrityException) {
            failedAuthentication = true
        }

        assertTrue(failedAuthentication)
    }

    private fun openRepository() {
        database = ChatDatabaseFactory.open(context, databaseName)
        repository = RoomChatRepository(
            database = database,
            cipher = AndroidKeystoreChatPayloadCipher(keyAlias),
        )
    }

    private fun deleteKeystoreAlias() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }
}
