package com.peyvand.core.transfer.stream

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest

data class TransferStreamResult(
    val bytesTransferred: Long,
    val sha256Hex: String,
)

open class TransferStreamException(message: String) : IOException(message)

class TransferSizeLimitException(message: String) : TransferStreamException(message)

class TransferSizeMismatchException(message: String) : TransferStreamException(message)

class StreamingTransferEngine(
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
) {
    init {
        require(bufferSizeBytes in 1..MAX_BUFFER_SIZE_BYTES) {
            "Buffer size must be between 1 and $MAX_BUFFER_SIZE_BYTES bytes"
        }
    }

    /**
     * Copies an exact-length stream using one bounded buffer and computes SHA-256.
     * The caller owns both streams and must stage output until verification succeeds.
     * The caller must also use finite source read timeouts for network streams.
     */
    suspend fun copyAndDigest(
        source: InputStream,
        destination: OutputStream,
        expectedSizeBytes: Long,
        maximumSizeBytes: Long,
        onProgress: (Long) -> Unit = {},
    ): TransferStreamResult {
        require(expectedSizeBytes >= 0L) { "Expected size cannot be negative" }
        require(maximumSizeBytes >= 0L) { "Maximum size cannot be negative" }
        if (expectedSizeBytes > maximumSizeBytes) {
            throw TransferSizeLimitException("Manifest size exceeds the receiver limit")
        }

        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSizeBytes)
        var totalBytes = 0L

        while (true) {
            context.ensureActive()
            var count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) {
                val single = source.read()
                if (single < 0) break
                buffer[0] = single.toByte()
                count = 1
            }
            context.ensureActive()

            val countLong = count.toLong()
            if (countLong > expectedSizeBytes - totalBytes) {
                throw TransferSizeMismatchException(
                    "Source stream is longer than the declared manifest size",
                )
            }
            if (countLong > maximumSizeBytes - totalBytes) {
                throw TransferSizeLimitException("Stream exceeds the configured receiver limit")
            }

            destination.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            totalBytes += countLong
            onProgress(totalBytes)
        }

        if (totalBytes != expectedSizeBytes) {
            throw TransferSizeMismatchException(
                "Received $totalBytes bytes; expected $expectedSizeBytes bytes",
            )
        }

        context.ensureActive()
        destination.flush()
        return TransferStreamResult(
            bytesTransferred = totalBytes,
            sha256Hex = digest.digest().toLowerHex(),
        )
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE_BYTES = 64 * 1024
        const val MAX_BUFFER_SIZE_BYTES = 256 * 1024
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