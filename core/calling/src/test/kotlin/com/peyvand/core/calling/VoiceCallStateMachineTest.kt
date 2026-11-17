package com.peyvand.core.calling

import com.peyvand.core.domain.call.VoiceCallDirection
import com.peyvand.core.domain.call.VoiceCallFailure
import com.peyvand.core.domain.call.VoiceCallStatus
import com.peyvand.core.domain.call.VoiceCallTerminationReason
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallStateMachineTest {
    @Test
    fun outgoingCallRequestsMicrophoneOnlyAfterRemoteAcceptance() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        machine.beginOutgoing(id)
        assertEquals(VoiceCallStatus.OUTGOING_RINGING, machine.snapshot.status)
        assertThrows(IllegalStateException::class.java) { machine.mediaConnected(id) }
        machine.remoteAccepted(id)
        assertEquals(VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION, machine.snapshot.status)
        assertThrows(IllegalStateException::class.java) { machine.mediaConnected(id) }
        machine.outgoingMicrophonePermissionGranted(id)
        assertEquals(VoiceCallStatus.NEGOTIATING, machine.snapshot.status)
        machine.mediaConnected(id)
        assertEquals(VoiceCallStatus.ACTIVE, machine.snapshot.status)
    }

    @Test
    fun outgoingPermissionDenialNeverStartsMedia() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        machine.beginOutgoing(id)
        machine.remoteAccepted(id)
        machine.outgoingMicrophonePermissionDenied(id)
        assertEquals(VoiceCallStatus.FAILED, machine.snapshot.status)
        assertEquals(VoiceCallFailure.MICROPHONE_PERMISSION_DENIED, machine.snapshot.failure)
        assertThrows(IllegalStateException::class.java) { machine.mediaConnected(id) }
    }

    @Test
    fun incomingAcceptanceRequiresRuntimeMicrophonePermission() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        assertTrue(machine.receiveInvite(id))
        assertThrows(IllegalStateException::class.java) {
            machine.acceptIncoming(id, microphonePermissionGranted = false)
        }
        assertEquals(VoiceCallStatus.INCOMING_RINGING, machine.snapshot.status)
        machine.acceptIncoming(id, microphonePermissionGranted = true)
        assertEquals(VoiceCallStatus.NEGOTIATING, machine.snapshot.status)
    }

    @Test
    fun incomingCallRequiresUserAcceptanceThenSeparateMicrophoneGrant() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        assertTrue(machine.receiveInvite(id))
        machine.beginAcceptIncoming(id)
        assertEquals(VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION, machine.snapshot.status)
        assertThrows(IllegalStateException::class.java) { machine.mediaConnected(id) }
        machine.incomingMicrophonePermissionGranted(id)
        assertEquals(VoiceCallStatus.NEGOTIATING, machine.snapshot.status)
        machine.mediaConnected(id)
        assertEquals(VoiceCallStatus.ACTIVE, machine.snapshot.status)
    }

    @Test
    fun incomingMicrophonePermissionDenialFailsBeforeMediaNegotiation() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        assertTrue(machine.receiveInvite(id))
        machine.beginAcceptIncoming(id)
        machine.incomingMicrophonePermissionDenied(id)
        assertEquals(VoiceCallStatus.FAILED, machine.snapshot.status)
        assertEquals(VoiceCallFailure.MICROPHONE_PERMISSION_DENIED, machine.snapshot.failure)
    }

    @Test
    fun remoteCancelIsNotAcceptedAfterNegotiationBegins() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        assertTrue(machine.receiveInvite(id))
        machine.beginAcceptIncoming(id)
        machine.incomingMicrophonePermissionGranted(id)
        assertThrows(IllegalStateException::class.java) { machine.cancelRemotely(id) }
        assertEquals(VoiceCallStatus.NEGOTIATING, machine.snapshot.status)
    }

    @Test
    fun secondCallIsRejectedWhileAnotherCallIsRingingOrActive() {
        val machine = VoiceCallStateMachine()
        val first = UUID.randomUUID()
        machine.beginOutgoing(first)
        assertFalse(machine.receiveInvite(UUID.randomUUID()))
        machine.remoteAccepted(first)
        assertFalse(machine.receiveInvite(UUID.randomUUID()))
        machine.outgoingMicrophonePermissionGranted(first)
        machine.mediaConnected(first)
        assertFalse(machine.receiveInvite(UUID.randomUUID()))
    }

    @Test
    fun callIdCannotBeReplayedAfterThePreviousCallEnds() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        machine.beginOutgoing(id)
        machine.cancelOutgoing(id)
        machine.resetAfterTerminalCall()
        assertFalse(machine.receiveInvite(id))
        assertThrows(IllegalStateException::class.java) { machine.beginOutgoing(id) }
    }

    @Test
    fun callIdRegistryIsBoundedPerSession() {
        val machine = VoiceCallStateMachine(maximumCallIdsPerSession = 1)
        machine.beginOutgoing(UUID.randomUUID())
        val activeId = machine.snapshot.callId!!
        machine.cancelOutgoing(activeId)
        machine.resetAfterTerminalCall()
        assertFalse(machine.receiveInvite(UUID.randomUUID()))
    }

    @Test
    fun inboundCallTracksDirectionAndRemoteTermination() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        assertTrue(machine.receiveInvite(id))
        assertEquals(VoiceCallDirection.INCOMING, machine.snapshot.direction)
        machine.acceptIncoming(id, microphonePermissionGranted = true)
        machine.mediaConnected(id)
        machine.endRemotely(id)
        assertEquals(VoiceCallStatus.ENDED, machine.snapshot.status)
        assertEquals(VoiceCallTerminationReason.REMOTE_HANGUP, machine.snapshot.terminationReason)
    }

    @Test
    fun failedMediaNegotiationIsNeverReportedAsActive() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        machine.beginOutgoing(id)
        machine.remoteAccepted(id)
        machine.outgoingMicrophonePermissionGranted(id)
        machine.fail(id, VoiceCallFailure.MEDIA_NEGOTIATION_FAILED)
        assertEquals(VoiceCallStatus.FAILED, machine.snapshot.status)
    }
}
