package com.peyvand.core.transfer.session

import com.peyvand.core.domain.connection.AuthenticatedPeerChannelPurpose
import com.peyvand.core.transfer.chat.ChatMessageFrameCodec
import com.peyvand.core.transfer.chat.ChatWireMessage
import com.peyvand.core.domain.chat.ChatMessageId
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedPeerChannelMultiplexerTest {
    private val sessionId = UUID.fromString("0d708be7-26ac-4c1c-9d9b-c3d6e4d42a89")

    @Test
    fun `chat and file frames are routed to their independent channels`() {
        val pair = createPair()
        val chatA = pair.first.openChannel(AuthenticatedPeerChannelPurpose.CHAT)!!
        val fileA = pair.first.openChannel(AuthenticatedPeerChannelPurpose.FILE_TRANSFER)!!
        val chatB = pair.second.openChannel(AuthenticatedPeerChannelPurpose.CHAT)!!
        val fileB = pair.second.openChannel(AuthenticatedPeerChannelPurpose.FILE_TRANSFER)!!
        val message = ChatWireMessage(
            messageId = ChatMessageId("f9673ff9-31af-410e-961b-a252f8c478c5"),
            senderSequence = 2L,
            observedPeerSequence = 1L,
            createdAtEpochMillis = 1_800_000_000_000L,
            body = "سلام، این یک پیام چت است 🌱",
        )

        TransferFrameCodec.write(
            ChatMessageFrameCodec.encodeMessage(sessionId, message),
            chatA.outputStream,
        )
        chatA.outputStream.flush()
        val receivedChatFrame = TransferFrameCodec.read(chatB.inputStream)
        assertNotNull(receivedChatFrame)
        assertEquals(TransferFrameType.CHAT_MESSAGE, receivedChatFrame!!.type)
        assertEquals(message, ChatMessageFrameCodec.decodeMessage(receivedChatFrame))

        val manifest = TransferFrame(
            TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            TransferFrameType.MANIFEST,
            sessionId,
            byteArrayOf(7, 6, 5, 4),
        )
        TransferFrameCodec.write(manifest, fileA.outputStream)
        fileA.outputStream.flush()
        val receivedFileFrame = TransferFrameCodec.read(fileB.inputStream)
        assertEquals(manifest, receivedFileFrame)

        chatA.close()
        fileA.close()
        chatB.close()
        fileB.close()
        pair.first.close()
        pair.second.close()
    }

    @Test
    fun `closing file channel leaves chat channel operational`() {
        val pair = createPair()
        val chatA = pair.first.openChannel(AuthenticatedPeerChannelPurpose.CHAT)!!
        val fileA = pair.first.openChannel(AuthenticatedPeerChannelPurpose.FILE_TRANSFER)!!
        val chatB = pair.second.openChannel(AuthenticatedPeerChannelPurpose.CHAT)!!
        val fileB = pair.second.openChannel(AuthenticatedPeerChannelPurpose.FILE_TRANSFER)!!

        fileA.close()
        fileB.close()

        val message = ChatWireMessage(
            ChatMessageId("fa0c88d5-0584-4e68-937a-91d5316882de"),
            1L,
            0L,
            1_800_000_000_001L,
            "مکالمه باید بعد از پایان انتقال ادامه پیدا کند.",
        )
        TransferFrameCodec.write(
            ChatMessageFrameCodec.encodeMessage(sessionId, message),
            chatA.outputStream,
        )
        chatA.outputStream.flush()

        val received = TransferFrameCodec.read(chatB.inputStream)
        assertNotNull(received)
        assertEquals(message, ChatMessageFrameCodec.decodeMessage(received!!))

        chatA.close()
        chatB.close()
        pair.first.close()
        pair.second.close()
    }

    @Test
    fun `only one reader may claim each logical channel on a device`() {
        val pair = createPair()
        val first = pair.first.openChannel(AuthenticatedPeerChannelPurpose.CHAT)
        val duplicate = pair.first.openChannel(AuthenticatedPeerChannelPurpose.CHAT)

        assertNotNull(first)
        assertNull(duplicate)

        first?.close()
        pair.first.close()
        pair.second.close()
    }

    @Test
    fun `peer frame for unopened channel fails the authenticated multiplexer`() {
        val failure = CountDownLatch(1)
        val failureCause = AtomicReference<Throwable?>()
        val pair = createPair(
            onSecondFailure = {
                failureCause.set(it)
                failure.countDown()
            },
        )
        val fileA = pair.first.openChannel(AuthenticatedPeerChannelPurpose.FILE_TRANSFER)!!
        pair.second.openChannel(AuthenticatedPeerChannelPurpose.CHAT)

        TransferFrameCodec.write(
            TransferFrame(
                TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                TransferFrameType.MANIFEST,
                sessionId,
                byteArrayOf(1),
            ),
            fileA.outputStream,
        )
        fileA.outputStream.flush()

        assertTrue("The receiver should reject an unclaimed channel frame.", failure.await(2, TimeUnit.SECONDS))
        assertNotNull(failureCause.get())

        fileA.close()
        pair.first.close()
        pair.second.close()
    }

    private data class PairOfMultiplexers(
        val first: AuthenticatedPeerChannelMultiplexer,
        val second: AuthenticatedPeerChannelMultiplexer,
    )

    private fun createPair(
        onFirstFailure: (Throwable) -> Unit = {},
        onSecondFailure: (Throwable) -> Unit = {},
    ): PairOfMultiplexers {
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
            onFailure = onFirstFailure,
        )
        val second = AuthenticatedPeerChannelMultiplexer(
            sessionId = sessionId,
            socketInput = secondInput,
            socketOutput = secondOutput,
            closeUnderlying = {
                runCatching { secondInput.close() }
                runCatching { secondOutput.close() }
            },
            onFailure = onSecondFailure,
        )
        return PairOfMultiplexers(first, second)
    }
}
