package com.peyvand.core.transfer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchTransferDataPayloadCodecTest {
    private val batchId = UUID.fromString("b88c5d56-e79b-4bd5-b82d-4e7a0ce2f013")
    private val itemId = UUID.fromString("d8e8b9f2-688e-48fb-b8a1-b5d67e017a34")
    private val identity = BatchTransferItemIdentity(batchId, itemId, 3)

    @Test
    fun dataChunkRoundTripsMaximumBoundedPayloadAndExactIdentity() {
        val bytes = ByteArray(BatchTransferFrameCodec.MAX_DATA_CHUNK_BYTES) {
            ((it * 31) xor (it ushr 3)).toByte()
        }
        val chunk = BatchTransferDataChunk(identity, 65_536L, bytes)
        val restored = BatchTransferDataChunkCodec.decode(
            BatchTransferDataChunkCodec.encode(chunk),
        )

        assertEquals(identity, restored.identity)
        assertEquals(65_536L, restored.offsetBytes)
        assertEquals(bytes.size, restored.sizeBytes)
        assertArrayEquals(bytes, restored.bytes())
    }

    @Test
    fun dataChunkRejectsEmptyOversizedNegativeAndOverflowingRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferDataChunk(identity, 0L, byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferDataChunk(
                identity,
                0L,
                ByteArray(BatchTransferFrameCodec.MAX_DATA_CHUNK_BYTES + 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferDataChunk(identity, -1L, byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferDataChunk(identity, Long.MAX_VALUE, byteArrayOf(1))
        }
    }

    @Test
    fun dataChunkDecoderRejectsShortAndLongPayloads() {
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferDataChunkCodec.decode(ByteArray(44))
        }
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferDataChunkCodec.decode(ByteArray(45 + BatchTransferFrameCodec.MAX_DATA_CHUNK_BYTES))
        }
    }

    @Test
    fun cumulativeAckRoundTripsAndRejectsNegativeOffsets() {
        val acknowledgement = BatchTransferChunkAcknowledgement(identity, 262_144L)
        assertEquals(
            acknowledgement,
            BatchTransferChunkAcknowledgementCodec.decode(
                BatchTransferChunkAcknowledgementCodec.encode(acknowledgement),
            ),
        )

        val malformed = BatchTransferChunkAcknowledgementCodec.encode(acknowledgement)
        ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN).putLong(36, -1L)
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferChunkAcknowledgementCodec.decode(malformed)
        }
    }

    @Test
    fun integrityRecordRoundTripsAndBindsBatchItemOrdinalBytesAndDigest() {
        val record = BatchTransferItemIntegrityRecord(identity, 900_123L, "ab".repeat(32))
        val restored = BatchTransferItemIntegrityCodec.decode(
            BatchTransferItemIntegrityCodec.encode(record),
        )
        assertEquals(record, restored)
    }

    @Test
    fun integrityRecordRejectsNegativeSizeInvalidDigestAndWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferItemIntegrityRecord(identity, -1L, "ab".repeat(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferItemIntegrityRecord(identity, 1L, "AB".repeat(32))
        }
        val payload = BatchTransferItemIntegrityCodec.encode(
            BatchTransferItemIntegrityRecord(identity, 1L, "ab".repeat(32)),
        )
        val negative = payload.copyOf()
        ByteBuffer.wrap(negative).order(ByteOrder.BIG_ENDIAN).putLong(36, -1L)
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemIntegrityCodec.decode(negative)
        }
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemIntegrityCodec.decode(payload.copyOf(payload.size - 1))
        }
    }

    @Test
    fun abortRecordRoundTripsAndRejectsUnknownOutcomeReasonAndNegativeBoundary() {
        val record = BatchTransferItemAbortRecord(
            identity = identity,
            finalOffsetBytes = 196_608L,
            outcome = BatchTransferAbortOutcome.FAILED,
            reason = BatchTransferReasonCode.STORAGE_EXHAUSTED,
        )
        val encoded = BatchTransferItemAbortCodec.encode(record)
        assertEquals(record, BatchTransferItemAbortCodec.decode(encoded))

        val unknownOutcome = encoded.copyOf().also { it[44] = 99 }
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemAbortCodec.decode(unknownOutcome)
        }
        val unknownReason = encoded.copyOf().also { it[45] = 99 }
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemAbortCodec.decode(unknownReason)
        }
        val negativeBoundary = encoded.copyOf()
        ByteBuffer.wrap(negativeBoundary).order(ByteOrder.BIG_ENDIAN).putLong(36, -1L)
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemAbortCodec.decode(negativeBoundary)
        }
    }

    @Test
    fun batchAcceptRejectAndCancelRecordsUseTheDeclaredWireLengths() {
        val accepted = BatchTransferBatchAcceptance(batchId)
        val encodedAccept = BatchTransferBatchAcceptanceCodec.encode(accepted)
        assertEquals(16, encodedAccept.size)
        assertEquals(accepted, BatchTransferBatchAcceptanceCodec.decode(encodedAccept))

        val status = BatchTransferBatchStatusRecord(
            batchId,
            BatchTransferReasonCode.USER_CANCELLED,
        )
        val encodedStatus = BatchTransferBatchStatusCodec.encode(status)
        assertEquals(17, encodedStatus.size)
        assertEquals(status, BatchTransferBatchStatusCodec.decode(encodedStatus))
    }

    @Test
    fun batchStatusRejectsUnknownReasonAndWrongLength() {
        val encoded = BatchTransferBatchStatusCodec.encode(
            BatchTransferBatchStatusRecord(batchId, BatchTransferReasonCode.USER_CANCELLED),
        )
        encoded[16] = 99
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferBatchStatusCodec.decode(encoded)
        }
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferBatchStatusCodec.decode(ByteArray(16))
        }
    }

    @Test
    fun batchCompletionSummaryRequiresAllItemsToHaveOneTerminalOutcome() {
        val summary = BatchTransferCompletionSummary(
            batchId = batchId,
            totalItems = 5,
            completedItems = 3,
            rejectedItems = 1,
            cancelledItems = 0,
            failedItems = 1,
        )
        val encoded = BatchTransferCompletionSummaryCodec.encode(summary)
        assertEquals(26, encoded.size)
        assertEquals(summary, BatchTransferCompletionSummaryCodec.decode(encoded))

        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferCompletionSummary(
                batchId = batchId,
                totalItems = 5,
                completedItems = 4,
                rejectedItems = 0,
                cancelledItems = 0,
                failedItems = 0,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferCompletionSummary(
                batchId = batchId,
                totalItems = 5,
                completedItems = 0,
                rejectedItems = 101,
                cancelledItems = 0,
                failedItems = -96,
            )
        }
        val malformed = encoded.copyOf()
        ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN).putShort(24, 0)
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferCompletionSummaryCodec.decode(malformed)
        }
    }
}
