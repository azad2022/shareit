package com.peyvand.core.calling

import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceCallSignalCodecTest {
    private val sessionId = UUID.fromString("f9e5b633-2653-4ac9-986e-7eab06b8d11a")

    @Test
    fun inviteAcceptRejectCancelAndEndRoundTrip() {
        val callId = UUID.randomUUID()
        val signals = listOf(
            VoiceCallSignal.Invite(callId),
            VoiceCallSignal.Accept(callId),
            VoiceCallSignal.Reject(callId, VoiceCallRejectReason.BUSY),
            VoiceCallSignal.Cancel(callId),
            VoiceCallSignal.End(callId, VoiceCallEndCause.NETWORK_FAILURE),
        )
        signals.forEach { signal ->
            assertEquals(signal, VoiceCallSignalCodec.decode(VoiceCallSignalCodec.encode(sessionId, signal), sessionId))
        }
    }

    @Test
    fun sdpAndIceRoundTrip() {
        val callId = UUID.randomUUID()
        val signals = listOf(
            VoiceCallSignal.SdpOffer(callId, "v=0\r\no=- 1 1 IN IP4 192.168.49.1\r\n"),
            VoiceCallSignal.SdpAnswer(callId, "v=0\r\no=- 2 2 IN IP4 192.168.49.2\r\n"),
            VoiceCallSignal.IceCandidate(
                callId, "audio", 0,
                "candidate:1 1 UDP 2122260223 192.168.49.2 50000 typ host",
                "localUfrag",
            ),
        )
        signals.forEach { signal ->
            assertEquals(signal, VoiceCallSignalCodec.decode(VoiceCallSignalCodec.encode(sessionId, signal), sessionId))
        }
    }

    @Test
    fun rejectsWrongSessionAndNonCallFrames() {
        val frame = VoiceCallSignalCodec.encode(sessionId, VoiceCallSignal.Invite(UUID.randomUUID()))
        assertThrows(TransferProtocolException::class.java) {
            VoiceCallSignalCodec.decode(frame, UUID.randomUUID())
        }
        val nonCall = TransferFrame(
            TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            TransferFrameType.PAIRING_APPROVED,
            sessionId,
            byteArrayOf(),
        )
        assertThrows(TransferProtocolException::class.java) {
            VoiceCallSignalCodec.decode(nonCall, sessionId)
        }
    }

    @Test
    fun rejectsOversizedSdpAndInvalidUtf8() {
        val callId = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) {
            VoiceCallSignalCodec.encode(
                sessionId, VoiceCallSignal.SdpOffer(callId, "x".repeat(VoiceCallSignalCodec.MAX_SDP_BYTES + 1)),
            )
        }
        val malformed = TransferFrame(
            TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            TransferFrameType.CALL_SDP_OFFER,
            sessionId,
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
                0, 1,
                0xc3.toByte(),
            ),
        )
        assertThrows(TransferProtocolException::class.java) {
            VoiceCallSignalCodec.decode(malformed, sessionId)
        }
    }
}
