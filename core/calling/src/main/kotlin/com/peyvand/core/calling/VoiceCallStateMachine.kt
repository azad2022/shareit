package com.peyvand.core.calling

import com.peyvand.core.domain.call.VoiceCallDirection
import com.peyvand.core.domain.call.VoiceCallFailure
import com.peyvand.core.domain.call.VoiceCallSnapshot
import com.peyvand.core.domain.call.VoiceCallStatus
import com.peyvand.core.domain.call.VoiceCallTerminationReason
import java.util.LinkedHashSet
import java.util.UUID

/**
 * One-call-per-authenticated-session state machine. Instantiate it per authenticated
 * peer session; the bounded replay registry survives call completion/reset.
 */
class VoiceCallStateMachine(
    private val maximumCallIdsPerSession: Int = DEFAULT_MAX_CALL_IDS_PER_SESSION,
) {
    private val lock = Any()
    private val seenCallIds = LinkedHashSet<UUID>()
    private var current = VoiceCallSnapshot()

    init {
        require(maximumCallIdsPerSession in 1..MAX_ALLOWED_CALL_IDS_PER_SESSION)
    }

    val snapshot: VoiceCallSnapshot
        get() = synchronized(lock) { current }

    fun beginOutgoing(callId: UUID, microphonePermissionGranted: Boolean) {
        synchronized(lock) {
            check(microphonePermissionGranted) {
                "RECORD_AUDIO permission is required before placing a voice call."
            }
            check(canStartNewCall()) { "A voice call is already active." }
            reserveCallId(callId)
            current = VoiceCallSnapshot(
                callId = callId,
                status = VoiceCallStatus.OUTGOING_RINGING,
                direction = VoiceCallDirection.OUTGOING,
            )
        }
    }

    /** False means busy, duplicate/replayed call ID, or the session call quota was reached. */
    fun receiveInvite(callId: UUID): Boolean = synchronized(lock) {
        if (!canStartNewCall() || seenCallIds.contains(callId) ||
            seenCallIds.size >= maximumCallIdsPerSession
        ) {
            return@synchronized false
        }
        reserveCallId(callId)
        current = VoiceCallSnapshot(
            callId = callId,
            status = VoiceCallStatus.INCOMING_RINGING,
            direction = VoiceCallDirection.INCOMING,
        )
        true
    }

    /** Local acceptance requires Android RECORD_AUDIO permission before media negotiation. */
    fun acceptIncoming(callId: UUID, microphonePermissionGranted: Boolean) {
        synchronized(lock) {
            requireCurrentCall(callId, VoiceCallStatus.INCOMING_RINGING)
            check(microphonePermissionGranted) {
                "The microphone permission must be granted before accepting a voice call."
            }
            current = current.copy(status = VoiceCallStatus.NEGOTIATING)
        }
    }

    fun remoteAccepted(callId: UUID) {
        synchronized(lock) {
            requireCurrentCall(callId, VoiceCallStatus.OUTGOING_RINGING)
            current = current.copy(status = VoiceCallStatus.NEGOTIATING)
        }
    }

    /** Media adapter calls this only after verified P2P route, DTLS-SRTP and audio readiness. */
    fun mediaConnected(callId: UUID) {
        synchronized(lock) {
            requireCurrentCall(callId, VoiceCallStatus.NEGOTIATING)
            current = current.copy(status = VoiceCallStatus.ACTIVE)
        }
    }

    fun rejectIncoming(callId: UUID) {
        synchronized(lock) {
            requireCurrentCall(callId, VoiceCallStatus.INCOMING_RINGING)
            current = current.copy(
                status = VoiceCallStatus.ENDED,
                terminationReason = VoiceCallTerminationReason.REJECTED,
            )
        }
    }

    fun cancelOutgoing(callId: UUID) {
        synchronized(lock) {
            requireCurrentCall(callId, VoiceCallStatus.OUTGOING_RINGING)
            current = current.copy(
                status = VoiceCallStatus.ENDED,
                terminationReason = VoiceCallTerminationReason.CANCELLED,
            )
        }
    }

    fun endLocally(callId: UUID) {
        synchronized(lock) {
            requireCurrentCall(
                callId,
                VoiceCallStatus.OUTGOING_RINGING,
                VoiceCallStatus.INCOMING_RINGING,
                VoiceCallStatus.NEGOTIATING,
                VoiceCallStatus.ACTIVE,
            )
            current = current.copy(
                status = VoiceCallStatus.ENDED,
                terminationReason = VoiceCallTerminationReason.LOCAL_HANGUP,
            )
        }
    }

    fun endRemotely(callId: UUID) {
        synchronized(lock) {
            requireCurrentCall(
                callId,
                VoiceCallStatus.OUTGOING_RINGING,
                VoiceCallStatus.INCOMING_RINGING,
                VoiceCallStatus.NEGOTIATING,
                VoiceCallStatus.ACTIVE,
            )
            current = current.copy(
                status = VoiceCallStatus.ENDED,
                terminationReason = VoiceCallTerminationReason.REMOTE_HANGUP,
            )
        }
    }

    fun sessionLost() {
        synchronized(lock) {
            if (current.callId != null && !current.isTerminal && current.status != VoiceCallStatus.IDLE) {
                current = current.copy(
                    status = VoiceCallStatus.ENDED,
                    terminationReason = VoiceCallTerminationReason.SESSION_LOST,
                )
            }
        }
    }

    fun networkLost(callId: UUID) {
        synchronized(lock) {
            requireCurrentCall(
                callId,
                VoiceCallStatus.OUTGOING_RINGING,
                VoiceCallStatus.INCOMING_RINGING,
                VoiceCallStatus.NEGOTIATING,
                VoiceCallStatus.ACTIVE,
            )
            current = current.copy(
                status = VoiceCallStatus.ENDED,
                terminationReason = VoiceCallTerminationReason.NETWORK_LOST,
            )
        }
    }

    fun fail(callId: UUID, failure: VoiceCallFailure) {
        synchronized(lock) {
            require(callId == current.callId) { "Failure belongs to a different call." }
            check(!current.isTerminal) { "A terminal call cannot fail again." }
            current = current.copy(status = VoiceCallStatus.FAILED, failure = failure)
        }
    }

    /** Reset visible state only; the session replay registry remains intact. */
    fun resetAfterTerminalCall() {
        synchronized(lock) {
            check(current.isTerminal) { "Only an ended or failed call can be reset." }
            current = VoiceCallSnapshot()
        }
    }

    private fun reserveCallId(callId: UUID) {
        check(!seenCallIds.contains(callId)) { "Call identifier was already used in this session." }
        check(seenCallIds.size < maximumCallIdsPerSession) {
            "The authenticated session reached its call identifier limit."
        }
        seenCallIds.add(callId)
    }

    private fun canStartNewCall(): Boolean =
        current.status == VoiceCallStatus.IDLE || current.isTerminal

    private fun requireCurrentCall(callId: UUID, vararg allowed: VoiceCallStatus) {
        require(current.callId == callId) { "Call control belongs to a different call." }
        check(current.status in allowed) {
            "Invalid voice call transition from ${current.status}."
        }
    }

    private companion object {
        const val DEFAULT_MAX_CALL_IDS_PER_SESSION = 128
        const val MAX_ALLOWED_CALL_IDS_PER_SESSION = 1_024
    }
}
