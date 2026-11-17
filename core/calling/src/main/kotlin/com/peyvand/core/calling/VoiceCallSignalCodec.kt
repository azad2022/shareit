package com.peyvand.core.calling

import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Bounded call signalling frames over the authenticated CALL_CONTROL logical channel. */
object VoiceCallSignalCodec {
    const val MAX_SDP_BYTES = 8 * 1024
    const val MAX_CANDIDATE_BYTES = 2 * 1024
    const val MAX_MID_BYTES = 256
    const val MAX_USERNAME_FRAGMENT_BYTES = 256

    fun encode(sessionId: UUID, signal: VoiceCallSignal): TransferFrame {
        val (type, payload) = when (signal) {
            is VoiceCallSignal.Invite ->
                TransferFrameType.CALL_INVITE to encodeCallId(signal.callId)
            is VoiceCallSignal.Accept ->
                TransferFrameType.CALL_ACCEPT to encodeCallId(signal.callId)
            is VoiceCallSignal.Reject ->
                TransferFrameType.CALL_REJECT to encodeCallIdAndCode(signal.callId, signal.reason.wireCode)
            is VoiceCallSignal.Cancel ->
                TransferFrameType.CALL_CANCEL to encodeCallId(signal.callId)
            is VoiceCallSignal.End ->
                TransferFrameType.CALL_END to encodeCallIdAndCode(signal.callId, signal.cause.wireCode)
            is VoiceCallSignal.SdpOffer ->
                TransferFrameType.CALL_SDP_OFFER to encodeCallIdAndString(signal.callId, signal.sdp, MAX_SDP_BYTES)
            is VoiceCallSignal.SdpAnswer ->
                TransferFrameType.CALL_SDP_ANSWER to encodeCallIdAndString(signal.callId, signal.sdp, MAX_SDP_BYTES)
            is VoiceCallSignal.IceCandidate ->
                TransferFrameType.CALL_ICE_CANDIDATE to encodeIceCandidate(signal)
        }
        return TransferFrame(
            protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            type = type,
            sessionId = sessionId,
            payload = payload,
        )
    }

    @Throws(TransferProtocolException::class)
    fun decode(frame: TransferFrame, expectedSessionId: UUID): VoiceCallSignal {
        if (frame.sessionId != expectedSessionId) {
            throw TransferProtocolException("Voice call signal belongs to a different authenticated session.")
        }
        if (frame.protocolVersion != TransferFrameCodec.CURRENT_PROTOCOL_VERSION) {
            throw TransferProtocolException("Unsupported voice call control protocol version.")
        }
        val payload = frame.payloadBytes()
        return try {
            when (frame.type) {
                TransferFrameType.CALL_INVITE -> VoiceCallSignal.Invite(decodeCallId(payload))
                TransferFrameType.CALL_ACCEPT -> VoiceCallSignal.Accept(decodeCallId(payload))
                TransferFrameType.CALL_REJECT -> {
                    val (callId, code) = decodeCallIdAndCode(payload)
                    VoiceCallSignal.Reject(callId, VoiceCallRejectReason.fromWireCode(code))
                }
                TransferFrameType.CALL_CANCEL -> VoiceCallSignal.Cancel(decodeCallId(payload))
                TransferFrameType.CALL_END -> {
                    val (callId, code) = decodeCallIdAndCode(payload)
                    VoiceCallSignal.End(callId, VoiceCallEndCause.fromWireCode(code))
                }
                TransferFrameType.CALL_SDP_OFFER -> {
                    val (callId, sdp) = decodeCallIdAndString(payload, MAX_SDP_BYTES)
                    VoiceCallSignal.SdpOffer(callId, sdp)
                }
                TransferFrameType.CALL_SDP_ANSWER -> {
                    val (callId, sdp) = decodeCallIdAndString(payload, MAX_SDP_BYTES)
                    VoiceCallSignal.SdpAnswer(callId, sdp)
                }
                TransferFrameType.CALL_ICE_CANDIDATE -> decodeIceCandidate(payload)
                else -> throw TransferProtocolException("Frame is not a voice call control message.")
            }
        } catch (exception: CharacterCodingException) {
            throw TransferProtocolException("Voice call control contains invalid UTF-8.", exception)
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Malformed voice call control payload.", exception)
        }
    }

    private fun encodeCallId(callId: UUID): ByteArray =
        ByteBuffer.allocate(CALL_ID_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(callId.mostSignificantBits)
            .putLong(callId.leastSignificantBits)
            .array()

    private fun decodeCallId(payload: ByteArray): UUID {
        if (payload.size != CALL_ID_BYTES) {
            throw TransferProtocolException("Invalid call ID payload length.")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return UUID(buffer.long, buffer.long)
    }

    private fun encodeCallIdAndCode(callId: UUID, code: Int): ByteArray =
        ByteBuffer.allocate(CALL_ID_BYTES + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(callId.mostSignificantBits)
            .putLong(callId.leastSignificantBits)
            .put(code.toByte())
            .array()

    private fun decodeCallIdAndCode(payload: ByteArray): Pair<UUID, Int> {
        if (payload.size != CALL_ID_BYTES + 1) {
            throw TransferProtocolException("Invalid voice call reason payload length.")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return UUID(buffer.long, buffer.long) to (buffer.get().toInt() and 0xff)
    }

    private fun encodeCallIdAndString(callId: UUID, value: String, maximumBytes: Int): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(value.isNotBlank() && bytes.size <= maximumBytes)
        return ByteBuffer.allocate(CALL_ID_BYTES + SHORT_LENGTH_BYTES + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(callId.mostSignificantBits)
            .putLong(callId.leastSignificantBits)
            .putShort(bytes.size.toShort())
            .put(bytes)
            .array()
    }

    private fun decodeCallIdAndString(payload: ByteArray, maximumBytes: Int): Pair<UUID, String> {
        if (payload.size < CALL_ID_BYTES + SHORT_LENGTH_BYTES + 1) {
            throw TransferProtocolException("Truncated voice call text payload.")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val callId = UUID(buffer.long, buffer.long)
        val length = buffer.short.toInt() and 0xffff
        if (length !in 1..maximumBytes || length != buffer.remaining()) {
            throw TransferProtocolException("Invalid voice call text length.")
        }
        return callId to decodeUtf8(ByteArray(length).also(buffer::get))
    }

    private fun encodeIceCandidate(signal: VoiceCallSignal.IceCandidate): ByteArray {
        require(signal.sdpMLineIndex >= 0)
        val mid = signal.sdpMid.orEmpty().toByteArray(StandardCharsets.UTF_8)
        val candidate = signal.candidate.toByteArray(StandardCharsets.UTF_8)
        val username = signal.usernameFragment.orEmpty().toByteArray(StandardCharsets.UTF_8)
        require(candidate.isNotBlank())
        require(mid.size <= MAX_MID_BYTES)
        require(candidate.size <= MAX_CANDIDATE_BYTES)
        require(username.size <= MAX_USERNAME_FRAGMENT_BYTES)
        return ByteBuffer.allocate(
            CALL_ID_BYTES + INT_BYTES +
                SHORT_LENGTH_BYTES + mid.size +
                SHORT_LENGTH_BYTES + candidate.size +
                SHORT_LENGTH_BYTES + username.size,
        ).order(ByteOrder.BIG_ENDIAN)
            .putLong(signal.callId.mostSignificantBits)
            .putLong(signal.callId.leastSignificantBits)
            .putInt(signal.sdpMLineIndex)
            .putShort(mid.size.toShort())
            .put(mid)
            .putShort(candidate.size.toShort())
            .put(candidate)
            .putShort(username.size.toShort())
            .put(username)
            .array()
    }

    private fun decodeIceCandidate(payload: ByteArray): VoiceCallSignal.IceCandidate {
        if (payload.size < CALL_ID_BYTES + INT_BYTES + (SHORT_LENGTH_BYTES * 3) + 1) {
            throw TransferProtocolException("Truncated ICE candidate payload.")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val callId = UUID(buffer.long, buffer.long)
        val mLineIndex = buffer.int
        if (mLineIndex < 0) throw TransferProtocolException("Negative ICE media-line index.")
        val midLength = readLength(buffer, MAX_MID_BYTES)
        val mid = decodeUtf8(ByteArray(midLength).also(buffer::get))
        val candidateLength = readLength(buffer, MAX_CANDIDATE_BYTES)
        if (candidateLength == 0) throw TransferProtocolException("Empty ICE candidate.")
        val candidate = decodeUtf8(ByteArray(candidateLength).also(buffer::get))
        val usernameLength = readLength(buffer, MAX_USERNAME_FRAGMENT_BYTES)
        val username = decodeUtf8(ByteArray(usernameLength).also(buffer::get))
        if (buffer.hasRemaining()) throw TransferProtocolException("Trailing bytes in ICE candidate payload.")
        return VoiceCallSignal.IceCandidate(
            callId = callId,
            sdpMid = mid.ifEmpty { null },
            sdpMLineIndex = mLineIndex,
            candidate = candidate,
            usernameFragment = username.ifEmpty { null },
        )
    }

    private fun readLength(buffer: ByteBuffer, maximumBytes: Int): Int {
        if (buffer.remaining() < SHORT_LENGTH_BYTES) {
            throw TransferProtocolException("Truncated voice call field length.")
        }
        val length = buffer.short.toInt() and 0xffff
        if (length > maximumBytes || length > buffer.remaining()) {
            throw TransferProtocolException("Voice call field length exceeds its bound.")
        }
        return length
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private const val CALL_ID_BYTES = 16
    private const val SHORT_LENGTH_BYTES = 2
    private const val INT_BYTES = 4
}
