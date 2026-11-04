package com.peyvand.core.transfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class TransferChunkFlowControlTest {
    @Test
    fun `chunks advance a contiguous offset and return cumulative acknowledgement`() {
        val itemId = UUID.randomUUID()
        val validator = TransferChunkSequenceValidator(itemId, maximumSizeBytes = 10L)

        val first = validator.accept(TransferDataChunk(itemId, 0L, byteArrayOf(1, 2, 3)))
        val second = validator.accept(TransferDataChunk(itemId, 3L, byteArrayOf(4, 5)))

        assertEquals(3L, first.nextExpectedOffset)
        assertEquals(5L, second.nextExpectedOffset)
        assertEquals(5L, validator.nextExpectedOffset)
    }

    @Test
    fun `duplicate skipped reordered and foreign item chunks are rejected`() {
        val itemId = UUID.randomUUID()
        val validator = TransferChunkSequenceValidator(itemId, maximumSizeBytes = 10L)
        validator.accept(TransferDataChunk(itemId, 0L, byteArrayOf(1, 2)))

        assertThrows(TransferProtocolException::class.java) {
            validator.accept(TransferDataChunk(itemId, 0L, byteArrayOf(1)))
        }
        assertThrows(TransferProtocolException::class.java) {
            validator.accept(TransferDataChunk(itemId, 4L, byteArrayOf(3)))
        }
        assertThrows(TransferProtocolException::class.java) {
            validator.accept(TransferDataChunk(UUID.randomUUID(), 2L, byteArrayOf(3)))
        }
    }

    @Test
    fun `chunks cannot exceed configured maximum size`() {
        val itemId = UUID.randomUUID()
        val validator = TransferChunkSequenceValidator(itemId, maximumSizeBytes = 3L)
        assertThrows(TransferProtocolException::class.java) {
            validator.accept(TransferDataChunk(itemId, 0L, byteArrayOf(1, 2, 3, 4)))
        }
        assertEquals(0L, validator.nextExpectedOffset)
    }

    @Test
    fun `chunk acknowledgement codec validates item id and offset`() {
        val expected = TransferChunkAcknowledgement(UUID.randomUUID(), 65_536L)
        val restored = TransferChunkAcknowledgementCodec.decode(
            TransferChunkAcknowledgementCodec.encode(expected),
        )
        assertEquals(expected, restored)
        assertEquals(256 * 1024, TransferFlowControlPolicy.MAX_IN_FLIGHT_BYTES)
        assertEquals(4, TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS)
    }

    @Test
    fun `chunk acknowledgement rejects negative offset and malformed length`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferChunkAcknowledgement(UUID.randomUUID(), -1L)
        }
        assertThrows(TransferProtocolException::class.java) {
            TransferChunkAcknowledgementCodec.decode(ByteArray(23))
        }
    }
}