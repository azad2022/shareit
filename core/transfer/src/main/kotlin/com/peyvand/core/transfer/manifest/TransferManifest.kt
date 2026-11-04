package com.peyvand.core.transfer.manifest

import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class TransferManifestItem(
    val itemId: UUID,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Hex: String,
)

object TransferManifestCodec {
    const val MAX_NAME_BYTES = 255
    const val MAX_MIME_BYTES = 127
    const val SHA256_BYTES = 32
    private const val FIXED_BYTES = 16 + 2 + 2 + 8 + SHA256_BYTES

    private val mimeTypePattern = Regex(
        "^[a-z0-9!#$&^_.+-]{1,64}/(?:[a-z0-9!#$&^_.+-]{1,64}|\\*)$",
    )

    fun encode(item: TransferManifestItem): ByteArray {
        require(item.sizeBytes >= 0L) { "File size cannot be negative" }
        val name = SafeTransferFileName.sanitize(item.displayName)
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val mimeType = normalizeMimeType(item.mimeType)
        val mimeBytes = mimeType.toByteArray(StandardCharsets.UTF_8)
        val digest = decodeSha256(item.sha256Hex)
        require(nameBytes.size in 1..MAX_NAME_BYTES)
        require(mimeBytes.size in 1..MAX_MIME_BYTES)

        val output = ByteArrayOutputStream(FIXED_BYTES + nameBytes.size + mimeBytes.size)
        DataOutputStream(output).use { data ->
            data.writeLong(item.itemId.mostSignificantBits)
            data.writeLong(item.itemId.leastSignificantBits)
            data.writeShort(nameBytes.size)
            data.write(nameBytes)
            data.writeShort(mimeBytes.size)
            data.write(mimeBytes)
            data.writeLong(item.sizeBytes)
            data.write(digest)
        }
        return output.toByteArray()
    }

    @Throws(TransferProtocolException::class)
    fun decode(payload: ByteArray): TransferManifestItem {
        if (payload.size < FIXED_BYTES || payload.size > FIXED_BYTES +
            MAX_NAME_BYTES + MAX_MIME_BYTES
        ) {
            throw TransferProtocolException("Invalid manifest payload length")
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val itemId = UUID(buffer.long, buffer.long)
        val nameLength = buffer.short.toInt() and 0xffff
        if (nameLength !in 1..MAX_NAME_BYTES || nameLength > buffer.remaining()) {
            throw TransferProtocolException("Invalid manifest file-name length")
        }
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)

        if (buffer.remaining() < 2) {
            throw TransferProtocolException("Truncated manifest MIME length")
        }
        val mimeLength = buffer.short.toInt() and 0xffff
        if (mimeLength !in 1..MAX_MIME_BYTES || mimeLength > buffer.remaining() - 8 - SHA256_BYTES) {
            throw TransferProtocolException("Invalid manifest MIME length")
        }
        val mimeBytes = ByteArray(mimeLength)
        buffer.get(mimeBytes)

        if (buffer.remaining() != 8 + SHA256_BYTES) {
            throw TransferProtocolException("Invalid manifest field layout")
        }
        val sizeBytes = buffer.long
        if (sizeBytes < 0L) {
            throw TransferProtocolException("Negative manifest file size")
        }
        val digest = ByteArray(SHA256_BYTES)
        buffer.get(digest)

        val rawName = decodeUtf8(nameBytes, "file name")
        val rawMimeType = decodeUtf8(mimeBytes, "MIME type")
        return TransferManifestItem(
            itemId = itemId,
            displayName = SafeTransferFileName.sanitize(rawName),
            mimeType = normalizeMimeType(rawMimeType),
            sizeBytes = sizeBytes,
            sha256Hex = digest.toLowerHex(),
        )
    }

    fun normalizeMimeType(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return normalized.takeIf(mimeTypePattern::matches) ?: "application/octet-stream"
    }

    private fun decodeSha256(value: String): ByteArray {
        require(value.length == SHA256_BYTES * 2 && value.all(::isHexDigit)) {
            "SHA-256 must be exactly 64 hexadecimal characters"
        }
        return ByteArray(SHA256_BYTES) { index ->
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }

    private fun isHexDigit(character: Char): Boolean =
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

    private fun decodeUtf8(bytes: ByteArray, fieldName: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (exception: CharacterCodingException) {
        throw TransferProtocolException("Malformed UTF-8 in manifest $fieldName", exception)
    }
}

object SafeTransferFileName {
    /**
     * Sanitizes an untrusted display name before passing it to a destination provider.
     * The result is a single bounded name, never a path.
     */
    fun sanitize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        val result = StringBuilder()
        var byteCount = 0
        var offset = 0

        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            val safeCodePoint = when {
                codePoint == '/'.code || codePoint == '\\'.code || codePoint == ':'.code -> '_'.code
                Character.isISOControl(codePoint) ||
                    Character.getType(codePoint) == Character.SURROGATE ||
                    isBidiOverride(codePoint) -> '_'.code
                else -> codePoint
            }
            val fragment = String(Character.toChars(safeCodePoint))
            val fragmentBytes = fragment.toByteArray(StandardCharsets.UTF_8).size
            if (byteCount + fragmentBytes > TransferManifestCodec.MAX_NAME_BYTES) break
            result.appendCodePoint(safeCodePoint)
            byteCount += fragmentBytes
            offset += Character.charCount(codePoint)
        }

        val safeName = result.toString().trim()
        return safeName.takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "received-file"
    }

    private fun isBidiOverride(codePoint: Int): Boolean =
        codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069
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