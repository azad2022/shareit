package com.peyvand.core.domain.transfer.batch

import java.util.UUID

sealed interface BatchTransferItemEvent {
    object ConsentRequested : BatchTransferItemEvent
    data class Accepted(val maximumAcceptedBytes: Long) : BatchTransferItemEvent
    object Rejected : BatchTransferItemEvent
    data class PreflightFailed(
        val reason: BatchTransferItemFailureReason,
    ) : BatchTransferItemEvent
    object TransferStarted : BatchTransferItemEvent
    data class ProgressAcknowledged(val acknowledgedBytes: Long) : BatchTransferItemEvent
    data class VerificationStarted(
        val actualSizeBytes: Long,
        val sha256Hex: String,
    ) : BatchTransferItemEvent
    object PublicationStarted : BatchTransferItemEvent
    data class Verified(val receipt: BatchTransferItemReceipt) : BatchTransferItemEvent
    data class AbortStarted(
        val outcome: BatchTransferItemOutcome,
        val reason: BatchTransferItemFailureReason,
    ) : BatchTransferItemEvent
    object AbortAcknowledged : BatchTransferItemEvent
    object TerminalBoundaryCommitted : BatchTransferItemEvent

    /** Used only when a whole-batch failure makes further item-level recovery unsafe. */
    data class BatchTerminated(
        val reason: BatchTransferItemFailureReason,
    ) : BatchTransferItemEvent

    /** Used after the whole-batch cancellation handshake has completed. */
    object BatchCancelled : BatchTransferItemEvent
}

object BatchTransferItemStateMachine {
    fun reduce(
        current: BatchTransferItemSnapshot,
        event: BatchTransferItemEvent,
    ): BatchTransferItemSnapshot = when (event) {
        BatchTransferItemEvent.ConsentRequested -> {
            requirePhase(current, BatchTransferItemPhase.OFFERED)
            current.copy(phase = BatchTransferItemPhase.AWAITING_CONSENT)
        }

        is BatchTransferItemEvent.Accepted -> {
            requirePhase(current, BatchTransferItemPhase.AWAITING_CONSENT)
            requireTransition(event.maximumAcceptedBytes >= 0L, "Accepted byte limit is negative")
            val declaredSize = current.declaredSizeBytes
            requireTransition(
                declaredSize == null || declaredSize <= event.maximumAcceptedBytes,
                "Declared item size exceeds the receiver-accepted limit",
            )
            current.copy(
                phase = BatchTransferItemPhase.ACCEPTED,
                maximumAcceptedBytes = event.maximumAcceptedBytes,
            )
        }

        BatchTransferItemEvent.Rejected -> {
            requirePhase(current, BatchTransferItemPhase.AWAITING_CONSENT)
            current.copy(
                phase = BatchTransferItemPhase.AWAITING_TERMINAL_COMMIT,
                pendingOutcome = BatchTransferItemOutcome.REJECTED,
                failureReason = null,
            )
        }

        is BatchTransferItemEvent.PreflightFailed -> {
            requirePhase(
                current,
                BatchTransferItemPhase.OFFERED,
                BatchTransferItemPhase.AWAITING_CONSENT,
            )
            current.copy(
                phase = BatchTransferItemPhase.AWAITING_TERMINAL_COMMIT,
                pendingOutcome = BatchTransferItemOutcome.FAILED,
                failureReason = event.reason,
            )
        }

        BatchTransferItemEvent.TransferStarted -> {
            requirePhase(current, BatchTransferItemPhase.ACCEPTED)
            current.copy(phase = BatchTransferItemPhase.TRANSFERRING)
        }

        is BatchTransferItemEvent.ProgressAcknowledged -> {
            requirePhase(current, BatchTransferItemPhase.TRANSFERRING)
            val acknowledged = event.acknowledgedBytes
            requireTransition(acknowledged >= current.acknowledgedBytes) {
                "Acknowledged progress cannot move backwards"
            }
            requireTransition(acknowledged >= 0L, "Acknowledged progress is negative")
            val acceptedLimit = checkNotNull(current.maximumAcceptedBytes)
            requireTransition(acknowledged <= acceptedLimit) {
                "Acknowledged progress exceeds the negotiated receive limit"
            }
            val declaredSize = current.declaredSizeBytes
            requireTransition(declaredSize == null || acknowledged <= declaredSize) {
                "Acknowledged progress exceeds the declared item size"
            }
            current.copy(acknowledgedBytes = acknowledged)
        }

        is BatchTransferItemEvent.VerificationStarted -> {
            requirePhase(current, BatchTransferItemPhase.TRANSFERRING)
            requireTransition(event.actualSizeBytes >= 0L, "Verified size is negative")
            requireTransition(
                event.actualSizeBytes == current.acknowledgedBytes,
            ) {
                "Verification cannot start before all bytes are receiver-committed"
            }
            val declaredSize = current.declaredSizeBytes
            requireTransition(declaredSize == null || declaredSize == event.actualSizeBytes) {
                "Actual item size differs from the declared size"
            }
            val acceptedLimit = checkNotNull(current.maximumAcceptedBytes)
            requireTransition(event.actualSizeBytes <= acceptedLimit) {
                "Actual item size exceeds the negotiated receive limit"
            }
            requireTransition(SHA256_HEX.matches(event.sha256Hex)) {
                "SHA-256 must contain exactly 64 lowercase hexadecimal characters"
            }
            current.copy(
                phase = BatchTransferItemPhase.VERIFYING,
                actualSizeBytes = event.actualSizeBytes,
                sha256Hex = event.sha256Hex,
            )
        }

        BatchTransferItemEvent.PublicationStarted -> {
            requirePhase(current, BatchTransferItemPhase.VERIFYING)
            current.copy(phase = BatchTransferItemPhase.PUBLISHING)
        }

        is BatchTransferItemEvent.Verified -> {
            requirePhase(current, BatchTransferItemPhase.PUBLISHING)
            val receipt = event.receipt
            requireTransition(receipt.batchId == current.batchId) {
                "Receipt belongs to a different batch"
            }
            requireTransition(receipt.itemId == current.itemId) {
                "Receipt belongs to a different item"
            }
            requireTransition(receipt.ordinal == current.ordinal) {
                "Receipt ordinal does not match the active queue position"
            }
            requireTransition(receipt.actualSizeBytes == current.actualSizeBytes) {
                "Receipt byte count does not match the verified item"
            }
            requireTransition(receipt.sha256Hex == current.sha256Hex) {
                "Receipt digest does not match the verified item"
            }
            current.copy(
                phase = BatchTransferItemPhase.COMPLETED,
                pendingOutcome = null,
                failureReason = null,
                receipt = receipt,
            )
        }

        is BatchTransferItemEvent.AbortStarted -> {
            requirePhase(
                current,
                BatchTransferItemPhase.ACCEPTED,
                BatchTransferItemPhase.TRANSFERRING,
                BatchTransferItemPhase.VERIFYING,
                BatchTransferItemPhase.PUBLISHING,
            )
            requireTransition(event.outcome != BatchTransferItemOutcome.REJECTED) {
                "An accepted item cannot transition to REJECTED"
            }
            current.copy(
                phase = BatchTransferItemPhase.ABORTING,
                pendingOutcome = event.outcome,
                failureReason = event.reason,
            )
        }

        BatchTransferItemEvent.AbortAcknowledged -> {
            requirePhase(current, BatchTransferItemPhase.ABORTING)
            current.copy(phase = BatchTransferItemPhase.AWAITING_TERMINAL_COMMIT)
        }

        BatchTransferItemEvent.TerminalBoundaryCommitted -> {
            requirePhase(current, BatchTransferItemPhase.AWAITING_TERMINAL_COMMIT)
            val outcome = checkNotNull(current.pendingOutcome)
            val finalPhase = when (outcome) {
                BatchTransferItemOutcome.REJECTED -> BatchTransferItemPhase.REJECTED
                BatchTransferItemOutcome.CANCELLED -> BatchTransferItemPhase.CANCELLED
                BatchTransferItemOutcome.FAILED -> BatchTransferItemPhase.FAILED
            }
            current.copy(
                phase = finalPhase,
                pendingOutcome = null,
                failureReason = if (outcome == BatchTransferItemOutcome.REJECTED) {
                    null
                } else {
                    current.failureReason
                },
            )
        }

        is BatchTransferItemEvent.BatchTerminated -> {
            if (current.phase.isTerminalOutcome()) {
                current
            } else if (current.phase == BatchTransferItemPhase.QUEUED) {
                current.copy(phase = BatchTransferItemPhase.NOT_STARTED)
            } else {
                current.copy(
                    phase = BatchTransferItemPhase.FAILED,
                    pendingOutcome = null,
                    failureReason = event.reason,
                    receipt = null,
                )
            }
        }

        BatchTransferItemEvent.BatchCancelled -> {
            if (current.phase.isTerminalOutcome()) {
                current
            } else if (current.phase == BatchTransferItemPhase.QUEUED) {
                current.copy(phase = BatchTransferItemPhase.NOT_STARTED)
            } else {
                current.copy(
                    phase = BatchTransferItemPhase.CANCELLED,
                    pendingOutcome = null,
                    failureReason = BatchTransferItemFailureReason.BATCH_CANCELLED,
                    receipt = null,
                )
            }
        }
    }

    private fun requirePhase(
        current: BatchTransferItemSnapshot,
        vararg expected: BatchTransferItemPhase,
    ) {
        requireTransition(current.phase in expected) {
            "Invalid item transition from " + current.phase + "; expected " + expected.joinToString()
        }
    }

    private fun requireTransition(condition: Boolean, message: String) {
        if (!condition) throw InvalidBatchTransferTransitionException(message)
    }

    private fun requireTransition(condition: Boolean, message: () -> String) {
        if (!condition) throw InvalidBatchTransferTransitionException(message())
    }

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
}

sealed interface BatchTransferEvent {
    object OfferSent : BatchTransferEvent
    object BatchConsentRequested : BatchTransferEvent
    object BatchAccepted : BatchTransferEvent
    object BatchRejected : BatchTransferEvent
    object DestinationSelectionStarted : BatchTransferEvent
    object DestinationSelected : BatchTransferEvent
    data class BeginItem(val itemId: UUID) : BatchTransferEvent
    data class UpdateItem(
        val itemId: UUID,
        val event: BatchTransferItemEvent,
    ) : BatchTransferEvent
    object CompletionSent : BatchTransferEvent
    object CompletionAcknowledged : BatchTransferEvent
    object CancellationRequested : BatchTransferEvent
    object CancellationAcknowledged : BatchTransferEvent
    object CancellationUnconfirmed : BatchTransferEvent
    data class Failed(val reason: BatchTransferFailureReason) : BatchTransferEvent

    /** A cancellation arriving after BATCH_COMPLETE was committed cannot reverse completion. */
    object LatePeerCancellationIgnored : BatchTransferEvent
}

object BatchTransferStateMachine {
    fun initial(plan: BatchTransferPlan): BatchTransferSnapshot {
        val itemStates = plan.items.map { item ->
            BatchTransferItemSnapshot(
                batchId = plan.batchId,
                itemId = item.itemId,
                ordinal = item.ordinal,
                displayName = item.sourceItem.displayName,
                mimeType = item.sourceItem.mimeType,
                declaredSizeBytes = item.sourceItem.sizeBytes,
            )
        }
        return BatchTransferSnapshot(
            batchId = plan.batchId,
            aggregateSizeBytes = plan.aggregateSizeBytes,
            phase = BatchTransferPhase.CREATED,
            items = itemStates,
            nextItemOrdinal = 0,
            activeItemId = null,
            failureReason = null,
            cancellationStatus = BatchTransferCancellationStatus.NOT_REQUESTED,
        )
    }

    fun reduce(
        current: BatchTransferSnapshot,
        event: BatchTransferEvent,
    ): BatchTransferSnapshot = when (event) {
        BatchTransferEvent.OfferSent -> {
            requireBatchPhase(current, BatchTransferPhase.CREATED)
            current.evolve(phase = BatchTransferPhase.OFFERED)
        }

        BatchTransferEvent.BatchConsentRequested -> {
            requireBatchPhase(current, BatchTransferPhase.OFFERED)
            current.evolve(phase = BatchTransferPhase.AWAITING_BATCH_CONSENT)
        }

        BatchTransferEvent.BatchAccepted -> {
            requireBatchPhase(current, BatchTransferPhase.AWAITING_BATCH_CONSENT)
            current.evolve(phase = BatchTransferPhase.ACCEPTED)
        }

        BatchTransferEvent.BatchRejected -> {
            requireBatchPhase(current, BatchTransferPhase.AWAITING_BATCH_CONSENT)
            val items = current.items.map { item ->
                BatchTransferItemStateMachine.reduce(
                    item,
                    BatchTransferItemEvent.BatchTerminated(
                        BatchTransferItemFailureReason.BATCH_CANCELLED,
                    ),
                )
            }
            current.evolve(
                phase = BatchTransferPhase.REJECTED,
                items = items,
                nextItemOrdinal = items.size,
                activeItemId = null,
                failureReason = null,
            )
        }

        BatchTransferEvent.DestinationSelectionStarted -> {
            requireBatchPhase(current, BatchTransferPhase.ACCEPTED)
            current.evolve(phase = BatchTransferPhase.SELECTING_DESTINATION)
        }

        BatchTransferEvent.DestinationSelected -> {
            requireBatchPhase(current, BatchTransferPhase.SELECTING_DESTINATION)
            current.evolve(phase = BatchTransferPhase.PROCESSING)
        }

        is BatchTransferEvent.BeginItem -> beginItem(current, event.itemId)

        is BatchTransferEvent.UpdateItem -> updateItem(
            current = current,
            itemId = event.itemId,
            event = event.event,
        )

        BatchTransferEvent.CompletionSent -> {
            requireBatchPhase(current, BatchTransferPhase.PROCESSING)
            requireBatchTransition(current.activeItemId == null) {
                "Cannot complete a batch while an item is active"
            }
            requireBatchTransition(current.nextItemOrdinal == current.items.size) {
                "Cannot complete a batch before every item has a terminal outcome"
            }
            requireBatchTransition(
                current.items.all {
                    it.phase in setOf(
                        BatchTransferItemPhase.COMPLETED,
                        BatchTransferItemPhase.REJECTED,
                        BatchTransferItemPhase.CANCELLED,
                        BatchTransferItemPhase.FAILED,
                    )
                },
            ) {
                "A normal batch completion cannot contain NOT_STARTED items"
            }
            current.evolve(phase = BatchTransferPhase.AWAITING_COMPLETION_ACK)
        }

        BatchTransferEvent.CompletionAcknowledged -> {
            requireBatchPhase(current, BatchTransferPhase.AWAITING_COMPLETION_ACK)
            val finalPhase = if (current.summary.completedItems == current.summary.totalItems) {
                BatchTransferPhase.COMPLETED_SUCCESSFULLY
            } else {
                BatchTransferPhase.COMPLETED_WITH_ERRORS
            }
            current.evolve(phase = finalPhase)
        }

        BatchTransferEvent.CancellationRequested -> {
            requireBatchPhase(
                current,
                BatchTransferPhase.OFFERED,
                BatchTransferPhase.AWAITING_BATCH_CONSENT,
                BatchTransferPhase.ACCEPTED,
                BatchTransferPhase.SELECTING_DESTINATION,
                BatchTransferPhase.PROCESSING,
            )
            current.evolve(
                phase = BatchTransferPhase.CANCELLING,
                cancellationStatus = BatchTransferCancellationStatus.REQUESTED,
            )
        }

        BatchTransferEvent.CancellationAcknowledged -> finishCancellation(
            current = current,
            confirmed = true,
        )

        BatchTransferEvent.CancellationUnconfirmed -> finishCancellation(
            current = current,
            confirmed = false,
        )

        is BatchTransferEvent.Failed -> failBatch(current, event.reason)

        BatchTransferEvent.LatePeerCancellationIgnored -> {
            requireBatchPhase(current, BatchTransferPhase.AWAITING_COMPLETION_ACK)
            current
        }
    }

    private fun beginItem(
        current: BatchTransferSnapshot,
        itemId: UUID,
    ): BatchTransferSnapshot {
        requireBatchPhase(current, BatchTransferPhase.PROCESSING)
        requireBatchTransition(current.activeItemId == null) {
            "Only one batch item may be active"
        }
        requireBatchTransition(current.nextItemOrdinal < current.items.size) {
            "No queued batch item remains"
        }

        val item = current.items[current.nextItemOrdinal]
        requireBatchTransition(item.itemId == itemId) {
            "Items must be processed in the predeclared selection order"
        }
        requireBatchTransition(item.phase == BatchTransferItemPhase.QUEUED) {
            "The next batch item is not queued"
        }

        val updatedItems = current.items.toMutableList()
        updatedItems[current.nextItemOrdinal] = item.copy(
            phase = BatchTransferItemPhase.OFFERED,
        )
        return current.evolve(
            items = updatedItems,
            activeItemId = itemId,
        )
    }

    private fun updateItem(
        current: BatchTransferSnapshot,
        itemId: UUID,
        event: BatchTransferItemEvent,
    ): BatchTransferSnapshot {
        requireBatchPhase(current, BatchTransferPhase.PROCESSING)
        requireBatchTransition(current.activeItemId == itemId) {
            "Only the active batch item may change state"
        }

        val index = current.nextItemOrdinal
        val oldItem = current.items[index]
        val updatedItem = BatchTransferItemStateMachine.reduce(oldItem, event)
        val updatedItems = current.items.toMutableList()
        updatedItems[index] = updatedItem

        if (!updatedItem.phase.isTerminalOutcome()) {
            return current.evolve(items = updatedItems)
        }

        // Constructing the next immutable snapshot recalculates verifiedBytes with
        // overflow checks. An overflowing aggregate is rejected rather than wrapped.
        return current.evolve(
            items = updatedItems,
            nextItemOrdinal = index + 1,
            activeItemId = null,
        )
    }

    private fun finishCancellation(
        current: BatchTransferSnapshot,
        confirmed: Boolean,
    ): BatchTransferSnapshot {
        requireBatchPhase(current, BatchTransferPhase.CANCELLING)

        val updatedItems = current.items.map { item ->
            when {
                item.phase.isTerminalOutcome() -> item
                confirmed -> BatchTransferItemStateMachine.reduce(
                    item,
                    BatchTransferItemEvent.BatchCancelled,
                )
                item.itemId == current.activeItemId -> BatchTransferItemStateMachine.reduce(
                    item,
                    BatchTransferItemEvent.BatchTerminated(
                        BatchTransferItemFailureReason.REMOTE_OUTCOME_UNKNOWN,
                    ),
                )
                else -> BatchTransferItemStateMachine.reduce(
                    item,
                    BatchTransferItemEvent.BatchTerminated(
                        BatchTransferItemFailureReason.BATCH_CANCELLED,
                    ),
                )
            }
        }

        return current.evolve(
            phase = BatchTransferPhase.CANCELLED,
            items = updatedItems,
            nextItemOrdinal = updatedItems.size,
            activeItemId = null,
            failureReason = if (confirmed) {
                null
            } else {
                BatchTransferFailureReason.CANCELLATION_ACK_TIMEOUT
            },
            cancellationStatus = if (confirmed) {
                BatchTransferCancellationStatus.CONFIRMED
            } else {
                BatchTransferCancellationStatus.UNCONFIRMED
            },
        )
    }

    private fun failBatch(
        current: BatchTransferSnapshot,
        reason: BatchTransferFailureReason,
    ): BatchTransferSnapshot {
        requireBatchTransition(!current.phase.isTerminal()) {
            "A terminal batch cannot fail again"
        }
        requireBatchTransition(current.phase != BatchTransferPhase.CANCELLING) {
            "Use CancellationUnconfirmed when the cancellation handshake cannot be confirmed"
        }

        val activeItem = current.items.getOrNull(current.nextItemOrdinal)
        val activeOutcomeMayBeUnknown = activeItem != null &&
            activeItem.itemId == current.activeItemId &&
            activeItem.phase in setOf(
                BatchTransferItemPhase.VERIFYING,
                BatchTransferItemPhase.PUBLISHING,
            )
        val itemReason = if (activeOutcomeMayBeUnknown) {
            BatchTransferItemFailureReason.REMOTE_OUTCOME_UNKNOWN
        } else {
            reason.toItemFailureReason()
        }
        val updatedItems = current.items.map { item ->
            if (item.phase.isTerminalOutcome()) {
                item
            } else {
                BatchTransferItemStateMachine.reduce(
                    item,
                    BatchTransferItemEvent.BatchTerminated(itemReason),
                )
            }
        }

        return current.evolve(
            phase = BatchTransferPhase.FAILED,
            items = updatedItems,
            nextItemOrdinal = updatedItems.size,
            activeItemId = null,
            failureReason = reason,
        )
    }

    private fun BatchTransferFailureReason.toItemFailureReason(): BatchTransferItemFailureReason =
        when (this) {
            BatchTransferFailureReason.BATCH_CONSENT_TIMEOUT ->
                BatchTransferItemFailureReason.CONSENT_TIMEOUT
            BatchTransferFailureReason.DESTINATION_SELECTION_TIMEOUT ->
                BatchTransferItemFailureReason.DESTINATION_UNAVAILABLE
            BatchTransferFailureReason.CHANNEL_LOST ->
                BatchTransferItemFailureReason.CHANNEL_LOST
            BatchTransferFailureReason.PROTOCOL_ERROR ->
                BatchTransferItemFailureReason.PROTOCOL_ERROR
            BatchTransferFailureReason.COMPLETION_ACK_TIMEOUT ->
                BatchTransferItemFailureReason.REMOTE_OUTCOME_UNKNOWN
            BatchTransferFailureReason.CANCELLATION_ACK_TIMEOUT ->
                BatchTransferItemFailureReason.REMOTE_OUTCOME_UNKNOWN
            BatchTransferFailureReason.CLEANUP_FAILED ->
                BatchTransferItemFailureReason.CLEANUP_FAILED
            BatchTransferFailureReason.RESOURCE_LIMIT_EXCEEDED ->
                BatchTransferItemFailureReason.RESOURCE_LIMIT_EXCEEDED
            BatchTransferFailureReason.INTERNAL_ERROR ->
                BatchTransferItemFailureReason.UNKNOWN
        }

    private fun requireBatchPhase(
        current: BatchTransferSnapshot,
        vararg expected: BatchTransferPhase,
    ) {
        requireBatchTransition(current.phase in expected) {
            "Invalid batch transition from " + current.phase + "; expected " + expected.joinToString()
        }
    }

    private fun requireBatchTransition(condition: Boolean, message: String, ) {
        if (!condition) throw InvalidBatchTransferTransitionException(message)
    }

    private fun requireBatchTransition(condition: Boolean, message: () -> String) {
        if (!condition) throw InvalidBatchTransferTransitionException(message())
    }
}
