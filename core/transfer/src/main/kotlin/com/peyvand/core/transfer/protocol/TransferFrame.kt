package com.peyvand.core.transfer.protocol

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class TransferFrameType(
    val wireCode: Int,
    internal val maximumPayloadBytes: Int,
    internal val minimumPayloadBytes: Int = 0,
) {
    MANIFEST(1, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES),
    ACCEPT(2, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES),
    REJECT(3, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES),
    CANCEL(4, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES),
    DATA_CHUNK(
        5,
        TransferFrameCodec.CHUNK_METADATA_BYTES + TransferFrameCodec.MAX_DATA_CHUNK_BYTES,
        TransferFrameCodec.CHUNK_METADATA_BYTES + 1,
    ),
    ITEM_COMPLETE(6, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES),
    ITEM_VERIFIED(7, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES),
    ERROR(8, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES),
    SESSION_CLOSE(9, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES),
    CHUNK_ACK(10, TransferFrameCodec.CHUNK_ACK_PAYLOAD_BYTES, TransferFrameCodec.CHUNK_ACK_PAYLOAD_BYTES),
    PAIRING_APPROVED(11, 0, 0);

    companion object {
        internal fun fromWireCode(code: Int): TransferFrameType =
            entries.firstOrNull { it.wireCode == code }
                ?: throw TransferProtocolException("Unknown frame type: " + code)
    }
}

class TransferProtocolException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class TransferFrame(
    val protocolVersion: Int,
    val type: TransferFrameType,
    val sessionId: UUID,
    payload: ByteArray,
) {
    private val content = payload.copyOf()

    val payloadSize: Int
        get() = content.size

    fun payloadBytes(): ByteArray = content.copyOf()

    fun writePayloadTo(output: OutputStream) {
        output.write(content)
    }

    override fun equals(other: Any?): Boolean =
        other is TransferFrame &&
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

object TransferFrameCodec {
    const val CURRENT_PROTOCOL_VERSION = 1
    const val HEADER_BYTES = TransferWireFrameCodec.HEADER_BYTES
    const val MAX_CONTROL_PAYLOAD_BYTES = TransferWireFrameCodec.MAX_CONTROL_PAYLOAD_BYTES
    const val MAX_DATA_CHUNK_BYTES = TransferWireFrameCodec.MAX_DATA_CHUNK_BYTES
    const val CHUNK_METADATA_BYTES = 24
    const val CHUNK_ACK_PAYLOAD_BYTES = 24

    fun write(frame: TransferFrame, output: OutputStream) {
        if (frame.protocolVersion != CURRENT_PROTOCOL_VERSION) {
            throw TransferProtocolException(
                "Unsupported protocol version: " + frame.protocolVersion,
            )
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

    fun read(input: InputStream): TransferFrame? {
        val wire = TransferWireFrameCodec.read(input) ?: return null
        if (wire.protocolVersion != CURRENT_PROTOCOL_VERSION) {
            throw TransferProtocolException("Unsupported protocol version: " + wire.protocolVersion)
        }
        return TransferFrame(
            protocolVersion = wire.protocolVersion,
            type = TransferFrameType.fromWireCode(wire.wireType),
            sessionId = wire.sessionId,
            payload = wire.payloadBytes(),
        )
    }
}

class TransferDataChunk(
    val itemId: UUID,
    val offsetBytes: Long,
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()

    init {
        require(offsetBytes >= 0L) { "Chunk offset cannot be negative" }
        require(content.isNotEmpty()) { "A data chunk cannot be empty" }
        require(content.size <= TransferFrameCodec.MAX_DATA_CHUNK_BYTES) {
            "Chunk exceeds the configured protocol bound"
        }
        require(offsetBytes <= Long.MAX_VALUE - content.size.toLong()) {
            "Chunk end offset overflows Long"
        }
    }

    val sizeBytes: Int
        get() = content.size

    fun bytes(): ByteArray = content.copyOf()
}

object TransferDataChunkCodec {
    fun encodePayload(chunk: TransferDataChunk): ByteArray {
        val bytes = chunk.bytes()
        val buffer = ByteBuffer.allocate(TransferFrameCodec.CHUNK_METADATA_BYTES + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(chunk.itemId.mostSignificantBits)
        buffer.putLong(chunk.itemId.leastSignificantBits)
        buffer.putLong(chunk.offsetBytes)
        buffer.put(bytes)
        return buffer.array()
    }

    @Throws(TransferProtocolException::class)
    fun decodePayload(payload: ByteArray): TransferDataChunk {
        if (payload.size <= TransferFrameCodec.CHUNK_METADATA_BYTES ||
            payload.size > TransferFrameCodec.CHUNK_METADATA_BYTES +
            TransferFrameCodec.MAX_DATA_CHUNK_BYTES
        ) {
            throw TransferProtocolException("Invalid data chunk payload length")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val itemId = UUID(buffer.long, buffer.long)
        val offset = buffer.long
        if (offset < 0L || offset > Long.MAX_VALUE - buffer.remaining().toLong()) {
            throw TransferProtocolException("Invalid data chunk offset")
        }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return try {
            TransferDataChunk(itemId, offset, bytes)
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid data chunk", exception)
        }
    }
}
