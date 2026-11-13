package com.peyvand.core.transfer.chat

import com.peyvand.core.domain.chat.ChatMessageId
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageFrameCodecTest {
    private val sessionId = UUID.fromString("a9c0013e-39cc-4867-9f44-255a6c91c430")

    @Test
    fun `message round trips Persian Unicode and metadata`() {
        val original = ChatWireMessage(
            messageId = ChatMessageId("61710ea9-ae9d-4d23-8aab-41c1ad0d1199"),
            senderSequence = 12L,
            observedPeerSequence = 8L,
            createdAtEpochMillis = 1_800_000_000_000L,
            body = "سلام، فایل را دریافت کردم 🌱\nمتشکرم",
        )

        val encoded = ChatMessageFrameCodec.encodeMessage(sessionId, original)
        val restored = ChatMessageFrameCodec.decodeMessage(encoded)

        assertEquals(TransferFrameType.CHAT_MESSAGE, encoded.type)
        assertEquals(TransferFrameCodec.CURRENT_PROTOCOL_VERSION, encoded.protocolVersion)
        assertEquals(original, restored)
    }

    @Test
    fun `acknowledgement round trips message id and sequence`() {
        val original = ChatWireAcknowledgement(
            messageId = ChatMessageId("61710ea9-ae9d-4d23-8aab-41c1ad0d1199"),
            senderSequence = 77L,
        )

        val restored = ChatMessageFrameCodec.decodeAcknowledgement(
            ChatMessageFrameCodec.encodeAcknowledgement(sessionId, original),
        )

        assertEquals(original, restored)
    }

    @Test
    fun `chat frame passes through bounded transport frame codec`() {
        val message = ChatWireMessage(
            ChatMessageId("61710ea9-ae9d-4d23-8aab-41c1ad0d1199"),
            1L,
            0L,
            1_800_000_000_000L,
            "درود",
        )
        val frame = ChatMessageFrameCodec.encodeMessage(sessionId, message)
        val output = java.io.ByteArrayOutputStream()
        TransferFrameCodec.write(frame, output)
        val decoded = TransferFrameCodec.read(java.io.ByteArrayInputStream(output.toByteArray()))

        assertEquals(frame, decoded)
        assertEquals(message, ChatMessageFrameCodec.decodeMessage(decoded!!))
    }

    @Test
    fun `wrong frame type is rejected`() {
        val wrong = TransferFrame(
            TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            TransferFrameType.ACCEPT,
            sessionId,
            byteArrayOf(),
        )
        assertThrows(ChatWireProtocolException::class.java) {
            ChatMessageFrameCodec.decodeMessage(wrong)
        }
    }

    @Test
    fun `acknowledgement with wrong payload length is rejected`() {
        val malformed = TransferFrame(
            TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            TransferFrameType.CHAT_ACK,
            sessionId,
            ByteArray(23),
        )
        assertThrows(ChatWireProtocolException::class.java) {
            ChatMessageFrameCodec.decodeAcknowledgement(malformed)
        }
    }

    @Test
    fun `message decoder rejects inconsistent body length`() {
        val valid = ChatMessageFrameCodec.encodeMessage(
            sessionId,
            ChatWireMessage(
                ChatMessageId("61710ea9-ae9d-4d23-8aab-41c1ad0d1199"),
                1L,
                0L,
                1_800_000_000_000L,
                "hello",
            ),
        )
        val payload = valid.payloadBytes()
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            .putInt(ChatMessageFrameCodec.MESSAGE_METADATA_BYTES - 4, 1000)
        val malformed = TransferFrame(
            TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            TransferFrameType.CHAT_MESSAGE,
            sessionId,
            payload,
        )
        assertThrows(ChatWireProtocolException::class.java) {
            ChatMessageFrameCodec.decodeMessage(malformed)
        }
    }

    @Test
    fun `message body is bounded by UTF8 bytes`() {
        val tooLarge = "آ".repeat(1_400)
        assertThrows(IllegalArgumentException::class.java) {
            ChatMessageFrameCodec.encodeMessage(
                sessionId,
                ChatWireMessage(
                    ChatMessageId("61710ea9-ae9d-4d23-8aab-41c1ad0d1199"),
                    1L,
                    0L,
                    1_800_000_000_000L,
                    tooLarge,
                ),
            )
        }
    }

    @Test
    fun `message id must be canonical UUID`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatMessageFrameCodec.encodeMessage(
                sessionId,
                ChatWireMessage(ChatMessageId("not-an-id"), 1L, 0L, 1L, "x"),
            )
        }
    }
}
