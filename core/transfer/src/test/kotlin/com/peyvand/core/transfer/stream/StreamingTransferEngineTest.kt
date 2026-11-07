package com.peyvand.core.transfer.stream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class StreamingTransferEngineTest {
    @Test
    fun `large arbitrary byte stream is copied with bounded buffer and matching digest`() = runBlocking {
        val sourceBytes = ByteArray(2 * 1024 * 1024 + 317).also {
            Random(42L).nextBytes(it)
        }
        val destination = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val engine = StreamingTransferEngine(bufferSizeBytes = 8 * 1024)

        val result = engine.copyAndDigest(
            source = ByteArrayInputStream(sourceBytes),
            destination = destination,
            expectedSizeBytes = sourceBytes.size.toLong(),
            maximumSizeBytes = sourceBytes.size.toLong(),
            onProgress = { progress.add(it) },
        )

        assertEquals(sourceBytes.size.toLong(), result.bytesTransferred)
        assertTrue(sourceBytes.contentEquals(destination.toByteArray()))
        assertEquals(sha256(sourceBytes), result.sha256Hex)
        assertEquals(sourceBytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> after > before })
    }

    @Test
    fun `zero byte file has the sha256 empty digest`() = runBlocking {
        val output = ByteArrayOutputStream()

        val result = StreamingTransferEngine().copyAndDigest(
            ByteArrayInputStream(byteArrayOf()), output, 0L, 0L,
        )

        assertEquals(0L, result.bytesTransferred)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            result.sha256Hex,
        )
        assertEquals(0, output.size())
    }

    @Test
    fun `short source stream is rejected`() {
        assertThrows(TransferSizeMismatchException::class.java) {
            runBlocking {
                StreamingTransferEngine().copyAndDigest(
                    ByteArrayInputStream(byteArrayOf(1, 2)),
                    ByteArrayOutputStream(),
                    expectedSizeBytes = 3L,
                    maximumSizeBytes = 3L,
                )
            }
        }
    }

    @Test
    fun `long source stream is rejected before writing bytes beyond manifest size`() = runBlocking {
        val output = ByteArrayOutputStream()
        assertThrows(TransferSizeMismatchException::class.java) {
            runBlocking {
                StreamingTransferEngine(bufferSizeBytes = 2).copyAndDigest(
                    ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                    output,
                    expectedSizeBytes = 3L,
                    maximumSizeBytes = 10L,
                )
            }
        }
        assertTrue(output.size() <= 3)
    }

    @Test
    fun `manifest larger than receiver limit is rejected before reading`() = runBlocking {
        val output = ByteArrayOutputStream()
        assertThrows(TransferSizeLimitException::class.java) {
            runBlocking {
                StreamingTransferEngine().copyAndDigest(
                    ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                    output,
                    expectedSizeBytes = 3L,
                    maximumSizeBytes = 2L,
                )
            }
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `buffer size outside hard bounds is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingTransferEngine(bufferSizeBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingTransferEngine(bufferSizeBytes = StreamingTransferEngine.MAX_BUFFER_SIZE_BYTES + 1)
        }
    }

    @Test
    fun `unknown-length source is streamed and measured at EOF`() = runBlocking {
        val source = byteArrayOf(5, 4, 3, 2, 1)
        val output = ByteArrayOutputStream()

        val result = StreamingTransferEngine(bufferSizeBytes = 2).copyAndDigest(
            source = ByteArrayInputStream(source),
            destination = output,
            expectedSizeBytes = null,
            maximumSizeBytes = 10L,
        )

        assertEquals(5L, result.bytesTransferred)
        assertTrue(source.contentEquals(output.toByteArray()))
        assertEquals(sha256(source), result.sha256Hex)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
