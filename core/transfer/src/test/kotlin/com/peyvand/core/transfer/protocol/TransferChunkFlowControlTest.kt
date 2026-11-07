package com.peyvand.core.transfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class TransferChunkFlowControlTest {
    @Test
    fun `receiver validates before write and commits offset only after staging succeeds`() {
        val itemId = UUID.randomUUID()
        val validator = TransferChunkSequenceValidator(itemId, maximumSizeBytes = 10L)
        val first = TransferDataChunk(itemId, 0L, byteArrayOf(1, 2, 3))
        val second = TransferDataChunk(itemId, 3L, byteArrayOf(4, 5))

        validator.validate(first)
        assertEquals(0L, validator.nextExpectedOffset)

        // Model successful staging write followed by the commit/ACK point.
        val firstAck = validator.commit(first)
        assertEquals(3L, firstAck.nextExpectedOffset)
        assertEquals(3L, validator.nextExpectedOffset)

        validator.validate(second)
        assertEquals(3L, validator.nextExpectedOffset)
        val secondAck = validator.commit(second)
        assertEquals(5L, secondAck.nextExpectedOffset)
        assertEquals(5L, validator.nextExpectedOffset)
    }

    @Test
    fun `duplicate skipped reordered and foreign item chunks are rejected`() {
        val itemId = UUID.randomUUID()
        val validator = TransferChunkSequenceValidator(itemId, maximumSizeBytes = 10L)
        val first = TransferDataChunk(itemId, 0L, byteArrayOf(1, 2))
        validator.commit(first)

        assertThrows(TransferProtocolException::class.java) {
            validator.validate(TransferDataChunk(itemId, 0L, byteArrayOf(1)))
        }
        assertThrows(TransferProtocolException::class.java) {
            validator.validate(TransferDataChunk(itemId, 4L, byteArrayOf(3)))
        }
        assertThrows(TransferProtocolException::class.java) {
            validator.validate(TransferDataChunk(UUID.randomUUID(), 2L, byteArrayOf(3)))
        }
        assertThrows(TransferProtocolException::class.java) {
            validator.commit(TransferDataChunk(itemId, 0L, byteArrayOf(1)))
        }
        assertEquals(2L, validator.nextExpectedOffset)
    }

    @Test
    fun `receiver rejects a chunk beyond configured maximum size without advancing`() {
        val itemId = UUID.randomUUID()
        val validator = TransferChunkSequenceValidator(itemId, maximumSizeBytes = 3L)

        assertThrows(TransferProtocolException::class.java) {
            validator.validate(TransferDataChunk(itemId, 0L, byteArrayOf(1, 2, 3, 4)))
        }
        assertEquals(0L, validator.nextExpectedOffset)
    }

    @Test
    fun `sender window caps both outstanding chunks and bytes and reopens on cumulative ack`() {
        val itemId = UUID.randomUUID()
        val chunkBytes = ByteArray(TransferFrameCodec.MAX_DATA_CHUNK_BYTES)
        val window = TransferSendWindow(itemId, maximumSizeBytes = 1_000_000L)

        repeat(4) { index ->
            val chunk = TransferDataChunk(
                itemId = itemId,
                offsetBytes = index.toLong() * chunkBytes.size,
                bytes = chunkBytes,
            )
            assertTrue(window.tryReserve(chunk))
        }

        assertEquals(4, window.inFlightChunkCount)
        assertEquals(256 * 1024, window.inFlightBytes)
        assertEquals(0, window.availableInFlightBytes)
        assertFalse(window.hasCapacity)
        assertFalse(
            window.tryReserve(
                TransferDataChunk(itemId, 4L * chunkBytes.size, byteArrayOf(9)),
            ),
        )
        assertEquals(4L * chunkBytes.size, window.nextSendOffsetBytes)

        assertEquals(
            2,
            window.acknowledge(
                TransferChunkAcknowledgement(itemId, 2L * chunkBytes.size),
            ),
        )
        assertEquals(2, window.inFlightChunkCount)
        assertEquals(128 * 1024, window.inFlightBytes)
        assertTrue(window.hasCapacity)

        assertTrue(
            window.tryReserve(
                TransferDataChunk(itemId, 4L * chunkBytes.size, chunkBytes),
            ),
        )
        assertEquals(3, window.inFlightChunkCount)
        assertEquals(3 * chunkBytes.size, window.inFlightBytes)
    }

    @Test
    fun `sender rejects noncontiguous reservations and invalid cumulative acknowledgements`() {
        val itemId = UUID.randomUUID()
        val window = TransferSendWindow(itemId, maximumSizeBytes = 1_000L)
        val first = TransferDataChunk(itemId, 0L, byteArrayOf(1, 2, 3))
        assertTrue(window.tryReserve(first))

        assertThrows(TransferProtocolException::class.java) {
            window.tryReserve(TransferDataChunk(itemId, 4L, byteArrayOf(4)))
        }
        assertThrows(TransferProtocolException::class.java) {
            window.acknowledge(TransferChunkAcknowledgement(UUID.randomUUID(), 3L))
        }
        assertThrows(TransferProtocolException::class.java) {
            window.acknowledge(TransferChunkAcknowledgement(itemId, 2L))
        }
        assertThrows(TransferProtocolException::class.java) {
            window.acknowledge(TransferChunkAcknowledgement(itemId, 4L))
        }
        assertEquals(3L, window.nextSendOffsetBytes)
        assertEquals(0L, window.acknowledgedOffsetBytes)
        assertEquals(1, window.inFlightChunkCount)
    }

    @Test
    fun `duplicate or stale cumulative acknowledgement does not release capacity twice`() {
        val itemId = UUID.randomUUID()
        val window = TransferSendWindow(itemId, maximumSizeBytes = 100L)
        assertTrue(window.tryReserve(TransferDataChunk(itemId, 0L, byteArrayOf(1, 2))))
        assertTrue(window.tryReserve(TransferDataChunk(itemId, 2L, byteArrayOf(3, 4))))

        val ack = TransferChunkAcknowledgement(itemId, 2L)
        assertEquals(1, window.acknowledge(ack))
        assertEquals(0, window.acknowledge(ack))
        assertEquals(0, window.acknowledge(TransferChunkAcknowledgement(itemId, 1L)))
        assertEquals(1, window.inFlightChunkCount)
        assertEquals(2, window.inFlightBytes)
        assertEquals(2L, window.acknowledgedOffsetBytes)
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

    @Test
    fun `window refuses unsupported limits`() {
        val itemId = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) {
            TransferSendWindow(itemId, 100L, maximumInFlightChunks = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferSendWindow(itemId, 100L, maximumInFlightBytes = 256 * 1024 + 1)
        }
    }
}
