package com.peyvand.core.transfer.protocol

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTransferFramePayloadsTest {
    @Test
    fun roundTripsBatchDataAndAcknowledgementWithIndependentIdentifiers() {
        val batchId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val chunk = BatchTransferDataChunk(
            batchId = batchId,
            itemId = itemId,
            offsetBytes = 65_536L,
            bytes = ByteArray(4096) { (it % 251).toByte() },
        )
        val decoded = BatchTransferDataChunkCodec.decode(
            BatchTransferDataChunkCodec.encode(chunk),
        )
        assertEquals(batchId, decoded.batchId)
        assertEquals(itemId, decoded.itemId)
        assertEquals(chunk.offsetBytes, decoded.offsetBytes)
        assertTrue(chunk.bytes().contentEquals(decoded.bytes()))

        val acknowledgement = BatchChunkAcknowledgement(batchId, itemId, 69_632L)
        assertEquals(
            acknowledgement,
            BatchChunkAcknowledgementCodec.decode(
                BatchChunkAcknowledgementCodec.encode(acknowledgement),
            ),
        )
    }

    @Test
    fun completionAndFailureRecordsAreItemAndBatchBound() {
        val batchId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val completion = BatchItemCompletionRecord(
            batchId,
            itemId,
            0L,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        assertEquals(
            completion,
            BatchItemCompletionCodec.decode(BatchItemCompletionCodec.encode(completion)),
        )

        val failure = BatchItemFailureRecord(
            batchId,
            itemId,
            BatchItemFailureCode.STORAGE_UNAVAILABLE,
        )
        assertEquals(
            failure,
            BatchItemFailureCodec.decode(BatchItemFailureCodec.encode(failure)),
        )
    }

    @Test
    fun outcomeDigestIsOrderSensitiveAndTerminalRecordRoundTrips() {
        val batchId = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val outcomes = listOf(
            BatchItemOutcome(first, BatchItemOutcomeStatus.COMPLETED, 2L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            BatchItemOutcome(second, BatchItemOutcomeStatus.FAILED),
        )
        val digest = BatchOutcomeDigest.compute(batchId, listOf(first, second), outcomes)
        assertTrue(digest != BatchOutcomeDigest.compute(batchId, listOf(second, first), outcomes.reversed()))
        val record = BatchTerminalRecord(batchId, 2, digest)
        assertEquals(record, BatchTerminalRecordCodec.decode(BatchTerminalRecordCodec.encode(record)))
    }
}
