package com.peyvand.core.domain.call
import java.util.UUID
enum class VoiceCallStatus { IDLE, OUTGOING_RINGING, INCOMING_RINGING, AWAITING_MICROPHONE_PERMISSION, NEGOTIATING, ACTIVE, ENDED, FAILED }
enum class VoiceCallDirection { OUTGOING, INCOMING }
enum class VoiceCallTerminationReason { LOCAL_HANGUP, REMOTE_HANGUP, REJECTED, CANCELLED, SESSION_LOST, NETWORK_LOST }
enum class VoiceCallFailure { MICROPHONE_PERMISSION_DENIED, PEER_UNSUPPORTED, SIGNALING_FAILED, MEDIA_INITIALIZATION_FAILED, MEDIA_NEGOTIATION_FAILED, AUTHENTICATED_SESSION_LOST, WIFI_DIRECT_LOST, PROTOCOL_ERROR, TIMEOUT, RESOURCE_UNAVAILABLE }
enum class VoiceCallFailureStage { CALL_CONTROL, MICROPHONE_PERMISSION, MEDIA_SESSION_SETUP, SDP_NEGOTIATION, ICE_EXCHANGE, P2P_MEDIA_VERIFICATION, AUDIO_ENGINE, SETUP_TIMEOUT, TELECOM }
data class VoiceCallSnapshot(
    val callId: UUID? = null,
    val status: VoiceCallStatus = VoiceCallStatus.IDLE,
    val direction: VoiceCallDirection? = null,
    val terminationReason: VoiceCallTerminationReason? = null,
    val failure: VoiceCallFailure? = null,
    val failureStage: VoiceCallFailureStage? = null,
) {
    val isTerminal: Boolean
        get() = status == VoiceCallStatus.ENDED || status == VoiceCallStatus.FAILED
}
