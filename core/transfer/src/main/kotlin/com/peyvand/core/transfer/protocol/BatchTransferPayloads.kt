package com.peyvand.core.transfer.protocol

import com.peyvand.core.transfer.manifest.TransferManifestCodec
import com.peyvand.core.transfer.manifest.TransferManifestItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class BatchTransferOffer(
    val batchId: UUID,
    val itemCount: Int,
    val aggregateSizeBytes: Long?,
) {
    init {
        require(itemCount in 2..MAX_BATCH_ITEMS) { "A v2 batch must contain 2-100 items" }
        require(aggregateSizeBytes == null || aggregateSizeBytes >= 0L) {
            "Aggregate size must be non-negative or unknown"
        }
    }

    companion object {
        const val MAX_BATCH_ITEMS = 100
    }
}

object BatchTransferOfferCodec {
    const val PAYLOAD_BYTES = 26
    private const val UNKNOWN_SIZE = -1L

    fun encode(offer: BatchTransferOffer): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putUuid(offer.batchId)
            .putShort(offer.itemCount.toShort())
            .putLong(offer.aggregateSizeBytes ?: UNKNOWN_SIZE)
            .array()

    fun decode(payload: ByteArray): BatchTransferOffer {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch offer payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = buffer.readUuid()
        val count = buffer.short.toInt() and 0xffff
        val aggregate = buffer.long
        if (aggregate < UNKNOWN_SIZE) {
            throw TransferProtocolException("Invalid batch aggregate size")
        }
        return try {
            BatchTransferOffer(
                batchId = batchId,
                itemCount = count,
                aggregateSizeBytes = aggregate.takeIf { it != UNKNOWN_SIZE },
            )
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid batch offer fields", exception)
        }
    }
}

data class BatchTransferItemIdentity(
    val batchId: UUID,
    val itemId: UUID,
    val ordinal: Int,
) {
    init {
        require(batchId != itemId) { "Batch and item identifiers must differ" }
        require(ordinal in 0 until BatchTransferOffer.MAX_BATCH_ITEMS) {
            "Item ordinal is outside the batch limit"
        }
    }
}

data class BatchTransferItemManifest(
    val identity: BatchTransferItemIdentity,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
)

object BatchTransferItemManifestCodec {
    private const val IDENTITY_BYTES = 16 + 4

    fun encode(manifest: BatchTransferItemManifest): ByteArray {
        val itemManifest = TransferManifestCodec.encode(
            TransferManifestItem(
                itemId = manifest.identity.itemId,
                displayName = manifest.displayName,
                mimeType = manifest.mimeType,
                sizeBytes = manifest.sizeBytes,
            ),
        )
        return ByteBuffer.allocate(IDENTITY_BYTES + itemManifest.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putUuid(manifest.identity.batchId)
            .putInt(manifest.identity.ordinal)
            .put(itemManifest)
            .array()
    }

    fun decode(payload: ByteArray): BatchTransferItemManifest {
        val minimum = IDENTITY_BYTES + 30
        val maximum = IDENTITY_BYTES + 28 +
            TransferManifestCodec.MAX_NAME_BYTES + TransferManifestCodec.MAX_MIME_BYTES
        if (payload.size !in minimum..maximum) {
            throw TransferProtocolException("Invalid batch item manifest payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = buffer.readUuid()
        val ordinal = buffer.int
        val encodedItemManifest = ByteArray(buffer.remaining())
        buffer.get(encodedItemManifest)
        val item = TransferManifestCodec.decode(encodedItemManifest)
        return try {
            BatchTransferItemManifest(
                identity = BatchTransferItemIdentity(batchId, item.itemId, ordinal),
                displayName = item.displayName,
                mimeType = item.mimeType,
                sizeBytes = item.sizeBytes,
            )
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid batch item manifest fields", exception)
        }
    }
}

enum class BatchTransferReasonCode(val wireCode: Int) {
    USER_REJECTED(1),
    CONSENT_TIMEOUT(2),
    SOURCE_UNAVAILABLE(3),
    SOURCE_PERMISSION_REVOKED(4),
    SOURCE_READ_FAILED(5),
    SOURCE_SIZE_MISMATCH(6),
    DESTINATION_UNAVAILABLE(7),
    DESTINATION_CREATE_FAILED(8),
    DESTINATION_WRITE_FAILED(9),
    DESTINATION_READ_BACK_FAILED(10),
    STORAGE_EXHAUSTED(11),
    INTEGRITY_MISMATCH(12),
    PUBLICATION_FAILED(13),
    CLEANUP_FAILED(14),
    ITEM_TIMEOUT(15),
    ABORT_HANDSHAKE_FAILED(16),
    RESOURCE_LIMIT_EXCEEDED(17),
    PROTOCOL_ERROR(18),
    CHANNEL_LOST(19),
    INVALID_RECEIPT(20),
    UNKNOWN(255);

    companion object {
        fun fromWireCode(code: Int): BatchTransferReasonCode =
            entries.firstOrNull { it.wireCode == code }
                ?: throw TransferProtocolException("Unknown batch failure reason code")
    }
}

data class BatchTransferItemAcceptance(
    val identity: BatchTransferItemIdentity,
    val maximumAcceptedBytes: Long,
) {
    init {
        require(maximumAcceptedBytes >= 0L)
    }
}

object BatchTransferItemAcceptanceCodec {
    const val PAYLOAD_BYTES = 44

    fun encode(value: BatchTransferItemAcceptance): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putIdentity(value.identity)
            .putLong(value.maximumAcceptedBytes)
            .array()

    fun decode(payload: ByteArray): BatchTransferItemAcceptance {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid item acceptance payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val identity = buffer.readIdentity()
        val maximumBytes = buffer.long
        if (maximumBytes < 0L) {
            throw TransferProtocolException("Negative accepted item byte limit")
        }
        return BatchTransferItemAcceptance(identity, maximumBytes)
    }
}

data class BatchTransferItemRejection(
    val identity: BatchTransferItemIdentity,
    val reason: BatchTransferReasonCode,
)

object BatchTransferItemRejectionCodec {
    const val PAYLOAD_BYTES = 37

    fun encode(value: BatchTransferItemRejection): ByteArray =
        ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putIdentity(value.identity)
            .put(value.reason.wireCode.toByte())
            .array()

    fun decode(payload: ByteArray): BatchTransferItemRejection {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid item rejection payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val identity = buffer.readIdentity()
        val reason = BatchTransferReasonCode.fromWireCode(buffer.get().toInt() and 0xff)
        return BatchTransferItemRejection(identity, reason)
    }
}

internal fun ByteBuffer.putUuid(value: UUID): ByteBuffer =
    putLong(value.mostSignificantBits).putLong(value.leastSignificantBits)

internal fun ByteBuffer.readUuid(): UUID = UUID(long, long)

internal fun ByteBuffer.putIdentity(value: BatchTransferItemIdentity): ByteBuffer =
    putUuid(value.batchId).putUuid(value.itemId).putInt(value.ordinal)

internal fun ByteBuffer.readIdentity(): BatchTransferItemIdentity {
    val batchId = readUuid()
    val itemId = readUuid()
    val ordinal = int
    return try {
        BatchTransferItemIdentity(batchId, itemId, ordinal)
    } catch (exception: IllegalArgumentException) {
        throw TransferProtocolException("Invalid batch item identity", exception)
    }
}
