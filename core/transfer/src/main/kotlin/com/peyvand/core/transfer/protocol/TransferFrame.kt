package com.peyvand.core.transfer.protocol

import java.io.DataOutputStream
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
    PAIRING_APPROVED(11, 0, 0),
    BATCH_MANIFEST(12, TransferFrameCodec.MAX_CONTROL_PAYLOAD_BYTES, 16 + 2),
    BATCH_DECISION(13, 16 + 32 + 4, 16 + 32 + 4),
    BATCH_ITEM_START(14, 32, 32),
    BATCH_ITEM_READY(15, 32, 32),
    BATCH_DATA_CHUNK(
        16,
        16 + 16 + 8 + TransferFrameCodec.MAX_DATA_CHUNK_BYTES,
        16 + 16 + 8 + 1,
    ),
    BATCH_CHUNK_ACK(17, 16 + 16 + 8, 16 + 16 + 8),
    BATCH_ITEM_COMPLETE(18, 16 + 16 + 8 + 32, 16 + 16 + 8 + 32),
    BATCH_ITEM_VERIFIED(19, 16 + 16 + 8 + 32, 16 + 16 + 8 + 32),
    BATCH_ITEM_FAILED(20, 33, 33),
    BATCH_ITEM_ABORT(21, 33, 33),
    BATCH_ITEM_FAILED_REQUEST(22, 33, 33),
    BATCH_ITEM_FAILED_ACK(23, 33, 33),
    BATCH_ITEM_CANCEL_REQUEST(24, 32, 32),
    BATCH_ITEM_CANCELLED(25, 32, 32),
    BATCH_ITEM_CANCELLED_ACK(26, 32, 32),
    BATCH_COMPLETE(27, 16 + 4 + 32, 16 + 4 + 32),
    BATCH_RECEIPT(28, 16 + 4 + 32, 16 + 4 + 32),
    BATCH_ABORT(29, 16, 16),
    BATCH_OFFER(30, 16, 16),
    BATCH_OFFER_ACCEPTED(31, 16, 16);

    companion object {
        internal fun fromWireCode(code: Int): TransferFrameType =
            entries.firstOrNull { it.wireCode == code }
                ?: throw TransferProtocolException("Unknown frame type: $code")
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

    internal fun writePayloadTo(output: OutputStream) {
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
    const val BATCH_PROTOCOL_VERSION = 3
    const val HEADER_BYTES = 26
    const val MAX_CONTROL_PAYLOAD_BYTES = 16 * 1024
    const val MAX_DATA_CHUNK_BYTES = 64 * 1024
    const val CHUNK_METADATA_BYTES = 24
    const val CHUNK_ACK_PAYLOAD_BYTES = 24

    private const val MAGIC = 0x50595644 // ASCII: PYVD

    /** Writes one complete frame without closing or flushing the caller-owned stream. */
    @Throws(TransferProtocolException::class)
    fun write(frame: TransferFrame, output: OutputStream) {
        validate(frame)
        val payload = frame.payloadBytes()
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeByte(frame.protocolVersion)
        data.writeByte(frame.type.wireCode)
        data.writeLong(frame.sessionId.mostSignificantBits)
        data.writeLong(frame.sessionId.leastSignificantBits)
        data.writeInt(payload.size)
        data.write(payload)
    }

    /**
     * Reads one frame. A null result means clean EOF before any header byte;
     * EOF inside a header or payload is a protocol error.
     */
    @Throws(TransferProtocolException::class)
    fun read(input: InputStream): TransferFrame? {
        val header = ByteArray(HEADER_BYTES)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readFully(input, header, 1, HEADER_BYTES - 1, "frame header")

        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (headerBuffer.int != MAGIC) {
            throw TransferProtocolException("Invalid frame magic")
        }

        val version = headerBuffer.get().toInt() and 0xff
        if (version !in CURRENT_PROTOCOL_VERSION..BATCH_PROTOCOL_VERSION) {
            throw TransferProtocolException("Unsupported protocol version: $version")
        }

        val frameType = TransferFrameType.fromWireCode(headerBuffer.get().toInt() and 0xff)
        validateTypeVersion(frameType, version)
        val sessionId = UUID(headerBuffer.long, headerBuffer.long)
        val payloadLength = headerBuffer.int
        validatePayloadLength(frameType, payloadLength)

        // The untrusted length is bounded and validated before this allocation.
        val payload = ByteArray(payloadLength)
        readFully(input, payload, 0, payloadLength, "frame payload")
        return TransferFrame(version, frameType, sessionId, payload)
    }

    private fun validate(frame: TransferFrame) {
        validateTypeVersion(frame.type, frame.protocolVersion)
        validatePayloadLength(frame.type, frame.payloadSize)
    }

    private fun validateTypeVersion(type: TransferFrameType, version: Int) {
        if (version !in CURRENT_PROTOCOL_VERSION..BATCH_PROTOCOL_VERSION) {
            throw TransferProtocolException("Unsupported protocol version: $version")
        }
        val isBatchFrame = type.wireCode >= TransferFrameType.BATCH_MANIFEST.wireCode
        val isBatchProtocol = version > CURRENT_PROTOCOL_VERSION
        if (isBatchFrame != isBatchProtocol) {
            throw TransferProtocolException(
                "Frame type ${type.name} is not valid for protocol version $version",
            )
        }
    }

    private fun validatePayloadLength(type: TransferFrameType, length: Int) {
        if (length < type.minimumPayloadBytes || length > type.maximumPayloadBytes) {
            throw TransferProtocolException(
                "Invalid ${type.name} payload length: $length",
            )
        }
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
                count < 0 -> throw TransferProtocolException("Truncated $partName")
                count == 0 -> {
                    val single = input.read()
                    if (single < 0) {
                        throw TransferProtocolException("Truncated $partName")
                    }
                    target[offset++] = single.toByte()
                }
                else -> offset += count
            }
        }
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