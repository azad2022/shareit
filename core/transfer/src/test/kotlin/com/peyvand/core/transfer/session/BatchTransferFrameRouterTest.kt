package com.peyvand.core.transfer.session

import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTransferFrameRouterTest {

    @Test
    fun interleavedOutboundAndInboundFramesAreRoutedToTheirOwnBatch() = runBlocking {
        val sessionId = UUID.randomUUID()
        val peerInput = PipedInputStream(64 * 1024)
        val peerOutput = PipedOutputStream(peerInput)
        val channel = TestChannel(sessionId, peerInput, ByteArrayOutputStream())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = CompletableDeferred<Throwable>()
        val offer = CompletableDeferred<Pair<RoutedBatchChannel, TransferFrame>>()
        val router = BatchTransferFrameRouter(
            channel = channel,
            scope = scope,
            onIncomingOffer = { route, frame -> offer.complete(route to frame) },
            onFailure = { failure.complete(it) },
        )
        try {
            router.start()
            val outboundBatch = UUID.randomUUID()
            val outboundRoute = router.registerOutgoing(outboundBatch)
            assertEquals(BatchRouteDirection.OUTGOING, outboundRoute.direction)
            assertTrue(runCatching { router.registerOutgoing(UUID.randomUUID()) }.isFailure)

            val inboundBatch = UUID.randomUUID()
            writePeerFrame(peerOutput, sessionId, TransferFrameType.BATCH_OFFER_ACCEPTED, outboundBatch, 16)
            writePeerFrame(peerOutput, sessionId, TransferFrameType.BATCH_OFFER, inboundBatch, 16)
            writePeerFrame(peerOutput, sessionId, TransferFrameType.BATCH_MANIFEST, inboundBatch, 18)
            peerOutput.flush()

            val accepted = withTimeout(TEST_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) { TransferFrameCodec.read(outboundRoute.inputStream) }
            }
            assertEquals(TransferFrameType.BATCH_OFFER_ACCEPTED, accepted?.type)

            val (inboundRoute, firstOffer) = withTimeout(TEST_TIMEOUT_MILLIS) { offer.await() }
            assertEquals(BatchRouteDirection.INCOMING, inboundRoute.direction)
            assertEquals(inboundBatch, inboundRoute.batchId)
            assertEquals(TransferFrameType.BATCH_OFFER, firstOffer.type)
            val manifest = withTimeout(TEST_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) { TransferFrameCodec.read(inboundRoute.inputStream) }
            }
            assertEquals(TransferFrameType.BATCH_MANIFEST, manifest?.type)
            assertFalse(failure.isCompleted)

            outboundRoute.close()
            inboundRoute.close()
        } finally {
            router.close()
            scope.cancel()
            runCatching { peerOutput.close() }
        }
    }

    @Test
    fun secondInboundBatchFailsClosedWhileOneInboundAndOneOutboundAreActive() = runBlocking {
        val sessionId = UUID.randomUUID()
        val peerInput = PipedInputStream(64 * 1024)
        val peerOutput = PipedOutputStream(peerInput)
        val channel = TestChannel(sessionId, peerInput, ByteArrayOutputStream())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = CompletableDeferred<Throwable>()
        val offer = CompletableDeferred<RoutedBatchChannel>()
        val router = BatchTransferFrameRouter(
            channel = channel,
            scope = scope,
            onIncomingOffer = { route, _ -> offer.complete(route) },
            onFailure = { failure.complete(it) },
        )
        try {
            router.start()
            router.registerOutgoing(UUID.randomUUID())
            writePeerFrame(peerOutput, sessionId, TransferFrameType.BATCH_OFFER, UUID.randomUUID(), 16)
            peerOutput.flush()
            offer.await()

            writePeerFrame(peerOutput, sessionId, TransferFrameType.BATCH_OFFER, UUID.randomUUID(), 16)
            peerOutput.flush()
            val actual = withTimeout(TEST_TIMEOUT_MILLIS) { failure.await() }
            assertTrue(actual is java.io.IOException)
            assertTrue(channel.isClosed.get())
        } finally {
            router.close()
            scope.cancel()
            runCatching { peerOutput.close() }
        }
    }

@Test
    fun replayedOfferIsRejectedAfterItsOriginalRouteCloses() = runBlocking {
        val sessionId = UUID.randomUUID()
        val peerInput = PipedInputStream(64 * 1024)
        val peerOutput = PipedOutputStream(peerInput)
        val channel = TestChannel(sessionId, peerInput, ByteArrayOutputStream())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = CompletableDeferred<Throwable>()
        val handled = CompletableDeferred<Unit>()
        val batchId = UUID.randomUUID()
        val router = BatchTransferFrameRouter(
            channel = channel,
            scope = scope,
            onIncomingOffer = { route, _ ->
                route.close()
                handled.complete(Unit)
            },
            onFailure = { failure.complete(it) },
        )
        try {
            router.start()
            writePeerFrame(peerOutput, sessionId, TransferFrameType.BATCH_OFFER, batchId, 16)
            peerOutput.flush()
            withTimeout(TEST_TIMEOUT_MILLIS) { handled.await() }

            // The first route is no longer active. Reusing its ID must still fail.
            writePeerFrame(peerOutput, sessionId, TransferFrameType.BATCH_OFFER, batchId, 16)
            peerOutput.flush()
            val actual = withTimeout(TEST_TIMEOUT_MILLIS) { failure.await() }

            assertTrue(actual is TransferProtocolException)
            assertTrue(channel.isClosed.get())
        } finally {
            router.close()
            scope.cancel()
            runCatching { peerOutput.close() }
        }
    }


    @Test
    fun frameForUnknownBatchFailsTheLogicalChannelClosed() = runBlocking {
        val sessionId = UUID.randomUUID()
        val peerInput = PipedInputStream(64 * 1024)
        val peerOutput = PipedOutputStream(peerInput)
        val channel = TestChannel(sessionId, peerInput, ByteArrayOutputStream())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = CompletableDeferred<Throwable>()
        val router = BatchTransferFrameRouter(
            channel = channel,
            scope = scope,
            onIncomingOffer = { _, _ -> },
            onFailure = { failure.complete(it) },
        )
        try {
            router.start()
            writePeerFrame(
                peerOutput,
                sessionId,
                TransferFrameType.BATCH_ITEM_START,
                UUID.randomUUID(),
                32,
            )
            peerOutput.flush()
            val actual = withTimeout(TEST_TIMEOUT_MILLIS) { failure.await() }
            assertTrue(actual is java.io.IOException)
            assertTrue(channel.isClosed.get())
        } finally {
            router.close()
            scope.cancel()
            runCatching { peerOutput.close() }
        }
    }

    private fun writePeerFrame(
        output: PipedOutputStream,
        sessionId: UUID,
        type: TransferFrameType,
        batchId: UUID,
        payloadSize: Int,
    ) {
        val payload = ByteBuffer.allocate(payloadSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(batchId.mostSignificantBits)
            .putLong(batchId.leastSignificantBits)
            .array()
        TransferFrameCodec.write(
            TransferFrame(
                protocolVersion = TransferFrameCodec.CHAT_ATTACHMENT_PROTOCOL_VERSION,
                type = type,
                sessionId = sessionId,
                payload = payload,
            ),
            output,
        )
    }

    private class TestChannel(
        override val sessionId: UUID,
        override val inputStream: PipedInputStream,
        override val outputStream: ByteArrayOutputStream,
    ) : AuthenticatedPeerChannel {
        val isClosed = AtomicBoolean(false)

        override fun close() {
            isClosed.set(true)
            inputStream.close()
            outputStream.close()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
    }
}
