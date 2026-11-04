package com.peyvand.core.transfer.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferStateMachineTest {
    @Test
    fun `receiver can reach completed only after consent transfer verification and publication`() {
        var phase = TransferPhase.IDLE
        phase = TransferStateMachine.reduce(phase, TransferEvent.START)
        phase = TransferStateMachine.reduce(phase, TransferEvent.P2P_CONNECTED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.SAS_CONFIRMED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.TLS_AUTHENTICATED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.RECEIVER_ACCEPTED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.STREAM_FINISHED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.INTEGRITY_VERIFIED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.DESTINATION_PUBLISHED)

        assertEquals(TransferPhase.COMPLETED, phase)
    }

    @Test
    fun `receiver rejection is terminal and occurs before transfer`() {
        var phase = TransferPhase.IDLE
        phase = TransferStateMachine.reduce(phase, TransferEvent.START)
        phase = TransferStateMachine.reduce(phase, TransferEvent.P2P_CONNECTED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.SAS_CONFIRMED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.TLS_AUTHENTICATED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.RECEIVER_REJECTED)

        assertEquals(TransferPhase.REJECTED, phase)
        assertThrows(InvalidTransferTransitionException::class.java) {
            TransferStateMachine.reduce(phase, TransferEvent.RECEIVER_ACCEPTED)
        }
    }

    @Test
    fun `cannot skip pairing or consent`() {
        assertThrows(InvalidTransferTransitionException::class.java) {
            TransferStateMachine.reduce(TransferPhase.CONNECTING, TransferEvent.TLS_AUTHENTICATED)
        }
        assertThrows(InvalidTransferTransitionException::class.java) {
            TransferStateMachine.reduce(TransferPhase.AWAITING_CONSENT, TransferEvent.STREAM_FINISHED)
        }
    }

    @Test
    fun `sender may complete only after authenticated receipt acknowledgement`() {
        var phase = TransferPhase.IDLE
        phase = TransferStateMachine.reduce(phase, TransferEvent.START)
        phase = TransferStateMachine.reduce(phase, TransferEvent.P2P_CONNECTED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.SAS_CONFIRMED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.TLS_AUTHENTICATED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.RECEIVER_ACCEPTED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.STREAM_FINISHED)
        phase = TransferStateMachine.reduce(phase, TransferEvent.RECEIPT_ACKNOWLEDGED)

        assertEquals(TransferPhase.COMPLETED, phase)
    }

    @Test
    fun `cancellation can terminate an active transfer`() {
        assertEquals(
            TransferPhase.CANCELLED,
            TransferStateMachine.reduce(TransferPhase.TRANSFERRING, TransferEvent.CANCEL),
        )
    }
}