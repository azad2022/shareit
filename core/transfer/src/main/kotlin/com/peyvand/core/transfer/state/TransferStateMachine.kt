package com.peyvand.core.transfer.state

enum class TransferPhase {
    IDLE,
    CONNECTING,
    PAIRING,
    AUTHENTICATING,
    AWAITING_CONSENT,
    TRANSFERRING,
    VERIFYING,
    PUBLISHING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
}

enum class TransferEvent {
    START,
    P2P_CONNECTED,
    SAS_CONFIRMED,
    TLS_AUTHENTICATED,
    RECEIVER_ACCEPTED,
    RECEIVER_REJECTED,
    STREAM_FINISHED,
    INTEGRITY_VERIFIED,
    DESTINATION_PUBLISHED,
    RECEIPT_ACKNOWLEDGED,
    CANCEL,
    FAIL,
}

class InvalidTransferTransitionException(
    current: TransferPhase,
    event: TransferEvent,
) : IllegalStateException("Event $event is not valid while transfer is $current")

object TransferStateMachine {
    private val terminalPhases = setOf(
        TransferPhase.COMPLETED,
        TransferPhase.REJECTED,
        TransferPhase.CANCELLED,
        TransferPhase.FAILED,
    )

    fun reduce(current: TransferPhase, event: TransferEvent): TransferPhase {
        val next = when (event) {
            TransferEvent.START -> TransferPhase.CONNECTING.takeIf { current == TransferPhase.IDLE }
            TransferEvent.P2P_CONNECTED -> TransferPhase.PAIRING.takeIf { current == TransferPhase.CONNECTING }
            TransferEvent.SAS_CONFIRMED -> TransferPhase.AUTHENTICATING.takeIf { current == TransferPhase.PAIRING }
            TransferEvent.TLS_AUTHENTICATED -> TransferPhase.AWAITING_CONSENT.takeIf {
                current == TransferPhase.AUTHENTICATING
            }
            TransferEvent.RECEIVER_ACCEPTED -> TransferPhase.TRANSFERRING.takeIf {
                current == TransferPhase.AWAITING_CONSENT
            }
            TransferEvent.RECEIVER_REJECTED -> TransferPhase.REJECTED.takeIf {
                current == TransferPhase.AWAITING_CONSENT
            }
            TransferEvent.STREAM_FINISHED -> TransferPhase.VERIFYING.takeIf {
                current == TransferPhase.TRANSFERRING
            }
            TransferEvent.INTEGRITY_VERIFIED -> TransferPhase.PUBLISHING.takeIf {
                current == TransferPhase.VERIFYING
            }
            TransferEvent.DESTINATION_PUBLISHED -> TransferPhase.COMPLETED.takeIf {
                current == TransferPhase.PUBLISHING
            }
            TransferEvent.RECEIPT_ACKNOWLEDGED -> TransferPhase.COMPLETED.takeIf {
                current == TransferPhase.VERIFYING
            }
            TransferEvent.CANCEL -> TransferPhase.CANCELLED.takeIf {
                current !in terminalPhases && current != TransferPhase.IDLE
            }
            TransferEvent.FAIL -> TransferPhase.FAILED.takeIf {
                current !in terminalPhases && current != TransferPhase.IDLE
            }
        }
        return next ?: throw InvalidTransferTransitionException(current, event)
    }
}