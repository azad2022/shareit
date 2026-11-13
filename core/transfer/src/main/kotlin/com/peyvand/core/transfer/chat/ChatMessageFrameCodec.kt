package com.peyvand.core.transfer.chat

import com.peyvand.core.domain.chat.ChatMessageId
import com.peyvand.core.domain.chat.ChatMessageRules
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Bounded wire representation for one-to-one chat control messages.
 * File bytes are never encoded by this codec.
 */
data class ChatWireMessage(
    val messageId: ChatMessageId,
    val senderSequence: Long,
    val observedPeerSequence: Long,
    val createdAtEpochMillis: Long,
    val body: String,
)

data class ChatWireAcknowledgement(
    val messageId: ChatMessageId,
    val senderSequence: Long,
)

class ChatWireProtocolException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

object ChatMessageFrameCodec {
    const val MESSAGE_METADATA_BYTES = 16 + 8 + 8 + 8 + 4
    const val ACKNOWLEDGEMENT_BYTES = 16 + 8

    fun encodeMessage(sessionId: UUID, message: ChatWireMessage): TransferFrame {
        val messageUuid = canonicalUuid(message.messageId.value)
        require(message.senderSequence > 0L) { "Chat sender sequence must be positive." }
        require(message.observedPeerSequence >= 0L) { "Observed peer sequence cannot be negative." }
        require(message.createdAtEpochMillis >= 0L) { "Chat timestamp cannot be negative." }
        ChatMessageRules.validateBody(message.body)

        val bodyBytes = message.body.toByteArray(StandardCharsets.UTF_8)
        require(bodyBytes.isNotEmpty() && bodyBytes.size <= ChatMessageRules.MAX_MESSAGE_UTF8_BYTES) {
            "Chat message body exceeds the protocol limit."
        }
        val payload = ByteBuffer.allocate(MESSAGE_METADATA_BYTES + bodyBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(messageUuid.mostSignificantBits)
            .putLong(messageUuid.leastSignificantBits)
            .putLong(message.senderSequence)
            .putLong(message.observedPeerSequence)
            .putLong(message.createdAtEpochMillis)
            .putInt(bodyBytes.size)
            .put(bodyBytes)
            .array()

        return TransferFrame(
            protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            type = TransferFrameType.CHAT_MESSAGE,
            sessionId = sessionId,
            payload = payload,
        )
    }

    fun decodeMessage(frame: TransferFrame): ChatWireMessage {
        requireChatFrame(frame, TransferFrameType.CHAT_MESSAGE)
        val payload = frame.payloadBytes()
        if (payload.size !in (MESSAGE_METADATA_BYTES + 1)..(
                MESSAGE_METADATA_BYTES + ChatMessageRules.MAX_MESSAGE_UTF8_BYTES
            )
        ) {
            throw ChatWireProtocolException("Invalid chat message payload length.")
        }

        try {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val messageId = ChatMessageId(UUID(buffer.long, buffer.long).toString())
            val senderSequence = buffer.long
            val observedPeerSequence = buffer.long
            val createdAtEpochMillis = buffer.long
            val bodyLength = buffer.int

            if (senderSequence <= 0L || observedPeerSequence < 0L || createdAtEpochMillis < 0L) {
                throw ChatWireProtocolException("Chat message metadata contains an invalid value.")
            }
            if (bodyLength !in 1..ChatMessageRules.MAX_MESSAGE_UTF8_BYTES ||
                buffer.remaining() != bodyLength
            ) {
                throw ChatWireProtocolException("Chat message body length does not match its frame.")
            }

            val bodyBytes = ByteArray(bodyLength)
            buffer.get(bodyBytes)
            val body = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bodyBytes))
                .toString()
            try {
                ChatMessageRules.validateBody(body)
            } catch (exception: IllegalArgumentException) {
                throw ChatWireProtocolException("Chat message body failed domain validation.", exception)
            }

            return ChatWireMessage(
                messageId = messageId,
                senderSequence = senderSequence,
                observedPeerSequence = observedPeerSequence,
                createdAtEpochMillis = createdAtEpochMillis,
                body = body,
            )
        } catch (exception: ChatWireProtocolException) {
            throw exception
        } catch (exception: Exception) {
            throw ChatWireProtocolException("Malformed chat message frame.", exception)
        }
    }

    fun encodeAcknowledgement(
        sessionId: UUID,
        acknowledgement: ChatWireAcknowledgement,
    ): TransferFrame {
        val messageUuid = canonicalUuid(acknowledgement.messageId.value)
        require(acknowledgement.senderSequence > 0L) {
            "Chat acknowledgement sequence must be positive."
        }
        val payload = ByteBuffer.allocate(ACKNOWLEDGEMENT_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(messageUuid.mostSignificantBits)
            .putLong(messageUuid.leastSignificantBits)
            .putLong(acknowledgement.senderSequence)
            .array()

        return TransferFrame(
            protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            type = TransferFrameType.CHAT_ACK,
            sessionId = sessionId,
            payload = payload,
        )
    }

    fun decodeAcknowledgement(frame: TransferFrame): ChatWireAcknowledgement {
        requireChatFrame(frame, TransferFrameType.CHAT_ACK)
        val payload = frame.payloadBytes()
        if (payload.size != ACKNOWLEDGEMENT_BYTES) {
            throw ChatWireProtocolException("Invalid chat acknowledgement payload length.")
        }
        return try {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val messageId = ChatMessageId(UUID(buffer.long, buffer.long).toString())
            val senderSequence = buffer.long
            if (senderSequence <= 0L) {
                throw ChatWireProtocolException("Chat acknowledgement sequence must be positive.")
            }
            ChatWireAcknowledgement(messageId, senderSequence)
        } catch (exception: ChatWireProtocolException) {
            throw exception
        } catch (exception: Exception) {
            throw ChatWireProtocolException("Malformed chat acknowledgement frame.", exception)
        }
    }

    private fun requireChatFrame(frame: TransferFrame, expectedType: TransferFrameType) {
        if (frame.type != expectedType) {
            throw ChatWireProtocolException("Expected $expectedType but received " + frame.type + ".")
        }
        if (frame.protocolVersion != TransferFrameCodec.CURRENT_PROTOCOL_VERSION) {
            throw ChatWireProtocolException(
                "Unsupported chat protocol version: " + frame.protocolVersion + ".",
            )
        }
    }

    private fun canonicalUuid(value: String): UUID {
        val uuid = try {
            UUID.fromString(value)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Chat message ID must be a canonical UUID.", exception)
        }
        require(uuid.toString() == value) { "Chat message ID must be a canonical lowercase UUID." }
        return uuid
    }
}
