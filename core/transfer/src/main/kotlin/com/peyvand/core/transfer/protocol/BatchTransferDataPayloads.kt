package com.peyvand.core.transfer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BatchTransferDataChunk(
    val identity: BatchTransferItemIdentity,
    val offsetBytes: Long,
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()

    init {
        require(offsetBytes >= 0L) { "Batch chunk offset cannot be negative" }
        require(content.size in 1..BatchTransferFrameCodec.MAX_DATA_CHUNK_BYTES) {
            "Batch chunk size is outside the protocol limit"
        }
        require(offsetBytes <= Long.MAX_VALUE - content.size.toLong()) {
            "Batch chunk end offset overflows Long"
        }
    }

    val sizeBytes: Int
        get() = content.size

    fun bytes(): ByteArray = content.copyOf()
}

object BatchTransferDataChunkCodec {
    const val METADATA_BYTES = 16 + 16 + 4 + 8

    fun encode(chunk: BatchTransferDataChunk): ByteArray {
        val bytes = chunk.bytes()
        return ByteBuffer.allocate(METADATA_BYTES + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putIdentity(chunk.identity)
            .putLong(chunk.offsetBytes)
            .put(bytes)
            .array()
    }

    fun decode(payload: ByteArray): BatchTransferDataChunk {
        if (payload.size !in (METADATA_BYTES + 1)..(
                METADATA_BYTES + BatchTransferFrameCodec.MAX_DATA_CHUNK_BYTES
            )
        ) {
            throw TransferProtocolException("Invalid batch data chunk payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val identity = buffer.readIdentity()
        val offset = buffer.long
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return try {
            BatchTransferDataChunk(identity, offset, bytes)
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid batch data chunk", exception)
        }
    }
}

data class BatchTransferChunkAcknowledgement(
    val identity: BatchTransferItemIdentity,
    val nextExpectedOffset: Long,
) {
    init {
        require(nextExpectedOffset >= 0L) { "Batch ACK offset cannot be negative" }
    }
}

object BatchTransferChunkAcknowledgementCodec {
    const val PAYLOAD_BYTES = 44

    fun encode(value: BatchTransferChunkAcknowledgement): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putIdentity(value.identity)
            .putLong(value.nextExpectedOffset)
            .array()

    fun decode(payload: ByteArray): BatchTransferChunkAcknowledgement {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch ACK payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val identity = buffer.readIdentity()
        val nextExpectedOffset = buffer.long
        if (nextExpectedOffset < 0L) {
            throw TransferProtocolException("Negative batch ACK offset")
        }
        return BatchTransferChunkAcknowledgement(identity, nextExpectedOffset)
    }
}

data class BatchTransferItemIntegrityRecord(
    val identity: BatchTransferItemIdentity,
    val actualSizeBytes: Long,
    val sha256Hex: String,
) {
    init {
        require(actualSizeBytes >= 0L) { "Verified item size cannot be negative" }
        require(SHA256_PATTERN.matches(sha256Hex)) {
            "SHA-256 must be exactly 64 lowercase hexadecimal characters"
        }
    }

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

object BatchTransferItemIntegrityCodec {
    const val PAYLOAD_BYTES = 76
    private const val DIGEST_BYTES = 32

    fun encode(record: BatchTransferItemIntegrityRecord): ByteArray {
        val digest = decodeSha256(record.sha256Hex)
        return ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putIdentity(record.identity)
            .putLong(record.actualSizeBytes)
            .put(digest)
            .array()
    }

    fun decode(payload: ByteArray): BatchTransferItemIntegrityRecord {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch integrity record length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val identity = buffer.readIdentity()
        val actualSizeBytes = buffer.long
        if (actualSizeBytes < 0L) {
            throw TransferProtocolException("Negative batch integrity byte count")
        }
        val digest = ByteArray(DIGEST_BYTES)
        buffer.get(digest)
        return try {
            BatchTransferItemIntegrityRecord(
                identity = identity,
                actualSizeBytes = actualSizeBytes,
                sha256Hex = digest.toLowerHex(),
            )
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid batch integrity record", exception)
        }
    }

    private fun decodeSha256(value: String): ByteArray {
        require(value.length == DIGEST_BYTES * 2 && value.all {
            it in '0'..'9' || it in 'a'..'f'
        }) { "SHA-256 must be exactly 64 lowercase hexadecimal characters" }
        return ByteArray(DIGEST_BYTES) { index ->
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }

    private fun ByteArray.toLowerHex(): String {
        val alphabet = "0123456789abcdef"
        val chars = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xff
            chars[index * 2] = alphabet[value ushr 4]
            chars[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(chars)
    }
}

enum class BatchTransferAbortOutcome(val wireCode: Int) {
    CANCELLED(1),
    FAILED(2);

    companion object {
        fun fromWireCode(code: Int): BatchTransferAbortOutcome =
            entries.firstOrNull { it.wireCode == code }
                ?: throw TransferProtocolException("Unknown batch item abort outcome")
    }
}

data class BatchTransferItemAbortRecord(
    val identity: BatchTransferItemIdentity,
    val finalOffsetBytes: Long,
    val outcome: BatchTransferAbortOutcome,
    val reason: BatchTransferReasonCode,
) {
    init {
        require(finalOffsetBytes >= 0L) { "Abort boundary cannot be negative" }
    }
}

object BatchTransferItemAbortCodec {
    const val PAYLOAD_BYTES = 46

    fun encode(record: BatchTransferItemAbortRecord): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putIdentity(record.identity)
            .putLong(record.finalOffsetBytes)
            .put(record.outcome.wireCode.toByte())
            .put(record.reason.wireCode.toByte())
            .array()

    fun decode(payload: ByteArray): BatchTransferItemAbortRecord {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch item abort payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val identity = buffer.readIdentity()
        val finalOffset = buffer.long
        val outcome = BatchTransferAbortOutcome.fromWireCode(buffer.get().toInt() and 0xff)
        val reason = BatchTransferReasonCode.fromWireCode(buffer.get().toInt() and 0xff)
        if (finalOffset < 0L) {
            throw TransferProtocolException("Negative batch abort boundary")
        }
        return BatchTransferItemAbortRecord(identity, finalOffset, outcome, reason)
    }
}

data class BatchTransferBatchAcceptance(
    val batchId: UUID,
)

object BatchTransferBatchAcceptanceCodec {
    const val PAYLOAD_BYTES = 16

    fun encode(value: BatchTransferBatchAcceptance): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putUuid(value.batchId)
            .array()

    fun decode(payload: ByteArray): BatchTransferBatchAcceptance {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch acceptance payload length")
        }
        return BatchTransferBatchAcceptance(
            ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).readUuid(),
        )
    }
}

data class BatchTransferBatchStatusRecord(
    val batchId: UUID,
    val reason: BatchTransferReasonCode,
)

object BatchTransferBatchStatusCodec {
    const val PAYLOAD_BYTES = 17

    fun encode(value: BatchTransferBatchStatusRecord): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putUuid(value.batchId)
            .put(value.reason.wireCode.toByte())
            .array()

    fun decode(payload: ByteArray): BatchTransferBatchStatusRecord {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch status payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = buffer.readUuid()
        val reason = BatchTransferReasonCode.fromWireCode(buffer.get().toInt() and 0xff)
        return BatchTransferBatchStatusRecord(batchId, reason)
    }
}

data class BatchTransferCompletionSummary(
    val batchId: UUID,
    val totalItems: Int,
    val completedItems: Int,
    val rejectedItems: Int,
    val cancelledItems: Int,
    val failedItems: Int,
) {
    init {
        require(totalItems in BatchTransferOffer.MIN_BATCH_ITEMS..BatchTransferOffer.MAX_BATCH_ITEMS)
        require(completedItems >= 0)
        require(rejectedItems >= 0)
        require(cancelledItems >= 0)
        require(failedItems >= 0)
        require(completedItems + rejectedItems + cancelledItems + failedItems == totalItems) {
            "Batch completion item outcomes must add up to the declared item count"
        }
    }
}

object BatchTransferCompletionSummaryCodec {
    const val PAYLOAD_BYTES = 26

    fun encode(summary: BatchTransferCompletionSummary): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putUuid(summary.batchId)
            .putShort(summary.totalItems.toShort())
            .putShort(summary.completedItems.toShort())
            .putShort(summary.rejectedItems.toShort())
            .putShort(summary.cancelledItems.toShort())
            .putShort(summary.failedItems.toShort())
            .array()

    fun decode(payload: ByteArray): BatchTransferCompletionSummary {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch completion summary payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = buffer.readUuid()
        val totalItems = buffer.short.toInt() and 0xffff
        val completedItems = buffer.short.toInt() and 0xffff
        val rejectedItems = buffer.short.toInt() and 0xffff
        val cancelledItems = buffer.short.toInt() and 0xffff
        val failedItems = buffer.short.toInt() and 0xffff
        return try {
            BatchTransferCompletionSummary(
                batchId = batchId,
                totalItems = totalItems,
                completedItems = completedItems,
                rejectedItems = rejectedItems,
                cancelledItems = cancelledItems,
                failedItems = failedItems,
            )
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid batch completion summary", exception)
        }
    }
}
