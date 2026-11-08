package com.peyvand.core.transfer.session

import com.peyvand.core.domain.transfer.TransferFileSource
import com.peyvand.core.domain.transfer.TransferSourceMetadata
import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.manifest.BatchTransferManifest
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.BatchItemOutcomeStatus
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.onAwait
import kotlinx.coroutines.selects.select
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
        val fourthBytes = "storage failure".toByteArray(Charsets.UTF_8)
        val fifthBytes = "pdf payload".toByteArray(Charsets.UTF_8)
        val batchId = UUID.randomUUID()
        val items = listOf(
            manifest("large.bin", "application/octet-stream", firstBytes.size.toLong()),
            manifest("empty", "application/octet-stream", 0L),
            manifest("یادداشت.txt", "text/plain", thirdBytes.size.toLong()),
            manifest("failure.bin", "application/octet-stream", fourthBytes.size.toLong()),
            manifest("گزارش نهایی.pdf", "application/pdf", fifthBytes.size.toLong()),
        )
        val manifest = BatchTransferManifest(batchId, items)
        val sourceBytes = mapOf(
            items[0].itemId to firstBytes,
            items[1].itemId to secondBytes,
            items[2].itemId to thirdBytes,
            items[3].itemId to fourthBytes,
            items[4].itemId to fifthBytes,
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
                    firstOfferFrame = requireNotNull(TransferFrameCodec.read(receiverInput)) {
                        "Sender did not send the initial batch offer"
                    },
                    acquireOfferConsent = { offer -> offer.batchId == batchId },
                    channelInput = receiverInput,
                    channelOutput = receiverOutput,
                    acquireSelection = {
                        BatchReceiverSelection(
                            acceptedItemIds = setOf(items[0].itemId, items[1].itemId, items[2].itemId, items[3].itemId),
                            destinationTreeUri = "content://tree/peyvand",
                        )
                    },
                    acquireStaging = { item ->
                        if (item.itemId == items[3].itemId) {
                            throw IOException("Injected item-local staging failure")
                        }
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

            val (sent, receiveValue) = awaitSenderAndReceiver(sending, receiving, 15_000L)
            val receivedResult = requireNotNull(receiveValue) {
                "Receiver completed without returning a batch result"
            }

            assertEquals(3, sent.completedCount)
            assertEquals(1, sent.rejectedCount)
            assertEquals(1, sent.failedCount)
            assertEquals(sent.outcomes, receivedResult.outcomes)
            assertTrue(sent.outcomes.all { it.status in setOf(
                BatchItemOutcomeStatus.COMPLETED,
                BatchItemOutcomeStatus.FAILED,
                BatchItemOutcomeStatus.REJECTED,
            ) })
            assertArrayEquals(firstBytes, received[items[0].itemId])
            assertArrayEquals(secondBytes, received[items[1].itemId])
            assertArrayEquals(thirdBytes, received[items[2].itemId])
            assertTrue(items[3].itemId !in received)
            assertTrue(items[4].itemId !in received)
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


    @Test
    fun decliningMetadataOfferDoesNotRequestOrSendTheManifest() = runBlocking {
        val bytes = "must not be opened".toByteArray(Charsets.UTF_8)
        val item = manifest("private-name.txt", "text/plain", bytes.size.toLong())
        val manifest = BatchTransferManifest(UUID.randomUUID(), listOf(
            item,
            manifest("second.bin", "application/octet-stream", 0L),
        ))
        val senderInput = PipedInputStream(4096)
        val receiverOutput = PipedOutputStream(senderInput)
        val receiverInput = PipedInputStream(4096)
        val senderOutput = PipedOutputStream(receiverInput)
        val senderCancellation = Channel<UUID>(Channel.UNLIMITED)
        val receiverCancellation = Channel<UUID>(Channel.UNLIMITED)
        var sourceOpened = false

        try {
            val sender = async(Dispatchers.IO) {
                MultiItemTransferProtocol(1024 * 1024L).sendBatch(
                    sessionId = manifest.batchId,
                    manifest = manifest,
                    channelInput = senderInput,
                    channelOutput = senderOutput,
                    openSource = {
                        sourceOpened = true
                        TestSource(it, bytes)
                    },
                    cancellationRequests = senderCancellation,
                )
            }
            val receiver = async(Dispatchers.IO) {
                MultiItemTransferProtocol(1024 * 1024L).receiveBatch(
                    sessionId = manifest.batchId,
                    firstOfferFrame = requireNotNull(TransferFrameCodec.read(receiverInput)) {
                        "Sender did not send the initial batch offer"
                    },
                    channelInput = receiverInput,
                    channelOutput = receiverOutput,
                    acquireOfferConsent = { false },
                    acquireSelection = { error("Manifest must remain undisclosed") },
                    acquireStaging = { error("No staging must be created") },
                    publishVerified = { _, _, _, _ -> error("No publication must occur") },
                    cancellationRequests = receiverCancellation,
                )
            }

            val (sendResult, receiveResult) = awaitSenderAndReceiver(sender, receiver, 10_000L)
            assertEquals(manifest.items.size, sendResult.rejectedCount)
            assertEquals(null, receiveResult)
            assertTrue(!sourceOpened)
        } finally {
            senderCancellation.close()
            receiverCancellation.close()
            runCatching { senderInput.close() }
            runCatching { senderOutput.close() }
            runCatching { receiverInput.close() }
            runCatching { receiverOutput.close() }
        }
    }


    @Test
    fun cancellingTheActiveItemKeepsTheNextItemTransferable() = runBlocking {
        val firstBytes = ByteArray(3 * 1024 * 1024) { (it * 13).toByte() }
        val secondBytes = "second item remains valid".toByteArray(Charsets.UTF_8)
        val batchId = UUID.randomUUID()
        val items = listOf(
            manifest("large-first.bin", "application/octet-stream", firstBytes.size.toLong()),
            manifest("بعدی.txt", "text/plain", secondBytes.size.toLong()),
        )
        val manifest = BatchTransferManifest(batchId, items)
        val bytesById = mapOf(items[0].itemId to firstBytes, items[1].itemId to secondBytes)
        val senderInput = PipedInputStream(512 * 1024)
        val receiverOutput = PipedOutputStream(senderInput)
        val receiverInput = PipedInputStream(512 * 1024)
        val senderOutput = PipedOutputStream(receiverInput)
        val senderCancellation = Channel<UUID>(Channel.UNLIMITED)
        val receiverCancellation = Channel<UUID>(Channel.UNLIMITED)
        val cancellationRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val received = mutableMapOf<UUID, ByteArray>()

        try {
            val receiving = async(Dispatchers.IO) {
                MultiItemTransferProtocol(4L * 1024L * 1024L).receiveBatch(
                    sessionId = batchId,
                    firstOfferFrame = requireNotNull(TransferFrameCodec.read(receiverInput)) {
                        "Sender did not send the initial batch offer"
                    },
                    channelInput = receiverInput,
                    channelOutput = receiverOutput,
                    acquireOfferConsent = { true },
                    acquireSelection = {
                        BatchReceiverSelection(items.map { it.itemId }.toSet(), "content://tree/peyvand")
                    },
                    acquireStaging = { MemoryStage {} },
                    publishVerified = { item, staging, _, completion ->
                        val content = staging.openVerifiedInputStream().use { it.readBytes() }
                        assertEquals(item.itemId, completion.itemId)
                        received[item.itemId] = content
                        true
                    },
                    cancellationRequests = receiverCancellation,
                )
            }
            val sending = async(Dispatchers.IO) {
                MultiItemTransferProtocol(4L * 1024L * 1024L).sendBatch(
                    sessionId = batchId,
                    manifest = manifest,
                    channelInput = senderInput,
                    channelOutput = senderOutput,
                    openSource = { TestSource(it, requireNotNull(bytesById[it.itemId])) },
                    cancellationRequests = senderCancellation,
                    onItemProgress = { itemId, bytes ->
                        if (itemId == items[0].itemId && bytes > 0L &&
                            cancellationRequested.compareAndSet(false, true)
                        ) {
                            senderCancellation.trySend(itemId)
                        }
                    },
                )
            }

            val (sent, receiveValue) = awaitSenderAndReceiver(sending, receiving, 20_000L)
            val receivedResult = requireNotNull(receiveValue) {
                "Receiver completed without returning a cancellation result"
            }
            assertTrue(cancellationRequested.get())
            assertEquals(BatchItemOutcomeStatus.CANCELLED, sent.outcomes[0].status)
            assertEquals(BatchItemOutcomeStatus.COMPLETED, sent.outcomes[1].status)
            assertEquals(sent.outcomes, receivedResult.outcomes)
            assertTrue(items[0].itemId !in received)
            assertArrayEquals(secondBytes, received[items[1].itemId])
        } finally {
            senderCancellation.close()
            receiverCancellation.close()
            runCatching { senderInput.close() }
            runCatching { senderOutput.close() }
            runCatching { receiverInput.close() }
            runCatching { receiverOutput.close() }
        }
    }

    @Test
    fun destinationFailureIsScopedToItsItemAndNextItemCompletes() = runBlocking {
        val firstBytes = "destination must reject this item".toByteArray(Charsets.UTF_8)
        val secondBytes = "the next item can still be published".toByteArray(Charsets.UTF_8)
        val batchId = UUID.randomUUID()
        val items = listOf(
            manifest("first.txt", "text/plain", firstBytes.size.toLong()),
            manifest("second.txt", "text/plain", secondBytes.size.toLong()),
        )
        val manifest = BatchTransferManifest(batchId, items)
        val bytesById = mapOf(items[0].itemId to firstBytes, items[1].itemId to secondBytes)
        val senderInput = PipedInputStream(128 * 1024)
        val receiverOutput = PipedOutputStream(senderInput)
        val receiverInput = PipedInputStream(128 * 1024)
        val senderOutput = PipedOutputStream(receiverInput)
        val senderCancellation = Channel<UUID>(Channel.UNLIMITED)
        val receiverCancellation = Channel<UUID>(Channel.UNLIMITED)
        val published = mutableSetOf<UUID>()

        try {
            val receiving = async(Dispatchers.IO) {
                MultiItemTransferProtocol(1024 * 1024L).receiveBatch(
                    sessionId = batchId,
                    firstOfferFrame = requireNotNull(TransferFrameCodec.read(receiverInput)) {
                        "Sender did not send the initial batch offer"
                    },
                    channelInput = receiverInput,
                    channelOutput = receiverOutput,
                    acquireOfferConsent = { true },
                    acquireSelection = {
                        BatchReceiverSelection(items.map { it.itemId }.toSet(), "content://tree/peyvand")
                    },
                    acquireStaging = { MemoryStage {} },
                    publishVerified = { item, staging, _, _ ->
                        if (item.itemId == items[0].itemId) {
                            false
                        } else {
                            val content = staging.openVerifiedInputStream().use { it.readBytes() }
                            assertArrayEquals(secondBytes, content)
                            published += item.itemId
                            true
                        }
                    },
                    cancellationRequests = receiverCancellation,
                )
            }
            val sending = async(Dispatchers.IO) {
                MultiItemTransferProtocol(1024 * 1024L).sendBatch(
                    sessionId = batchId,
                    manifest = manifest,
                    channelInput = senderInput,
                    channelOutput = senderOutput,
                    openSource = { TestSource(it, requireNotNull(bytesById[it.itemId])) },
                    cancellationRequests = senderCancellation,
                )
            }

            val (sent, receiveValue) = awaitSenderAndReceiver(sending, receiving, 15_000L)
            val receivedResult = requireNotNull(receiveValue) {
                "Receiver completed without returning a destination-failure result"
            }
            assertEquals(BatchItemOutcomeStatus.FAILED, sent.outcomes[0].status)
            assertEquals(BatchItemOutcomeStatus.COMPLETED, sent.outcomes[1].status)
            assertEquals(sent.outcomes, receivedResult.outcomes)
            assertEquals(setOf(items[1].itemId), published)
        } finally {
            senderCancellation.close()
            receiverCancellation.close()
            runCatching { senderInput.close() }
            runCatching { senderOutput.close() }
            runCatching { receiverInput.close() }
            runCatching { receiverOutput.close() }
        }
    }


    private sealed interface TransferSideCompletion {
        data class Sender(val result: BatchTransferResult) : TransferSideCompletion
        data class Receiver(val result: BatchTransferResult?) : TransferSideCompletion
    }

    /**
     * Observe whichever side of the in-memory duplex stream completes first.
     * This prevents a receiver-side protocol exception from being hidden behind
     * a sender-only timeout in integration tests.
     */
    private suspend fun awaitSenderAndReceiver(
        sender: Deferred<BatchTransferResult>,
        receiver: Deferred<BatchTransferResult?>,
        timeoutMillis: Long,
    ): Pair<BatchTransferResult, BatchTransferResult?> {
        val first = withTimeout(timeoutMillis) {
            select<TransferSideCompletion> {
                sender.onAwait { TransferSideCompletion.Sender(it) }
                receiver.onAwait { TransferSideCompletion.Receiver(it) }
            }
        }
        return when (first) {
            is TransferSideCompletion.Sender -> first.result to withTimeout(timeoutMillis) {
                receiver.await()
            }
            is TransferSideCompletion.Receiver -> withTimeout(timeoutMillis) {
                sender.await()
            } to first.result
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
