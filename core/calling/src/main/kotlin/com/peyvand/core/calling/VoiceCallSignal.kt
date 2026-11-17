package com.peyvand.core.calling

import java.util.UUID

enum class VoiceCallRejectReason(val wireCode: Int) {
    USER_DECLINED(1),
    BUSY(2),
    UNSUPPORTED_MEDIA(3),
    MICROPHONE_PERMISSION_DENIED(4);

    companion object {
        fun fromWireCode(code: Int): VoiceCallRejectReason =
            entries.firstOrNull { it.wireCode == code }
                ?: throw IllegalArgumentException("Unknown call rejection reason: $code")
    }
}

enum class VoiceCallEndCause(val wireCode: Int) {
    NORMAL(1),
    NETWORK_FAILURE(2),
    INTERNAL_FAILURE(3);

    companion object {
        fun fromWireCode(code: Int): VoiceCallEndCause =
            entries.firstOrNull { it.wireCode == code }
                ?: throw IllegalArgumentException("Unknown call end cause: $code")
    }
}

sealed interface VoiceCallSignal {
    val callId: UUID

    data class Invite(override val callId: UUID) : VoiceCallSignal
    data class Accept(override val callId: UUID) : VoiceCallSignal
    data class Reject(
        override val callId: UUID,
        val reason: VoiceCallRejectReason,
    ) : VoiceCallSignal
    data class Cancel(override val callId: UUID) : VoiceCallSignal
    data class End(
        override val callId: UUID,
        val cause: VoiceCallEndCause,
    ) : VoiceCallSignal
    data class SdpOffer(
        override val callId: UUID,
        val sdp: String,
    ) : VoiceCallSignal
    data class SdpAnswer(
        override val callId: UUID,
        val sdp: String,
    ) : VoiceCallSignal
    data class IceCandidate(
        override val callId: UUID,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String,
        val usernameFragment: String? = null,
    ) : VoiceCallSignal
}
