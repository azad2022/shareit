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
    fun outgoingCallRequiresRemoteAcceptanceAndMediaReadiness() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        machine.beginOutgoing(id, microphonePermissionGranted = true)
        assertEquals(VoiceCallStatus.OUTGOING_RINGING, machine.snapshot.status)
        assertThrows(IllegalStateException::class.java) { machine.mediaConnected(id) }
        machine.remoteAccepted(id)
        assertEquals(VoiceCallStatus.NEGOTIATING, machine.snapshot.status)
        machine.mediaConnected(id)
        assertEquals(VoiceCallStatus.ACTIVE, machine.snapshot.status)
    }

    @Test
    fun microphonePermissionIsRequiredForInitiationAndIncomingAcceptance() {
        val machine = VoiceCallStateMachine()
        assertThrows(IllegalStateException::class.java) {
            machine.beginOutgoing(UUID.randomUUID(), microphonePermissionGranted = false)
        }
        val incomingId = UUID.randomUUID()
        assertTrue(machine.receiveInvite(incomingId))
        assertThrows(IllegalStateException::class.java) {
            machine.acceptIncoming(incomingId, microphonePermissionGranted = false)
        }
        assertEquals(VoiceCallStatus.INCOMING_RINGING, machine.snapshot.status)
    }

    @Test
    fun secondCallIsRejectedWhileAnotherCallIsRingingOrActive() {
        val machine = VoiceCallStateMachine()
        val first = UUID.randomUUID()
        machine.beginOutgoing(first, microphonePermissionGranted = true)
        assertFalse(machine.receiveInvite(UUID.randomUUID()))
        machine.remoteAccepted(first)
        machine.mediaConnected(first)
        assertFalse(machine.receiveInvite(UUID.randomUUID()))
    }

    @Test
    fun callIdCannotBeReplayedAfterThePreviousCallEnds() {
        val machine = VoiceCallStateMachine()
        val id = UUID.randomUUID()
        machine.beginOutgoing(id, microphonePermissionGranted = true)
        machine.cancelOutgoing(id)
        machine.resetAfterTerminalCall()
        assertFalse(machine.receiveInvite(id))
        assertThrows(IllegalStateException::class.java) {
            machine.beginOutgoing(id, microphonePermissionGranted = true)
        }
    }

    @Test
    fun callIdRegistryIsBoundedPerSession() {
        val machine = VoiceCallStateMachine(maximumCallIdsPerSession = 1)
        machine.beginOutgoing(UUID.randomUUID(), microphonePermissionGranted = true)
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
        machine.beginOutgoing(id, microphonePermissionGranted = true)
        machine.remoteAccepted(id)
        machine.fail(id, VoiceCallFailure.MEDIA_NEGOTIATION_FAILED)
        assertEquals(VoiceCallStatus.FAILED, machine.snapshot.status)
    }
}
