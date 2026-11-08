package com.peyvand.core.transfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class TransferFrameCodecTest {
    private val sessionId = UUID.fromString("b88c5d56-e79b-4bd5-b82d-4e7a0ce2f013")

    @Test
    fun `control frame round trips without changing payload`() {
        val frame = TransferFrame(
            protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            type = TransferFrameType.MANIFEST,
            sessionId = sessionId,
            payload = byteArrayOf(0, 1, 2, -1, 127),
        )
        val output = ByteArrayOutputStream()

        TransferFrameCodec.write(frame, output)

        assertEquals(frame, TransferFrameCodec.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun `reader handles fragmented input reads`() {
        val frame = TransferFrame(
            1, TransferFrameType.ACCEPT, sessionId, byteArrayOf(4, 5, 6, 7),
        )
        val output = ByteArrayOutputStream().also { TransferFrameCodec.write(frame, it) }
        val fragmented = object : ByteArrayInputStream(output.toByteArray()) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                super.read(target, offset, minOf(length, 2))
        }

        assertEquals(frame, TransferFrameCodec.read(fragmented))
    }

    @Test
    fun `clean eof before a frame returns null`() {
        assertNull(TransferFrameCodec.read(ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun `truncated header is rejected`() {
        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.read(ByteArrayInputStream(byteArrayOf(0x50)))
        }
    }

    @Test
    fun `truncated payload is rejected`() {
        val frame = TransferFrame(1, TransferFrameType.MANIFEST, sessionId, byteArrayOf(1, 2, 3))
        val encoded = ByteArrayOutputStream().also { TransferFrameCodec.write(frame, it) }
            .toByteArray()

        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.read(ByteArrayInputStream(encoded.copyOf(encoded.size - 1)))
        }
    }

    @Test
    fun `invalid magic is rejected`() {
        val encoded = encodeEmptyControlFrame().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(0, 0)
        }
        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.read(ByteArrayInputStream(encoded))
        }
    }

    @Test
    fun `unsupported protocol version is rejected`() {
        val encoded = encodeEmptyControlFrame().also { it[4] = 9 }
        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.read(ByteArrayInputStream(encoded))
        }
    }

    @Test
    fun `unknown frame type is rejected`() {
        val encoded = encodeEmptyControlFrame().also { it[5] = 99 }
        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.read(ByteArrayInputStream(encoded))
        }
    }

    @Test
    fun `oversized length is rejected before payload allocation`() {
        val header = ByteBuffer.allocate(TransferFrameCodec.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0x50595644)
            .put(1.toByte())
            .put(TransferFrameType.MANIFEST.wireCode.toByte())
            .putLong(sessionId.mostSignificantBits)
            .putLong(sessionId.leastSignificantBits)
            .putInt(TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES + 1)
            .array()

        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.read(ByteArrayInputStream(header))
        }
    }

    @Test
    fun `writer rejects payload above the control limit`() {
        val frame = TransferFrame(
            1, TransferFrameType.MANIFEST, sessionId,
            ByteArray(TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES + 1),
        )
        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.write(frame, ByteArrayOutputStream())
        }
    }

    @Test
    fun `data chunk payload round trips with exact item and offset`() {
        val itemId = UUID.fromString("d8e8b9f2-688e-48fb-b8a1-b5d67e017a34")
        val original = TransferDataChunk(itemId, 65_536L, byteArrayOf(9, 8, 7, 6))

        val restored = TransferDataChunkCodec.decodePayload(
            TransferDataChunkCodec.encodePayload(original),
        )

        assertEquals(itemId, restored.itemId)
        assertEquals(65_536L, restored.offsetBytes)
        assertTrue(original.bytes().contentEquals(restored.bytes()))
    }

    @Test
    fun `data chunk rejects empty payload and invalid offset`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferDataChunk(UUID.randomUUID(), 0L, byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferDataChunk(UUID.randomUUID(), -1L, byteArrayOf(1))
        }
    }

    @Test
    fun batchFramesUseTheCurrentBatchProtocolVersion() {
        val batchId = UUID.randomUUID()
        val payload = ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(batchId.mostSignificantBits)
            .putLong(batchId.leastSignificantBits)
            .array()
        val frame = TransferFrame(
            TransferFrameCodec.BATCH_PROTOCOL_VERSION,
            TransferFrameType.BATCH_OFFER,
            sessionId,
            payload,
        )
        val output = ByteArrayOutputStream()
        TransferFrameCodec.write(frame, output)

        val decoded = TransferFrameCodec.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(3, TransferFrameCodec.BATCH_PROTOCOL_VERSION)
        assertEquals(frame, decoded)
    }

    @Test
    fun batchAndSingleFileFramesCannotCrossProtocolVersions() {
        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.write(
                TransferFrame(
                    TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                    TransferFrameType.BATCH_OFFER,
                    sessionId,
                    ByteArray(16),
                ),
                ByteArrayOutputStream(),
            )
        }
        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.write(
                TransferFrame(
                    TransferFrameCodec.BATCH_PROTOCOL_VERSION,
                    TransferFrameType.ACCEPT,
                    sessionId,
                    byteArrayOf(),
                ),
                ByteArrayOutputStream(),
            )
        }
    }

    private fun encodeEmptyControlFrame(): ByteArray = ByteArrayOutputStream().also {
        TransferFrameCodec.write(
            TransferFrame(1, TransferFrameType.ACCEPT, sessionId, byteArrayOf()),
            it,
        )
    }.toByteArray()
}