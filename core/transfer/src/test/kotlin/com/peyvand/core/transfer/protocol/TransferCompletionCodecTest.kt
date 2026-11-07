package com.peyvand.core.transfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class TransferCompletionCodecTest {
    @Test
    fun `completion record round trips item id actual bytes and digest`() {
        val record = TransferCompletionRecord(
            UUID.fromString("3ba3e2d7-7526-4f4b-98bb-cc8a6444fa4e"),
            98_765L,
            "cd".repeat(32),
        )

        assertEquals(record, TransferCompletionCodec.decode(TransferCompletionCodec.encode(record)))
    }

    @Test
    fun `completion rejects negative size and malformed digest`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferCompletionCodec.encode(TransferCompletionRecord(UUID.randomUUID(), -1L, "cd".repeat(32)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferCompletionCodec.encode(TransferCompletionRecord(UUID.randomUUID(), 0L, "bad"))
        }
    }

    @Test
    fun `completion decoder rejects wrong length and negative size`() {
        val record = TransferCompletionRecord(UUID.randomUUID(), 0L, "ef".repeat(32))
        val encoded = TransferCompletionCodec.encode(record)
        assertThrows(TransferProtocolException::class.java) {
            TransferCompletionCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        val negative = encoded.copyOf()
        java.nio.ByteBuffer.wrap(negative).putLong(16, -1L)
        assertThrows(TransferProtocolException::class.java) {
            TransferCompletionCodec.decode(negative)
        }
    }
}