package com.peyvand.core.transfer.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchTransferFrameCodecTest {
    private val sessionId = UUID.fromString("b88c5d56-e79b-4bd5-b82d-4e7a0ce2f013")

    @Test
    fun v2BatchOfferFrameRoundTripsUsingTheSharedEnvelope() {
        val frame = BatchTransferFrame(
            protocolVersion = BatchTransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            type = BatchTransferFrameType.BATCH_OFFER,
            sessionId = sessionId,
            payload = ByteArray(26) { it.toByte() },
        )
        val encoded = ByteArrayOutputStream().also {
            BatchTransferFrameCodec.write(frame, it)
        }.toByteArray()

        assertEquals(0x50, encoded[0].toInt() and 0xff)
        assertEquals(0x59, encoded[1].toInt() and 0xff)
        assertEquals(0x56, encoded[2].toInt() and 0xff)
        assertEquals(0x44, encoded[3].toInt() and 0xff)
        assertEquals(2, encoded[4].toInt() and 0xff)
        assertEquals(32, encoded[5].toInt() and 0xff)
        assertEquals(frame, BatchTransferFrameCodec.read(ByteArrayInputStream(encoded)))
    }

    @Test
    fun dispatcherRoutesBothVersionsWithoutLosingTheFirstFrame() {
        val v1 = TransferFrame(
            1,
            TransferFrameType.ACCEPT,
            sessionId,
            byteArrayOf(1, 2, 3),
        )
        val v2 = BatchTransferFrame(
            2,
            BatchTransferFrameType.BATCH_OFFER,
            sessionId,
            ByteArray(26),
        )
        val output = ByteArrayOutputStream()
        TransferFrameCodec.write(v1, output)
        BatchTransferFrameCodec.write(v2, output)
        val input = ByteArrayInputStream(output.toByteArray())

        val first = TransferProtocolFrameDispatcher.read(input)
        val second = TransferProtocolFrameDispatcher.read(input)

        assertEquals(DecodedTransferProtocolFrame.SingleFile(v1), first)
        assertEquals(DecodedTransferProtocolFrame.Batch(v2), second)
        assertNull(TransferProtocolFrameDispatcher.read(input))
    }

    @Test
    fun v1AndV2CodecsRejectTheOtherProtocolVersion() {
        val v1 = TransferFrame(1, TransferFrameType.ACCEPT, sessionId, byteArrayOf())
        val v2 = BatchTransferFrame(2, BatchTransferFrameType.BATCH_ACCEPT, sessionId, ByteArray(16))
        val encodedV1 = ByteArrayOutputStream().also { TransferFrameCodec.write(v1, it) }.toByteArray()
        val encodedV2 = ByteArrayOutputStream().also { BatchTransferFrameCodec.write(v2, it) }.toByteArray()

        assertThrows(TransferProtocolException::class.java) {
            BatchTransferFrameCodec.read(ByteArrayInputStream(encodedV1))
        }
        assertThrows(TransferProtocolException::class.java) {
            TransferFrameCodec.read(ByteArrayInputStream(encodedV2))
        }
    }

    @Test
    fun unknownV2TypeIsRejectedBeforePayloadRead() {
        val header = ByteBuffer.allocate(TransferFrameCodec.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0x50595644)
            .put(2.toByte())
            .put(31.toByte())
            .putLong(sessionId.mostSignificantBits)
            .putLong(sessionId.leastSignificantBits)
            .putInt(0)
            .array()

        assertThrows(TransferProtocolException::class.java) {
            BatchTransferFrameCodec.read(ByteArrayInputStream(header))
        }
    }

    @Test
    fun oversizedV2DataChunkIsRejectedBeforePayloadAllocation() {
        val maximum = BatchTransferFrameType.DATA_CHUNK.maximumPayloadBytes
        val header = ByteBuffer.allocate(TransferFrameCodec.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0x50595644)
            .put(2.toByte())
            .put(BatchTransferFrameType.DATA_CHUNK.wireCode.toByte())
            .putLong(sessionId.mostSignificantBits)
            .putLong(sessionId.leastSignificantBits)
            .putInt(maximum + 1)
            .array()

        assertThrows(TransferProtocolException::class.java) {
            BatchTransferFrameCodec.read(ByteArrayInputStream(header))
        }
    }

    @Test
    fun readerAcceptsFragmentedV2HeaderAndPayload() {
        val frame = BatchTransferFrame(
            2,
            BatchTransferFrameType.ITEM_ACCEPT,
            sessionId,
            ByteArray(44) { (it * 7).toByte() },
        )
        val encoded = ByteArrayOutputStream().also {
            BatchTransferFrameCodec.write(frame, it)
        }.toByteArray()
        val fragmented = object : ByteArrayInputStream(encoded) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                super.read(target, offset, minOf(length, 2))
        }

        assertEquals(frame, BatchTransferFrameCodec.read(fragmented))
    }
}
