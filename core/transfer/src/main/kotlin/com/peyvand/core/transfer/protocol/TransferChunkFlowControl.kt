package com.peyvand.core.transfer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class TransferChunkAcknowledgement(
    val itemId: UUID,
    /** Next contiguous byte offset durably accepted by the receiver's staging stream. */
    val nextExpectedOffset: Long,
)

object TransferChunkAcknowledgementCodec {
    const val PAYLOAD_BYTES = 24

    fun encode(acknowledgement: TransferChunkAcknowledgement): ByteArray {
        require(acknowledgement.nextExpectedOffset >= 0L) {
            "Acknowledged offset cannot be negative"
        }
        return ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(acknowledgement.itemId.mostSignificantBits)
            .putLong(acknowledgement.itemId.leastSignificantBits)
            .putLong(acknowledgement.nextExpectedOffset)
            .array()
    }

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): TransferChunkAcknowledgement {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid chunk acknowledgement length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val itemId = UUID(buffer.long, buffer.long)
        val offset = buffer.long
        if (offset < 0L) {
            throw TransferProtocolException("Negative chunk acknowledgement offset")
        }
        return TransferChunkAcknowledgement(itemId, offset)
    }
}

/**
 * Enforces a strictly contiguous, single-item byte sequence. Duplicate, skipped,
 * reordered, cross-item, or over-limit chunks are rejected before the writer advances.
 */
class TransferChunkSequenceValidator(
    private val expectedItemId: UUID,
    private val maximumSizeBytes: Long,
    initialOffsetBytes: Long = 0L,
) {
    var nextExpectedOffset: Long = initialOffsetBytes
        private set

    init {
        require(maximumSizeBytes >= 0L) { "Maximum transfer size cannot be negative" }
        require(initialOffsetBytes in 0L..maximumSizeBytes) {
            "Initial offset is outside the permitted transfer range"
        }
    }

    fun accept(chunk: TransferDataChunk): TransferChunkAcknowledgement {
        if (chunk.itemId != expectedItemId) {
            throw TransferProtocolException("Chunk belongs to a different transfer item")
        }
        if (chunk.offsetBytes != nextExpectedOffset) {
            throw TransferProtocolException(
                "Non-contiguous chunk offset: expected $nextExpectedOffset, received ${chunk.offsetBytes}",
            )
        }
        if (chunk.sizeBytes.toLong() > maximumSizeBytes - nextExpectedOffset) {
            throw TransferProtocolException("Chunk exceeds receiver transfer-size limit")
        }
        nextExpectedOffset += chunk.sizeBytes.toLong()
        return TransferChunkAcknowledgement(expectedItemId, nextExpectedOffset)
    }
}

object TransferFlowControlPolicy {
    const val MAX_IN_FLIGHT_CHUNKS = 4
    const val MAX_IN_FLIGHT_BYTES =
        TransferFrameCodec.MAX_DATA_CHUNK_BYTES * MAX_IN_FLIGHT_CHUNKS
}