package com.peyvand.core.transfer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class TransferCompletionRecord(
    val itemId: UUID,
    val actualSizeBytes: Long,
    val sha256Hex: String,
)

/**
 * Encodes both the sender's final stream declaration and the receiver's verified receipt.
 * The enclosing frame type distinguishes ITEM_COMPLETE from ITEM_VERIFIED.
 */
object TransferCompletionCodec {
    const val PAYLOAD_BYTES = 16 + 8 + 32

    fun encode(record: TransferCompletionRecord): ByteArray {
        require(record.actualSizeBytes >= 0L) { "Actual file size cannot be negative" }
        val digest = decodeSha256(record.sha256Hex)
        return ByteBuffer.allocate(PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(record.itemId.mostSignificantBits)
            .putLong(record.itemId.leastSignificantBits)
            .putLong(record.actualSizeBytes)
            .put(digest)
            .array()
    }

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): TransferCompletionRecord {
        if (payload.size != PAYLOAD_BYTES) {
            throw TransferProtocolException("Invalid transfer completion payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val itemId = UUID(buffer.long, buffer.long)
        val actualSizeBytes = buffer.long
        if (actualSizeBytes < 0L) {
            throw TransferProtocolException("Negative completed file size")
        }
        val digest = ByteArray(32)
        buffer.get(digest)
        return TransferCompletionRecord(itemId, actualSizeBytes, digest.toLowerHex())
    }

    private fun decodeSha256(value: String): ByteArray {
        require(value.length == 64 && value.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }) { "SHA-256 must be exactly 64 hexadecimal characters" }
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
}