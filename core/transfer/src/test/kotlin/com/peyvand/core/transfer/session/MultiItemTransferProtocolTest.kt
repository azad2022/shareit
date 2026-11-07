package com.peyvand.core.transfer.session

import com.peyvand.core.domain.transfer.TransferFileSource
import com.peyvand.core.domain.transfer.TransferSourceMetadata
import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.manifest.BatchTransferManifest
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.BatchItemOutcomeStatus
import com.peyvand.core.transfer.protocol.BatchOffer
import com.peyvand.core.transfer.protocol.BatchOfferCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiItemTransferProtocolTest {
    @Test
    fun transfersSelectedItemsSeriallyAndReturnsIndependentIntegrityReceipts() = runBlocking {
        val firstBytes = ByteArray(150_123) { (it * 17).toByte() }
        val secondBytes = byteArrayOf()
        val thirdBytes = "متن آزمایشی پیوند".toByteArray(Charsets.UTF_8)
        val fourthBytes = "pdf payload".toByteArray(Charsets.UTF_8)
        val batchId = UUID.randomUUID()
        val items = listOf(
            manifest("large.bin", "application/octet-stream", firstBytes.size.toLong()),
            manifest("empty", "application/octet-stream", 0L),
            manifest("یادداشت.txt", "text/plain", thirdBytes.size.toLong()),
            manifest("گزارش نهایی.pdf", "application/pdf", fourthBytes.size.toLong()),
        )
        val manifest = BatchTransferManifest(batchId, items)
        val sourceBytes = mapOf(
            items[0].itemId to firstBytes,
            items[1].itemId to secondBytes,
            items[2].itemId to thirdBytes,
            items[3].itemId to fourthBytes,
        )
        val senderInput = PipedInputStream(512 * 1024)
        val receiverOutput = PipedOutputStream(senderInput)
        val receiverInput = PipedInputStream(512 * 1024)
        val senderOutput = PipedOutputStream(receiverInput)
        val cancellation = Channel<UUID>(Channel.UNLIMITED)
        val receiverCancellation = Channel<UUID>(Channel.UNLIMITED)
        val sender = MultiItemTransferProtocol(2L * 1024L * 1024L)
        val receiver = MultiItemTransferProtocol(2L * 1024L * 1024L)
        val received = mutableMapOf<UUID, ByteArray>()
        var peakStages = 0
        var activeStages = 0

        try {
            val receiving = async(Dispatchers.IO) {
                receiver.receiveBatch(
                    sessionId = batchId,
                    firstOfferFrame = com.peyvand.core.transfer.protocol.TransferFrame(
                        2,
                        com.peyvand.core.transfer.protocol.TransferFrameType.BATCH_OFFER,
                        batchId,
                        BatchOfferCodec.encode(BatchOffer(batchId)),
                    ),
                    acquireOfferConsent = { offer -> offer.batchId == batchId },
                    channelInput = receiverInput,
                    channelOutput = receiverOutput,
                    acquireSelection = {
                        BatchReceiverSelection(
                            acceptedItemIds = setOf(items[0].itemId, items[1].itemId, items[2].itemId),
                            destinationTreeUri = "content://tree/peyvand",
                        )
                    },
                    acquireStaging = {
                        activeStages += 1
                        peakStages = maxOf(peakStages, activeStages)
                        MemoryStage { activeStages -= 1 }
                    },
                    publishVerified = { item, staging, treeUri, completion ->
                        assertEquals("content://tree/peyvand", treeUri)
                        val bytes = staging.openVerifiedInputStream().use { it.readBytes() }
                        assertEquals(item.itemId, completion.itemId)
                        assertEquals(completion.actualSizeBytes, bytes.size.toLong())
                        assertEquals(completion.sha256Hex, sha256(bytes))
                        received[item.itemId] = bytes
                        staging.delete()
                        true
                    },
                    cancellationRequests = receiverCancellation,
                )
            }

            val sending = async(Dispatchers.IO) {
                sender.sendBatch(
                    sessionId = batchId,
                    manifest = manifest,
                    channelInput = senderInput,
                    channelOutput = senderOutput,
                    openSource = { item ->
                        val bytes = requireNotNull(sourceBytes[item.itemId])
                        TestSource(item, bytes)
                    },
                    cancellationRequests = cancellation,
                )
            }

            val sent = withTimeout(15_000L) { sending.await() }
            val receivedResult = withTimeout(15_000L) { receiving.await() }

            assertEquals(3, sent.completedCount)
            assertEquals(1, sent.rejectedCount)
            assertEquals(0, sent.failedCount)
            assertEquals(sent.outcomes, receivedResult.outcomes)
            assertTrue(sent.outcomes.all { it.status in setOf(
                BatchItemOutcomeStatus.COMPLETED,
                BatchItemOutcomeStatus.REJECTED,
            ) })
            assertArrayEquals(firstBytes, received[items[0].itemId])
            assertArrayEquals(secondBytes, received[items[1].itemId])
            assertArrayEquals(thirdBytes, received[items[2].itemId])
            assertTrue(items[3].itemId !in received)
            assertEquals(1, peakStages)
            assertEquals(0, activeStages)
        } finally {
            cancellation.close()
            receiverCancellation.close()
            runCatching { senderInput.close() }
            runCatching { senderOutput.close() }
            runCatching { receiverInput.close() }
            runCatching { receiverOutput.close() }
        }
    }

    private fun manifest(name: String, mime: String, size: Long): TransferManifestItem =
        TransferManifestItem(UUID.randomUUID(), name, mime, size)

    private class TestSource(
        item: TransferManifestItem,
        private val bytes: ByteArray,
    ) : TransferFileSource {
        override val metadata = TransferSourceMetadata(
            displayName = item.displayName,
            mimeType = item.mimeType,
            sizeBytes = item.sizeBytes,
        )

        private var opened = false

        override fun openStream(): InputStream {
            check(!opened)
            opened = true
            return ByteArrayInputStream(bytes)
        }

        override fun close() = Unit
    }

    private class MemoryStage(
        private val onClose: () -> Unit,
    ) : TransferStagingSink {
        private val bytes = ByteArrayOutputStream()
        private var sealed = false
        private var deleted = false

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!sealed && !deleted)
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            this.bytes.write(bytes, offset, length)
        }

        override fun sync() {
            check(!sealed && !deleted)
        }

        override fun seal() {
            if (sealed) return
            sync()
            sealed = true
        }

        override fun openVerifiedInputStream(): InputStream {
            check(sealed && !deleted)
            return ByteArrayInputStream(bytes.toByteArray())
        }

        override fun delete() {
            if (deleted) return
            deleted = true
            bytes.reset()
            onClose()
        }

        override fun close() {
            if (!deleted) {
                deleted = true
                onClose()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
