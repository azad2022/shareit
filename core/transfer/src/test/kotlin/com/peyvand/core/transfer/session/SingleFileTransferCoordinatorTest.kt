package com.peyvand.core.transfer.session

import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.domain.connection.AuthenticatedPeerChannelPurpose
import com.peyvand.core.domain.connection.P2pTcpChannelSnapshot
import com.peyvand.core.domain.connection.PeerConnectionSnapshot
import com.peyvand.core.domain.connection.PeerConnectivityRepository
import com.peyvand.core.domain.connection.SecureSessionSnapshot
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.domain.files.ShareCategory
import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import com.peyvand.core.domain.transfer.TransferFileSource
import com.peyvand.core.domain.transfer.TransferFileStorageRepository
import com.peyvand.core.domain.transfer.TransferSourceMetadata
import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFileTransferCoordinatorTest {

    @Test
    fun authenticatedSenderAndReceiverCompleteOneFileOnlyAfterConsentAndPublication() = runBlocking {
        val sessionId = UUID.randomUUID()
        val fileBytes = ByteArray(900_123) { index ->
            ((index * 73) xor (index ushr 4)).toByte()
        }
        val item = ShareItem(
            id = "document:content://source/item-1",
            category = ShareCategory.FILES,
            displayName = "large-test-payload.bin",
            mimeType = "application/octet-stream",
            sizeBytes = fileBytes.size.toLong(),
            modifiedAtEpochMillis = null,
            source = ShareSource.ContentUri("content://source/item-1"),
        )

        val senderInput = PipedInputStream(512 * 1024)
        val receiverOutput = PipedOutputStream(senderInput)
        val receiverInput = PipedInputStream(512 * 1024)
        val senderOutput = PipedOutputStream(receiverInput)

        val senderChannel = TestAuthenticatedPeerChannel(
            sessionId = sessionId,
            inputStream = senderInput,
            outputStream = senderOutput,
        )
        val receiverChannel = TestAuthenticatedPeerChannel(
            sessionId = sessionId,
            inputStream = receiverInput,
            outputStream = receiverOutput,
        )
        val senderConnectivity = TestPeerConnectivityRepository(senderChannel)
        val receiverConnectivity = TestPeerConnectivityRepository(receiverChannel)
        val senderStorage = InMemoryTransferStorageRepository(sourceBytes = fileBytes)
        val receiverStorage = InMemoryTransferStorageRepository(expectedPublishedBytes = fileBytes)
        val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sender = SingleFileTransferCoordinator(
            scope = senderScope,
            connectivityRepository = senderConnectivity,
            storageRepository = senderStorage,
        )
        val receiver = SingleFileTransferCoordinator(
            scope = receiverScope,
            connectivityRepository = receiverConnectivity,
            storageRepository = receiverStorage,
        )

        try {
            sender.prepareSession(isSender = true, selectedItems = listOf(item))
            receiver.prepareSession(isSender = false, selectedItems = emptyList())

            assertEquals(TransferSessionPhase.READY_TO_SEND, sender.state.value.phase)
            assertEquals(TransferSessionPhase.WAITING_FOR_INCOMING, receiver.state.value.phase)

            sender.startSending()
            withTimeout(10_000L) {
                receiver.state.first { it.phase == TransferSessionPhase.WAITING_FOR_CONSENT }
            }

            // No file bytes may flow until the receiver makes this explicit decision.
            receiver.acceptIncoming()
            withTimeout(10_000L) {
                receiver.state.first { it.phase == TransferSessionPhase.COMPLETED }
            }
            assertTrue(
                sender.state.value.phase in setOf(
                    TransferSessionPhase.VERIFYING,
                    TransferSessionPhase.COMPLETED,
                ),
            )
            withTimeout(10_000L) {
                sender.state.first { it.phase == TransferSessionPhase.COMPLETED }
            }

            assertArrayEquals(fileBytes, receiverStorage.publishedBytes)
            // Four bounded receive windows plus the final staging seal; the receiver
            // does not fsync once for every 64 KiB chunk.
            assertEquals(5, receiverStorage.stagingSyncCount)
            assertEquals(fileBytes.size.toLong(), sender.state.value.transferredBytes)
            assertEquals(fileBytes.size.toLong(), receiver.state.value.transferredBytes)
            withTimeout(10_000L) {
                while (!senderChannel.closed || !receiverChannel.closed) {
                    delay(1L)
                }
            }
            assertTrue(senderChannel.closed)
            assertTrue(receiverChannel.closed)
            assertFalse(senderConnectivity.disconnected)
            assertFalse(receiverConnectivity.disconnected)
        } finally {
            sender.close()
            receiver.close()
            senderScope.cancel()
            receiverScope.cancel()
        }
    }

    @Test
    fun cancellationClosesBlockingSourceStreamAndAuthenticatedChannel() = runBlocking {
        val sessionId = UUID.randomUUID()
        val acceptFrame = ByteArrayOutputStream().apply {
            TransferFrameCodec.write(
                TransferFrame(
                    protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                    type = TransferFrameType.ACCEPT,
                    sessionId = sessionId,
                    payload = byteArrayOf(),
                ),
                this,
            )
        }.toByteArray()
        val channel = TestAuthenticatedPeerChannel(
            sessionId = sessionId,
            inputStream = ByteArrayInputStream(acceptFrame),
            outputStream = ByteArrayOutputStream(),
        )
        val connectivity = TestPeerConnectivityRepository(channel)
        val blockingStream = BlockingSourceInputStream()
        val storage = InMemoryTransferStorageRepository(
            sourceBytes = byteArrayOf(0x01),
            sourceStreamFactory = { blockingStream },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = SingleFileTransferCoordinator(
            scope = scope,
            connectivityRepository = connectivity,
            storageRepository = storage,
        )
        val item = ShareItem(
            id = "document:content://source/cancellable",
            category = ShareCategory.FILES,
            displayName = "cancel-test.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 1L,
            modifiedAtEpochMillis = null,
            source = ShareSource.ContentUri("content://source/cancellable"),
        )

        try {
            coordinator.prepareSession(isSender = true, selectedItems = listOf(item))
            coordinator.startSending()
            withTimeout(10_000L) {
                while (!storage.sourceStreamOpened || !blockingStream.readStarted) delay(1L)
            }

            coordinator.cancelTransfer()

            withTimeout(10_000L) {
                while (!storage.sourceStreamClosed || !channel.closed) delay(1L)
            }
            assertTrue(blockingStream.isClosed)
            assertTrue(channel.closed)
            assertFalse(connectivity.disconnected)
            assertEquals(TransferSessionPhase.CANCELLED, coordinator.state.value.phase)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    private class TestPeerConnectivityRepository(
        private val channel: AuthenticatedPeerChannel,
    ) : PeerConnectivityRepository {
        private val connectionState = MutableStateFlow(
            PeerConnectionSnapshot(
                status = com.peyvand.core.domain.connection.PeerConnectionStatus.GROUP_FORMED,
                locallyInitiated = true,
            ),
        )
        private val tcpState = MutableStateFlow(
            P2pTcpChannelSnapshot(
                status = com.peyvand.core.domain.connection.P2pTcpChannelStatus.CONNECTED,
            ),
        )
        private val sessionState = MutableStateFlow(
            SecureSessionSnapshot(status = SecureSessionStatus.AUTHENTICATED),
        )
        private var claimed = false

        @Volatile
        var disconnected: Boolean = false
            private set

        override val connection: StateFlow<PeerConnectionSnapshot> = connectionState
        override val tcpChannel: StateFlow<P2pTcpChannelSnapshot> = tcpState
        override val secureSession: StateFlow<SecureSessionSnapshot> = sessionState

        override fun connectToPeer(peerId: String) = Unit

        override fun disconnect() {
            disconnected = true
            runCatching { channel.close() }
        }

        override fun approvePairing() = Unit

        override fun rejectPairing() = Unit

        override fun claimAuthenticatedChannel(
            purpose: AuthenticatedPeerChannelPurpose,
        ): AuthenticatedPeerChannel? {
            if (purpose != AuthenticatedPeerChannelPurpose.FILE_TRANSFER || claimed) return null
            claimed = true
            return channel
        }
    }

    private class TestAuthenticatedPeerChannel(
        override val sessionId: UUID,
        override val inputStream: InputStream,
        override val outputStream: OutputStream,
    ) : AuthenticatedPeerChannel {
        @Volatile
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            runCatching { inputStream.close() }
            runCatching { outputStream.close() }
        }
    }

    private class InMemoryTransferStorageRepository(
        private val sourceBytes: ByteArray? = null,
        private val expectedPublishedBytes: ByteArray? = null,
        private val sourceStreamFactory: (() -> InputStream)? = null,
    ) : TransferFileStorageRepository {
        @Volatile
        var publishedBytes: ByteArray? = null
            private set

        @Volatile
        var sourceStreamOpened: Boolean = false
            private set

        @Volatile
        var sourceStreamClosed: Boolean = false
            private set

        @Volatile
        var stagingSyncCount: Int = 0
            private set

        override suspend fun openSource(
            item: ShareItem,
        ): TransferFileSource {
            val bytes = sourceBytes ?: error("No sender source was configured")
            return object : TransferFileSource {
                override val metadata = TransferSourceMetadata(
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    sizeBytes = bytes.size.toLong(),
                )

                private var opened = false

                override fun openStream(): InputStream {
                    check(!opened) { "The test source may be opened only once" }
                    opened = true
                    sourceStreamOpened = true
                    val stream = sourceStreamFactory?.invoke() ?: ByteArrayInputStream(bytes)
                    return object : FilterInputStream(stream) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                sourceStreamClosed = true
                            }
                        }
                    }
                }

                override fun close() = Unit
            }
        }

        override suspend fun maximumReceiveSizeBytes(): Long = 2L * 1024L * 1024L

        override suspend fun createPrivateStaging(
            maximumSizeBytes: Long,
        ): TransferStagingSink = MemoryTransferStagingSink(maximumSizeBytes) {
            stagingSyncCount += 1
        }

        override suspend fun publishVerified(
            staging: TransferStagingSink,
            destinationUri: String,
            displayName: String,
            mimeType: String,
            expectedSizeBytes: Long,
            expectedSha256Hex: String,
        ): Boolean {
            require(destinationUri.startsWith("content://"))
            val actualBytes = staging.openVerifiedInputStream().use { it.readBytes() }
            val expectedBytes = expectedPublishedBytes ?: error("No receiver expectation was configured")
            check(actualBytes.contentEquals(expectedBytes)) { "Published bytes differ from source bytes" }
            check(actualBytes.size.toLong() == expectedSizeBytes) { "Published byte count is incorrect" }
            check(sha256Hex(actualBytes) == expectedSha256Hex) { "Published SHA-256 is incorrect" }
            publishedBytes = actualBytes
            staging.delete()
            return true
        }

        override suspend fun publishVerifiedToDefaultDestination(
            staging: TransferStagingSink,
            displayName: String,
            mimeType: String,
            expectedSizeBytes: Long,
            expectedSha256Hex: String,
        ): Boolean = publishVerified(
            staging = staging,
            destinationUri = "content://peyvand/default-test-destination",
            displayName = displayName,
            mimeType = mimeType,
            expectedSizeBytes = expectedSizeBytes,
            expectedSha256Hex = expectedSha256Hex,
        )
    }

    private class BlockingSourceInputStream : InputStream() {
        private val readStartedLatch = CountDownLatch(1)
        private val closedLatch = CountDownLatch(1)

        @Volatile
        var isClosed: Boolean = false
            private set

        val readStarted: Boolean
            get() = readStartedLatch.count == 0L

        override fun read(): Int {
            readStartedLatch.countDown()
            try {
                closedLatch.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Blocking source read was interrupted", interrupted)
            }
            throw IOException("Blocking source stream was closed")
        }

        override fun close() {
            isClosed = true
            closedLatch.countDown()
        }
    }

    private class MemoryTransferStagingSink(
        private val maximumSizeBytes: Long,
        private val onSync: () -> Unit,
    ) : TransferStagingSink {
        private val output = ByteArrayOutputStream()
        private var sealed = false
        private var deleted = false

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!sealed && !deleted)
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            check(length.toLong() <= maximumSizeBytes - output.size().toLong())
            output.write(bytes, offset, length)
        }

        override fun sync() {
            check(!sealed && !deleted)
            onSync()
        }

        override fun seal() {
            sync()
            sealed = true
        }

        override fun openVerifiedInputStream(): InputStream {
            check(sealed && !deleted)
            return ByteArrayInputStream(output.toByteArray())
        }

        override fun delete() {
            deleted = true
            output.reset()
        }

        override fun close() = Unit
    }

    private companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
    }
}
