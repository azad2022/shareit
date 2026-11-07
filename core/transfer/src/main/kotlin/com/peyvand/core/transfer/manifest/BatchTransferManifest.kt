package com.peyvand.core.transfer.manifest

import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

data class BatchTransferManifest(
    val batchId: UUID,
    val items: List<TransferManifestItem>,
) {
    init {
        require(items.size in MIN_ITEMS..MAX_ITEMS) {
            "A batch must contain between $MIN_ITEMS and $MAX_ITEMS items"
        }
        require(items.map(TransferManifestItem::itemId).toSet().size == items.size) {
            "Batch item IDs must be unique"
        }
    }

    companion object {
        const val MIN_ITEMS = 2
        const val MAX_ITEMS = 32
    }
}

/**
 * Bounded, ordered manifest codec. Each existing single-item manifest is length-prefixed,
 * so malformed item boundaries cannot consume the next record or allocate an unbounded array.
 */
object BatchTransferManifestCodec {
    private const val BATCH_HEADER_BYTES = 16 + 2
    private const val ITEM_LENGTH_BYTES = 2
    private const val MAX_ITEM_ENCODED_BYTES =
        16 + 2 + TransferManifestCodec.MAX_NAME_BYTES +
            2 + TransferManifestCodec.MAX_MIME_BYTES + 8

    fun encode(manifest: BatchTransferManifest): ByteArray {
        val output = ByteArrayOutputStream(
            BATCH_HEADER_BYTES + manifest.items.size * (ITEM_LENGTH_BYTES + MAX_ITEM_ENCODED_BYTES),
        )
        DataOutputStream(output).use { data ->
            data.writeLong(manifest.batchId.mostSignificantBits)
            data.writeLong(manifest.batchId.leastSignificantBits)
            data.writeShort(manifest.items.size)
            manifest.items.forEach { item ->
                val encoded = TransferManifestCodec.encode(item)
                require(encoded.size in 1..MAX_ITEM_ENCODED_BYTES)
                data.writeShort(encoded.size)
                data.write(encoded)
                require(output.size() <= TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES) {
                    "Batch manifest exceeds the control-frame limit"
                }
            }
        }
        val result = output.toByteArray()
        require(result.size <= TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES) {
            "Batch manifest exceeds the control-frame limit"
        }
        return result
    }

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): BatchTransferManifest {
        if (payload.size !in BATCH_HEADER_BYTES..TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch manifest payload length")
        }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = UUID(input.long, input.long)
        val count = input.short.toInt() and 0xffff
        if (count !in BatchTransferManifest.MIN_ITEMS..BatchTransferManifest.MAX_ITEMS) {
            throw TransferProtocolException("Invalid batch item count")
        }

        val items = ArrayList<TransferManifestItem>(count)
        val ids = HashSet<UUID>(count)
        repeat(count) {
            if (input.remaining() < ITEM_LENGTH_BYTES) {
                throw TransferProtocolException("Truncated batch item length")
            }
            val itemLength = input.short.toInt() and 0xffff
            if (itemLength !in 1..MAX_ITEM_ENCODED_BYTES || itemLength > input.remaining()) {
                throw TransferProtocolException("Invalid batch item manifest length")
            }
            val itemBytes = ByteArray(itemLength)
            input.get(itemBytes)
            val item = TransferManifestCodec.decode(itemBytes)
            if (!ids.add(item.itemId)) {
                throw TransferProtocolException("Duplicate item ID in batch manifest")
            }
            items += item
        }
        if (input.hasRemaining()) {
            throw TransferProtocolException("Trailing bytes after batch manifest")
        }
        return try {
            BatchTransferManifest(batchId, items)
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid batch manifest", exception)
        }
    }

    fun digest(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .toLowerHex()

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

data class BatchConsentDecision(
    val batchId: UUID,
    val manifestDigestHex: String,
    /** Bit i corresponds to manifest.items[i]. Bit positions are never reordered. */
    val acceptedMask: Long,
) {
    init {
        require(acceptedMask in 0L..0xffff_ffffL)
        require(isSha256Hex(manifestDigestHex))
    }

    fun accepts(index: Int): Boolean {
        require(index in 0 until BatchTransferManifest.MAX_ITEMS)
        return (acceptedMask and (1L shl index)) != 0L
    }
}

object BatchConsentDecisionCodec {
    const val PAYLOAD_BYTES = 16 + 32 + 4

    fun encode(decision: BatchConsentDecision): ByteArray {
        val digest = decodeSha256(decision.manifestDigestHex)
        return ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(decision.batchId.mostSignificantBits)
            .putLong(decision.batchId.leastSignificantBits)
            .put(digest)
            .putInt(decision.acceptedMask.toInt())
            .array()
    }

    @Throws(TransferProtocolException::class)
    fun decode(
        payload: ByteArray,
        manifest: BatchTransferManifest,
    ): BatchConsentDecision {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid batch decision payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val batchId = UUID(buffer.long, buffer.long)
        val digestBytes = ByteArray(32)
        buffer.get(digestBytes)
        val acceptedMask = buffer.int.toLong() and 0xffff_ffffL

        if (batchId != manifest.batchId) {
            throw TransferProtocolException("Batch decision belongs to a different batch")
        }
        if (acceptedMask ushr manifest.items.size != 0L) {
            throw TransferProtocolException("Batch decision selects an unknown item")
        }

        val expectedDigest = BatchTransferManifestCodec.digest(
            BatchTransferManifestCodec.encode(manifest),
        )
        if (!MessageDigest.isEqual(digestBytes, decodeSha256(expectedDigest))) {
            throw TransferProtocolException("Batch decision does not match the exact manifest")
        }
        return BatchConsentDecision(batchId, expectedDigest, acceptedMask)
    }

    private fun decodeSha256(value: String): ByteArray {
        if (!isSha256Hex(value)) {
            throw IllegalArgumentException("Expected a 32-byte SHA-256 hexadecimal digest")
        }
        return ByteArray(32) { index ->
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }
}

private fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
    }
