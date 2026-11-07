package com.peyvand.core.transfer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class TransferChunkAcknowledgement(
    val itemId: UUID,
    /** Next contiguous byte offset durably committed to the receiver's staging stream. */
    val nextExpectedOffset: Long,
) {
    init {
        require(nextExpectedOffset >= 0L) { "Acknowledged offset cannot be negative" }
    }
}

object TransferChunkAcknowledgementCodec {
    const val PAYLOAD_BYTES = 24

    fun encode(acknowledgement: TransferChunkAcknowledgement): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(acknowledgement.itemId.mostSignificantBits)
            .putLong(acknowledgement.itemId.leastSignificantBits)
            .putLong(acknowledgement.nextExpectedOffset)
            .array()

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
 * Enforces a strictly contiguous, single-item receive sequence.
 *
 * [validate] is side-effect free and must run before writing a chunk. Call [commit]
 * only after the complete chunk has been written and the staging layer has performed
 * the durability operation required by its destination. The returned ACK must not be
 * sent before that commit point. Use this object from one serialized receive loop.
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

    fun validate(chunk: TransferDataChunk) {
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
    }

    /**
     * Advances the receive offset after the caller has successfully committed the
     * chunk to staging. Calling this before a completed write can acknowledge data
     * that is not actually present on the receiver.
     */
    fun commit(chunk: TransferDataChunk): TransferChunkAcknowledgement {
        validate(chunk)
        nextExpectedOffset += chunk.sizeBytes.toLong()
        return TransferChunkAcknowledgement(expectedItemId, nextExpectedOffset)
    }
}

/**
 * Serial, bounded sender-side sliding window for one transfer item.
 *
 * A successful [tryReserve] means the caller may write that chunk to the socket.
 * A false result means the sender must wait for a cumulative receiver ACK before
 * reserving more data. ACKs are accepted only at boundaries of chunks already sent.
 * Repeated or stale cumulative ACKs are harmless and release no additional capacity.
 *
 * This class is intentionally not thread-safe; one sender coroutine owns it.
 */
class TransferSendWindow(
    private val expectedItemId: UUID,
    private val maximumSizeBytes: Long,
    initialOffsetBytes: Long = 0L,
    private val maximumInFlightChunks: Int = TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS,
    private val maximumInFlightBytes: Int = TransferFlowControlPolicy.MAX_IN_FLIGHT_BYTES,
) {
    private val inFlightChunks = linkedMapOf<Long, Int>()

    var acknowledgedOffsetBytes: Long = initialOffsetBytes
        private set

    var nextSendOffsetBytes: Long = initialOffsetBytes
        private set

    var inFlightBytes: Int = 0
        private set

    val inFlightChunkCount: Int
        get() = inFlightChunks.size

    val availableInFlightBytes: Int
        get() = maximumInFlightBytes - inFlightBytes

    val hasCapacity: Boolean
        get() = inFlightChunkCount < maximumInFlightChunks && availableInFlightBytes > 0

    init {
        require(maximumSizeBytes >= 0L) { "Maximum transfer size cannot be negative" }
        require(initialOffsetBytes in 0L..maximumSizeBytes) {
            "Initial offset is outside the permitted transfer range"
        }
        require(maximumInFlightChunks in 1..TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS) {
            "In-flight chunk count must be between 1 and the protocol maximum"
        }
        require(maximumInFlightBytes in 1..TransferFlowControlPolicy.MAX_IN_FLIGHT_BYTES) {
            "In-flight byte limit must be between 1 and the protocol maximum"
        }
    }

    /**
     * Reserves a contiguous chunk for transmission. Returns false only when the
     * negotiated local window is currently full. Invalid sequence, item, or size
     * information is a protocol error rather than a backpressure signal.
     */
    fun tryReserve(chunk: TransferDataChunk): Boolean {
        if (chunk.itemId != expectedItemId) {
            throw TransferProtocolException("Chunk belongs to a different transfer item")
        }
        if (chunk.offsetBytes != nextSendOffsetBytes) {
            throw TransferProtocolException(
                "Non-contiguous sender offset: expected $nextSendOffsetBytes, received ${chunk.offsetBytes}",
            )
        }
        if (chunk.sizeBytes.toLong() > maximumSizeBytes - nextSendOffsetBytes) {
            throw TransferProtocolException("Chunk exceeds configured transfer-size limit")
        }
        if (chunk.sizeBytes > maximumInFlightBytes) {
            throw TransferProtocolException("Chunk cannot fit in the configured in-flight window")
        }
        if (inFlightChunkCount >= maximumInFlightChunks ||
            chunk.sizeBytes > availableInFlightBytes
        ) {
            return false
        }

        inFlightChunks[chunk.offsetBytes] = chunk.sizeBytes
        nextSendOffsetBytes += chunk.sizeBytes.toLong()
        inFlightBytes += chunk.sizeBytes
        return true
    }

    /**
     * Applies a cumulative ACK. The next expected offset must be a boundary of a
     * previously reserved chunk and cannot acknowledge bytes that were never sent.
     * Returns the number of chunk reservations released by this ACK.
     */
    fun acknowledge(acknowledgement: TransferChunkAcknowledgement): Int {
        if (acknowledgement.itemId != expectedItemId) {
            throw TransferProtocolException("Acknowledgement belongs to a different transfer item")
        }

        val acknowledged = acknowledgement.nextExpectedOffset
        if (acknowledged < 0L) {
            throw TransferProtocolException("Negative acknowledged offset")
        }
        if (acknowledged <= acknowledgedOffsetBytes) {
            return 0
        }
        if (acknowledged > nextSendOffsetBytes) {
            throw TransferProtocolException("Acknowledgement exceeds bytes sent")
        }

        val isChunkBoundary = inFlightChunks.any { (offset, size) ->
            offset + size.toLong() == acknowledged
        }
        if (!isChunkBoundary) {
            throw TransferProtocolException("Acknowledgement is not on a sent chunk boundary")
        }

        var releasedChunks = 0
        val iterator = inFlightChunks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val chunkEnd = entry.key + entry.value.toLong()
            if (chunkEnd > acknowledged) break
            inFlightBytes -= entry.value
            iterator.remove()
            releasedChunks += 1
        }

        acknowledgedOffsetBytes = acknowledged
        return releasedChunks
    }
}

object TransferFlowControlPolicy {
    const val MAX_IN_FLIGHT_CHUNKS = 4
    const val MAX_IN_FLIGHT_BYTES =
        TransferFrameCodec.MAX_DATA_CHUNK_BYTES * MAX_IN_FLIGHT_CHUNKS
}
