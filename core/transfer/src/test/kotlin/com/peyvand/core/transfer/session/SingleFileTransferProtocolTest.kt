package com.peyvand.core.transfer.session

import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.manifest.TransferManifestCodec
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.protocol.TransferDataChunk
import com.peyvand.core.transfer.protocol.TransferDataChunkCodec
import com.peyvand.core.transfer.protocol.TransferCompletionRecord
import com.peyvand.core.transfer.protocol.TransferCompletionCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFileTransferProtocolTest {
    @Test
    fun transfersArbitraryBytesOnlyAfterConsentAndReturnsVerifiedReceipt() = runBlocking {
        val sessionId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val bytes = ByteArray(730_123) { index -> ((index * 37) xor (index ushr 3)).toByte() }
        val manifest = TransferManifestItem(
            itemId = itemId,
            displayName = "دفترچه.bin",
            mimeType = "application/octet-stream",
            sizeBytes = bytes.size.toLong(),
        )
        val sink = MemoryStagingSink()
        val senderToReceiver = PipedInputStream(512 * 1024)
        val senderOutput = PipedOutputStream(senderToReceiver)
        val receiverToSender = PipedInputStream(128 * 1024)
        val receiverOutput = PipedOutputStream(receiverToSender)
        var publishCalled = false
        val senderProgress = AtomicLong(0L)
        val receiverProgress = AtomicLong(0L)
        val protocol = SingleFileTransferProtocol(maximumFileSizeBytes = 2 * 1024 * 1024L)

        val receiving = async(Dispatchers.IO) {
            protocol.receiveOne(
                sessionId = sessionId,
                channelInput = senderToReceiver,
                channelOutput = receiverOutput,
                acquireConsentAndStaging = { receivedManifest ->
                    assertEquals(manifest, receivedManifest)
                    sink
                },
                publishVerified = { receivedManifest, receipt, staged ->
                    assertEquals(manifest.itemId, receivedManifest.itemId)
                    assertEquals(bytes.size.toLong(), receipt.actualSizeBytes)
                    assertTrue(bytes.contentEquals(staged.openVerifiedInputStream().use { it.readBytes() }))
                    publishCalled = true
                    true
                },
                onProgress = receiverProgress::set,
            )
        }

        val sending = async(Dispatchers.IO) {
            protocol.sendOne(
                sessionId = sessionId,
                manifest = manifest,
                source = ByteArrayInputStream(bytes),
                channelInput = receiverToSender,
                channelOutput = senderOutput,
                onProgress = senderProgress::set,
            )
        }

        val receiverResult = receiving.await()
        val senderResult = sending.await()
        assertTrue(receiverResult is TransferSessionOutcome.Completed)
        assertTrue(senderResult is TransferSessionOutcome.Completed)
        assertTrue(publishCalled)
        assertEquals(bytes.size.toLong(), senderProgress.get())
        assertEquals(bytes.size.toLong(), receiverProgress.get())
        senderOutput.close()
        receiverOutput.close()
    }

    @Test
    fun receiverRejectionPreventsSourceReadAndDataFrames() = runBlocking {
        val sessionId = UUID.randomUUID()
        val manifest = TransferManifestItem(
            itemId = UUID.randomUUID(),
            displayName = "secret.dat",
            mimeType = "application/octet-stream",
            sizeBytes = 4L,
        )
        val senderToReceiver = PipedInputStream()
        val senderOutput = PipedOutputStream(senderToReceiver)
        val receiverToSender = PipedInputStream()
        val receiverOutput = PipedOutputStream(receiverToSender)
        var sourceReads = 0

        val protocol = SingleFileTransferProtocol(1024L)
        val receiving = async(Dispatchers.IO) {
            protocol.receiveOne(
                sessionId,
                senderToReceiver,
                receiverOutput,
                acquireConsentAndStaging = { null },
                publishVerified = { _, _, _ -> false },
            )
        }
        val sending = async(Dispatchers.IO) {
            protocol.sendOne(
                sessionId,
                manifest,
                object : InputStream() {
                    override fun read(): Int {
                        sourceReads += 1
                        return -1
                    }
                },
                receiverToSender,
                senderOutput,
            )
        }

        assertTrue(receiving.await() is TransferSessionOutcome.Rejected)
        assertTrue(sending.await() is TransferSessionOutcome.Rejected)
        assertEquals(0, sourceReads)
        senderOutput.close()
        receiverOutput.close()
    }

    @Test
    fun receiverRejectsUndersizedNonFinalChunkBeforeAdditionalDiskSyncs() = runBlocking {
        val sessionId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val manifest = TransferManifestItem(
            itemId = itemId,
            displayName = "malformed.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 2L,
        )
        val wire = ByteArrayOutputStream()
        TransferFrameCodec.write(
            TransferFrame(
                TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                TransferFrameType.MANIFEST,
                sessionId,
                TransferManifestCodec.encode(manifest),
            ),
            wire,
        )
        val firstChunk = TransferDataChunk(itemId, 0L, byteArrayOf(0x01))
        val secondChunk = TransferDataChunk(itemId, 1L, byteArrayOf(0x02))
        listOf(firstChunk, secondChunk).forEach { chunk ->
            TransferFrameCodec.write(
                TransferFrame(
                    TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                    TransferFrameType.DATA_CHUNK,
                    sessionId,
                    TransferDataChunkCodec.encodePayload(chunk),
                ),
                wire,
            )
        }
        val completion = TransferCompletionRecord(
            itemId = itemId,
            actualSizeBytes = 2L,
            sha256Hex = "0000000000000000000000000000000000000000000000000000000000000000",
        )
        TransferFrameCodec.write(
            TransferFrame(
                TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                TransferFrameType.ITEM_COMPLETE,
                sessionId,
                TransferCompletionCodec.encode(completion),
            ),
            wire,
        )

        val staging = MemoryStagingSink()
        val result = runCatching {
            SingleFileTransferProtocol(1024L).receiveOne(
                sessionId = sessionId,
                channelInput = ByteArrayInputStream(wire.toByteArray()),
                channelOutput = ByteArrayOutputStream(),
                acquireConsentAndStaging = { staging },
                publishVerified = { _, _, _ -> false },
            )
        }

        assertTrue(result.isFailure)
        assertTrue(staging.deleted)
        assertEquals(0, staging.syncCount)
    }

    @Test
    fun emptyFileProducesValidSha256Receipt() = runBlocking {
        val sessionId = UUID.randomUUID()
        val manifest = TransferManifestItem(
            itemId = UUID.randomUUID(),
            displayName = "empty",
            mimeType = "application/octet-stream",
            sizeBytes = 0L,
        )
        val senderToReceiver = PipedInputStream()
        val senderOutput = PipedOutputStream(senderToReceiver)
        val receiverToSender = PipedInputStream()
        val receiverOutput = PipedOutputStream(receiverToSender)
        val sink = MemoryStagingSink()
        val protocol = SingleFileTransferProtocol(1024L)

        val receiving = async(Dispatchers.IO) {
            protocol.receiveOne(
                sessionId,
                senderToReceiver,
                receiverOutput,
                acquireConsentAndStaging = { sink },
                publishVerified = { _, receipt, _ ->
                    assertEquals(
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        receipt.sha256Hex,
                    )
                    true
                },
            )
        }
        val sending = async(Dispatchers.IO) {
            protocol.sendOne(
                sessionId,
                manifest,
                ByteArrayInputStream(byteArrayOf()),
                receiverToSender,
                senderOutput,
            )
        }
        assertTrue(receiving.await() is TransferSessionOutcome.Completed)
        assertTrue(sending.await() is TransferSessionOutcome.Completed)
        senderOutput.close()
        receiverOutput.close()
    }

    private class MemoryStagingSink : TransferStagingSink {
        private val output = ByteArrayOutputStream()
        private var sealed = false
        var deleted = false
            private set
        var syncCount = 0
            private set

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!sealed)
            output.write(bytes, offset, length)
        }

        override fun sync() {
            check(!sealed && !deleted)
            syncCount += 1
        }

        override fun seal() {
            if (sealed) return
            sync()
            sealed = true
        }

        override fun openVerifiedInputStream(): InputStream {
            check(sealed)
            return ByteArrayInputStream(output.toByteArray())
        }

        override fun delete() {
            deleted = true
            output.reset()
        }

        override fun close() = Unit
    }
}
