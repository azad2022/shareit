package com.peyvand.core.transfer.protocol

import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The v2-only message registry. Codes 32-49 are reserved by ADR-0008.
 * The common frame reader uses these bounds before allocating a payload.
 */
enum class BatchTransferFrameType(
    val wireCode: Int,
    internal val minimumPayloadBytes: Int,
    internal val maximumPayloadBytes: Int,
) {
    BATCH_OFFER(32, 26, 26),
    BATCH_ACCEPT(33, 16, 16),
    BATCH_REJECT(34, 17, 17),
    ITEM_MANIFEST(35, 50, 48 + 255 + 127),
    ITEM_ACCEPT(36, 44, 44),
    ITEM_REJECT(37, 37, 37),
    DATA_CHUNK(
        38,
        45,
        44 + TransferWireFrameCodec.MAX_DATA_CHUNK_BYTES,
    ),
    CHUNK_ACK(39, 44, 44),
    ITEM_COMPLETE(40, 76, 76),
    ITEM_VERIFIED(41, 76, 76),
    ITEM_ABORT_REQUEST(42, 46, 46),
    ITEM_ABORT(43, 46, 46),
    ITEM_ABORT_ACK(44, 46, 46),
    ITEM_TERMINAL_COMMIT(45, 46, 46),
    BATCH_CANCEL(46, 17, 17),
    BATCH_CANCEL_ACK(47, 17, 17),
    BATCH_COMPLETE(48, 26, 26),
    BATCH_COMPLETE_ACK(49, 26, 26),
    DESTINATION_READY(50, 16, 16);

    companion object {
        internal fun fromWireCode(code: Int): BatchTransferFrameType =
            entries.firstOrNull { it.wireCode == code }
                ?: throw TransferProtocolException("Unknown v2 batch frame type: " + code)
    }
}

/** Generic validated envelope used only between the version dispatcher and strict codecs. */
internal class TransferWireFrame(
    val protocolVersion: Int,
    val wireType: Int,
    val sessionId: UUID,
    payload: ByteArray,
) {
    private val content = payload.copyOf()

    val payloadSize: Int
        get() = content.size

    fun payloadBytes(): ByteArray = content.copyOf()
}

/**
 * Parses the shared 26-byte envelope once, dispatching payload bounds by protocol version.
 * The version/type registry is validated before allocating the untrusted payload length.
 */
internal object TransferWireFrameCodec {
    const val HEADER_BYTES = 26
    const val MAX_CONTROL_PAYLOAD_BYTES = 16 * 1024
    const val MAX_DATA_CHUNK_BYTES = 64 * 1024

    private const val MAGIC = 0x50595644 // ASCII: PYVD
    private const val V1 = 1
    private const val V2 = 2

    fun write(frame: TransferWireFrame, output: OutputStream) {
        val bounds = payloadBounds(frame.protocolVersion, frame.wireType)
        if (frame.payloadSize !in bounds.first..bounds.second) {
            throw TransferProtocolException("Invalid frame payload length: " + frame.payloadSize)
        }

        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeByte(frame.protocolVersion)
        data.writeByte(frame.wireType)
        data.writeLong(frame.sessionId.mostSignificantBits)
        data.writeLong(frame.sessionId.leastSignificantBits)
        data.writeInt(frame.payloadSize)
        data.write(frame.payloadBytes())
    }

    fun read(input: InputStream): TransferWireFrame? {
        val header = ByteArray(HEADER_BYTES)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readFully(input, header, 1, HEADER_BYTES - 1, "frame header")

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC) {
            throw TransferProtocolException("Invalid frame magic")
        }

        val version = buffer.get().toInt() and 0xff
        val wireType = buffer.get().toInt() and 0xff
        val sessionId = UUID(buffer.long, buffer.long)
        val payloadLength = buffer.int
        val bounds = payloadBounds(version, wireType)
        if (payloadLength !in bounds.first..bounds.second) {
            throw TransferProtocolException("Invalid frame payload length: " + payloadLength)
        }

        // Length is known to be valid for this exact version/type before allocation.
        val payload = ByteArray(payloadLength)
        readFully(input, payload, 0, payloadLength, "frame payload")
        return TransferWireFrame(version, wireType, sessionId, payload)
    }

    private fun payloadBounds(version: Int, wireType: Int): Pair<Int, Int> =
        when (version) {
            V1 -> {
                val type = TransferFrameType.fromWireCode(wireType)
                type.minimumPayloadBytes to type.maximumPayloadBytes
            }
            V2 -> {
                val type = BatchTransferFrameType.fromWireCode(wireType)
                type.minimumPayloadBytes to type.maximumPayloadBytes
            }
            else -> throw TransferProtocolException("Unsupported protocol version: " + version)
        }

    private fun readFully(
        input: InputStream,
        target: ByteArray,
        startOffset: Int,
        length: Int,
        partName: String,
    ) {
        var offset = startOffset
        val endOffset = startOffset + length
        while (offset < endOffset) {
            val count = input.read(target, offset, endOffset - offset)
            when {
                count < 0 -> throw TransferProtocolException("Truncated " + partName)
                count == 0 -> {
                    val single = input.read()
                    if (single < 0) throw TransferProtocolException("Truncated " + partName)
                    target[offset++] = single.toByte()
                }
                else -> offset += count
            }
        }
    }
}

class BatchTransferFrame(
    val protocolVersion: Int,
    val type: BatchTransferFrameType,
    val sessionId: UUID,
    payload: ByteArray,
) {
    private val content = payload.copyOf()

    val payloadSize: Int
        get() = content.size

    fun payloadBytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BatchTransferFrame &&
            protocolVersion == other.protocolVersion &&
            type == other.type &&
            sessionId == other.sessionId &&
            content.contentEquals(other.content)

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + type.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

object BatchTransferFrameCodec {
    const val CURRENT_PROTOCOL_VERSION = 2
    const val MAX_DATA_CHUNK_BYTES = TransferWireFrameCodec.MAX_DATA_CHUNK_BYTES

    fun write(frame: BatchTransferFrame, output: OutputStream) {
        if (frame.protocolVersion != CURRENT_PROTOCOL_VERSION) {
            throw TransferProtocolException("Unsupported batch protocol version: " + frame.protocolVersion)
        }
        TransferWireFrameCodec.write(
            TransferWireFrame(
                protocolVersion = frame.protocolVersion,
                wireType = frame.type.wireCode,
                sessionId = frame.sessionId,
                payload = frame.payloadBytes(),
            ),
            output,
        )
    }

    fun read(input: InputStream): BatchTransferFrame? {
        val wire = TransferWireFrameCodec.read(input) ?: return null
        if (wire.protocolVersion != CURRENT_PROTOCOL_VERSION) {
            throw TransferProtocolException("Expected batch protocol version 2, received " + wire.protocolVersion)
        }
        val type = BatchTransferFrameType.fromWireCode(wire.wireType)
        return BatchTransferFrame(
            protocolVersion = wire.protocolVersion,
            type = type,
            sessionId = wire.sessionId,
            payload = wire.payloadBytes(),
        )
    }
}

sealed interface DecodedTransferProtocolFrame {
    data class SingleFile(val frame: TransferFrame) : DecodedTransferProtocolFrame
    data class Batch(val frame: BatchTransferFrame) : DecodedTransferProtocolFrame
}

/**
 * Reads the first frame of a newly claimed authenticated channel without consuming it
 * through the wrong protocol codec. The selected version is explicit; there is no downgrade.
 */
object TransferProtocolFrameDispatcher {
    fun read(input: InputStream): DecodedTransferProtocolFrame? {
        val wire = TransferWireFrameCodec.read(input) ?: return null
        return when (wire.protocolVersion) {
            1 -> DecodedTransferProtocolFrame.SingleFile(
                TransferFrame(
                    protocolVersion = wire.protocolVersion,
                    type = TransferFrameType.fromWireCode(wire.wireType),
                    sessionId = wire.sessionId,
                    payload = wire.payloadBytes(),
                ),
            )
            2 -> DecodedTransferProtocolFrame.Batch(
                BatchTransferFrame(
                    protocolVersion = wire.protocolVersion,
                    type = BatchTransferFrameType.fromWireCode(wire.wireType),
                    sessionId = wire.sessionId,
                    payload = wire.payloadBytes(),
                ),
            )
            else -> throw TransferProtocolException("Unsupported protocol version: " + wire.protocolVersion)
        }
    }
}
