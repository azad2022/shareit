data class BatchOffer(val batchId: UUID)

object BatchOfferCodec {
    const val PAYLOAD_BYTES = 16

    fun encode(offer: BatchOffer): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(offer.batchId.mostSignificantBits)
            .putLong(offer.batchId.leastSignificantBits)
            .array()

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): BatchOffer {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch offer payload length")
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return BatchOffer(UUID(input.long, input.long))
    }
}

package com.peyvand.core.transfer.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

data class BatchItemReference(
    val batchId: UUID,
    val itemId: UUID,
)

object BatchItemReferenceCodec {
    const val PAYLOAD_BYTES = 32

    fun encode(reference: BatchItemReference): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(reference.batchId.mostSignificantBits)
            .putLong(reference.batchId.leastSignificantBits)
            .putLong(reference.itemId.mostSignificantBits)
            .putLong(reference.itemId.leastSignificantBits)
            .array()

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): BatchItemReference {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch item reference length")
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return BatchItemReference(
            batchId = UUID(input.long, input.long),
            itemId = UUID(input.long, input.long),
        )
    }
}

class BatchTransferDataChunk(
    val batchId: UUID,
    val itemId: UUID,
    val offsetBytes: Long,
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()

    init {
        require(offsetBytes >= 0L)
        require(content.isNotEmpty())
        require(content.size <= TransferFrameCodec.MAX_DATA_CHUNK_BYTES)
        require(offsetBytes <= Long.MAX_VALUE - content.size.toLong())
    }

    val sizeBytes: Int
        get() = content.size

    fun bytes(): ByteArray = content.copyOf()
}

object BatchTransferDataChunkCodec {
    const val METADATA_BYTES = 16 + 16 + 8

    fun encode(chunk: BatchTransferDataChunk): ByteArray {
        val bytes = chunk.bytes()
        return ByteBuffer.allocate(METADATA_BYTES + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(chunk.batchId.mostSignificantBits)
            .putLong(chunk.batchId.leastSignificantBits)
            .putLong(chunk.itemId.mostSignificantBits)
            .putLong(chunk.itemId.leastSignificantBits)
            .putLong(chunk.offsetBytes)
            .put(bytes)
            .array()
    }

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): BatchTransferDataChunk {
        if (payload.size !in (METADATA_BYTES + 1)..(
                METADATA_BYTES + TransferFrameCodec.MAX_DATA_CHUNK_BYTES
            )
        ) {
            throw TransferProtocolException("Invalid batch data chunk length")
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = UUID(input.long, input.long)
        val itemId = UUID(input.long, input.long)
        val offset = input.long
        if (offset < 0L || offset > Long.MAX_VALUE - input.remaining().toLong()) {
            throw TransferProtocolException("Invalid batch data offset")
        }
        val bytes = ByteArray(input.remaining())
        input.get(bytes)
        return try {
            BatchTransferDataChunk(batchId, itemId, offset, bytes)
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid batch data chunk", exception)
        }
    }
}

data class BatchChunkAcknowledgement(
    val batchId: UUID,
    val itemId: UUID,
    val nextExpectedOffset: Long,
) {
    init {
        require(nextExpectedOffset >= 0L)
    }
}

object BatchChunkAcknowledgementCodec {
    const val PAYLOAD_BYTES = 16 + 16 + 8

    fun encode(acknowledgement: BatchChunkAcknowledgement): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(acknowledgement.batchId.mostSignificantBits)
            .putLong(acknowledgement.batchId.leastSignificantBits)
            .putLong(acknowledgement.itemId.mostSignificantBits)
            .putLong(acknowledgement.itemId.leastSignificantBits)
            .putLong(acknowledgement.nextExpectedOffset)
            .array()

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): BatchChunkAcknowledgement {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch chunk ACK length")
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val acknowledgement = BatchChunkAcknowledgement(
            batchId = UUID(input.long, input.long),
            itemId = UUID(input.long, input.long),
            nextExpectedOffset = input.long,
        )
        return acknowledgement.takeIf { it.nextExpectedOffset >= 0L }
            ?: throw TransferProtocolException("Negative batch chunk ACK offset")
    }
}

data class BatchItemCompletionRecord(
    val batchId: UUID,
    val itemId: UUID,
    val actualSizeBytes: Long,
    val sha256Hex: String,
) {
    init {
        require(actualSizeBytes >= 0L)
        require(isSha256Hex(sha256Hex))
    }
}

object BatchItemCompletionCodec {
    const val PAYLOAD_BYTES = 16 + 16 + 8 + 32

    fun encode(record: BatchItemCompletionRecord): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(record.batchId.mostSignificantBits)
            .putLong(record.batchId.leastSignificantBits)
            .putLong(record.itemId.mostSignificantBits)
            .putLong(record.itemId.leastSignificantBits)
            .putLong(record.actualSizeBytes)
            .put(decodeSha256(record.sha256Hex))
            .array()

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): BatchItemCompletionRecord {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch item completion length")
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = UUID(input.long, input.long)
        val itemId = UUID(input.long, input.long)
        val size = input.long
        if (size < 0L) {
            throw TransferProtocolException("Negative batch item byte count")
        }
        val digest = ByteArray(32)
        input.get(digest)
        return BatchItemCompletionRecord(batchId, itemId, size, digest.toLowerHex())
    }
}

enum class BatchItemOutcomeStatus(val wireCode: Int) {
    COMPLETED(1),
    REJECTED(2),
    CANCELLED(3),
    FAILED(4);

    companion object {
        fun fromWireCode(code: Int): BatchItemOutcomeStatus =
            entries.firstOrNull { it.wireCode == code }
                ?: throw TransferProtocolException("Unknown batch item outcome")
    }
}

enum class BatchItemFailureCode(val wireCode: Int) {
    SOURCE_UNAVAILABLE(1),
    SOURCE_READ_FAILED(2),
    STORAGE_UNAVAILABLE(3),
    STORAGE_WRITE_FAILED(4),
    DESTINATION_FAILED(5),
    UNKNOWN_ITEM_FAILURE(255);

    companion object {
        fun fromWireCode(code: Int): BatchItemFailureCode =
            entries.firstOrNull { it.wireCode == code }
                ?: throw TransferProtocolException("Unknown batch item failure code")
    }
}

data class BatchItemFailureRecord(
    val batchId: UUID,
    val itemId: UUID,
    val failureCode: BatchItemFailureCode,
)

object BatchItemFailureCodec {
    const val PAYLOAD_BYTES = 16 + 16 + 1

    fun encode(record: BatchItemFailureRecord): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(record.batchId.mostSignificantBits)
            .putLong(record.batchId.leastSignificantBits)
            .putLong(record.itemId.mostSignificantBits)
            .putLong(record.itemId.leastSignificantBits)
            .put(record.failureCode.wireCode.toByte())
            .array()

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): BatchItemFailureRecord {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch item failure length")
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return BatchItemFailureRecord(
            batchId = UUID(input.long, input.long),
            itemId = UUID(input.long, input.long),
            failureCode = BatchItemFailureCode.fromWireCode(input.get().toInt() and 0xff),
        )
    }
}

data class BatchItemOutcome(
    val itemId: UUID,
    val status: BatchItemOutcomeStatus,
    val actualSizeBytes: Long? = null,
    val sha256Hex: String? = null,
) {
    init {
        if (status == BatchItemOutcomeStatus.COMPLETED) {
            require(actualSizeBytes != null && actualSizeBytes >= 0L)
            require(sha256Hex != null && isSha256Hex(sha256Hex))
        } else {
            require(actualSizeBytes == null && sha256Hex == null)
        }
    }
}

/**
 * Hashes an ordered, canonical representation of every item's terminal state.
 * Both peers compute this after validating each item-specific outcome/receipt.
 */
object BatchOutcomeDigest {
    fun compute(
        batchId: UUID,
        orderedItemIds: List<UUID>,
        outcomes: List<BatchItemOutcome>,
    ): String {
        require(orderedItemIds.size in 2..32)
        require(outcomes.map(BatchItemOutcome::itemId) == orderedItemIds) {
            "Batch outcomes must match the immutable manifest order"
        }
        val bytes = ByteArrayOutputStream(16 + orderedItemIds.size * 57)
        DataOutputStream(bytes).use { output ->
            output.writeLong(batchId.mostSignificantBits)
            output.writeLong(batchId.leastSignificantBits)
            outcomes.forEach { outcome ->
                output.writeLong(outcome.itemId.mostSignificantBits)
                output.writeLong(outcome.itemId.leastSignificantBits)
                output.writeByte(outcome.status.wireCode)
                if (outcome.status == BatchItemOutcomeStatus.COMPLETED) {
                    output.writeLong(requireNotNull(outcome.actualSizeBytes))
                    output.write(decodeSha256(requireNotNull(outcome.sha256Hex)))
                } else {
                    output.writeLong(-1L)
                    output.write(ByteArray(32))
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toLowerHex()
    }
}

data class BatchTerminalRecord(
    val batchId: UUID,
    val itemCount: Int,
    val outcomeDigestHex: String,
) {
    init {
        require(itemCount in 2..32)
        require(isSha256Hex(outcomeDigestHex))
    }
}

object BatchTerminalRecordCodec {
    const val PAYLOAD_BYTES = 16 + 4 + 32

    fun encode(record: BatchTerminalRecord): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(record.batchId.mostSignificantBits)
            .putLong(record.batchId.leastSignificantBits)
            .putInt(record.itemCount)
            .put(decodeSha256(record.outcomeDigestHex))
            .array()

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): BatchTerminalRecord {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch terminal record length")
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = UUID(input.long, input.long)
        val itemCount = input.int
        if (itemCount !in 2..32) {
            throw TransferProtocolException("Invalid batch terminal item count")
        }
        val digest = ByteArray(32)
        input.get(digest)
        return BatchTerminalRecord(batchId, itemCount, digest.toLowerHex())
    }
}

private fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
    }

private fun decodeSha256(value: String): ByteArray {
    require(isSha256Hex(value)) { "Expected a 32-byte SHA-256 hexadecimal digest" }
    return ByteArray(32) { index ->
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
