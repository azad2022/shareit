package com.peyvand.core.domain.transfer.batch

import com.peyvand.core.domain.files.ShareCategory
import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTransferStateMachineTest {
    @Test
    fun `all items complete only after verified receipts and batch completion acknowledgement`() {
        val plan = plan(5L, 7L)
        var state = readyForItems(plan)

        state = completeItem(state, plan.items[0], 5L, "a".repeat(64))
        assertEquals(BatchTransferPhase.PROCESSING, state.phase)
        assertEquals(5L, state.summary.verifiedBytes)

        state = completeItem(state, plan.items[1], 7L, "b".repeat(64))
        assertEquals(12L, state.summary.verifiedBytes)
        assertEquals(2, state.summary.completedItems)

        state = reduce(state, BatchTransferEvent.CompletionSent)
        assertEquals(BatchTransferPhase.AWAITING_COMPLETION_ACK, state.phase)
        assertThrows(InvalidBatchTransferTransitionException::class.java) {
            reduce(state, BatchTransferEvent.CancellationRequested)
        }

        state = reduce(state, BatchTransferEvent.CompletionAcknowledged)
        assertEquals(BatchTransferPhase.COMPLETED_SUCCESSFULLY, state.phase)
        assertEquals(0, state.summary.pendingItems)
        assertEquals(2, state.summary.terminalItems)
    }

    @Test
    fun `explicit item rejection requires terminal commit before the next item`() {
        val plan = plan(10L, 20L)
        var state = readyForItems(plan)
        val firstId = plan.items[0].itemId

        state = reduce(state, BatchTransferEvent.BeginItem(firstId))
        state = updateItem(state, firstId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(state, firstId, BatchTransferItemEvent.Rejected)

        assertEquals(
            BatchTransferItemPhase.AWAITING_TERMINAL_COMMIT,
            state.items[0].phase,
        )
        assertThrows(InvalidBatchTransferTransitionException::class.java) {
            reduce(state, BatchTransferEvent.BeginItem(plan.items[1].itemId))
        }

        state = updateItem(
            state,
            firstId,
            BatchTransferItemEvent.TerminalBoundaryCommitted,
        )
        assertEquals(BatchTransferItemPhase.REJECTED, state.items[0].phase)
        assertEquals(1, state.nextItemOrdinal)
        state = reduce(state, BatchTransferEvent.BeginItem(plan.items[1].itemId))
        assertEquals(plan.items[1].itemId, state.activeItemId)
    }

    @Test
    fun `item failure or cancellation cannot advance before abort acknowledgement and terminal commit`() {
        val plan = plan(100L, 200L)
        var state = readyForItems(plan)
        val firstId = plan.items[0].itemId

        state = reduce(state, BatchTransferEvent.BeginItem(firstId))
        state = updateItem(state, firstId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(state, firstId, BatchTransferItemEvent.Accepted(100L))
        state = updateItem(state, firstId, BatchTransferItemEvent.TransferStarted)
        state = updateItem(
            state,
            firstId,
            BatchTransferItemEvent.ProgressAcknowledged(64L),
        )
        state = updateItem(
            state,
            firstId,
            BatchTransferItemEvent.AbortStarted(
                outcome = BatchTransferItemOutcome.CANCELLED,
                reason = BatchTransferItemFailureReason.UNKNOWN,
            ),
        )

        assertEquals(BatchTransferItemPhase.ABORTING, state.items[0].phase)
        assertThrows(InvalidBatchTransferTransitionException::class.java) {
            updateItem(
                state,
                firstId,
                BatchTransferItemEvent.TerminalBoundaryCommitted,
            )
        }

        state = updateItem(state, firstId, BatchTransferItemEvent.AbortAcknowledged)
        assertEquals(
            BatchTransferItemPhase.AWAITING_TERMINAL_COMMIT,
            state.items[0].phase,
        )
        state = updateItem(
            state,
            firstId,
            BatchTransferItemEvent.TerminalBoundaryCommitted,
        )
        assertEquals(BatchTransferItemPhase.CANCELLED, state.items[0].phase)
        assertEquals(1, state.summary.cancelledItems)
        assertEquals(null, state.activeItemId)
    }

    @Test
    fun `partial success is not represented as full batch success`() {
        val plan = plan(10L, 20L)
        var state = readyForItems(plan)
        val firstId = plan.items[0].itemId

        state = reduce(state, BatchTransferEvent.BeginItem(firstId))
        state = updateItem(state, firstId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(state, firstId, BatchTransferItemEvent.Rejected)
        state = updateItem(
            state,
            firstId,
            BatchTransferItemEvent.TerminalBoundaryCommitted,
        )

        state = completeItem(state, plan.items[1], 20L, "c".repeat(64))
        state = reduce(state, BatchTransferEvent.CompletionSent)
        state = reduce(state, BatchTransferEvent.CompletionAcknowledged)

        assertEquals(BatchTransferPhase.COMPLETED_WITH_ERRORS, state.phase)
        assertEquals(1, state.summary.rejectedItems)
        assertEquals(1, state.summary.completedItems)
        assertEquals(20L, state.summary.verifiedBytes)
    }

    @Test
    fun `acknowledged progress is monotonic and bounded by negotiated and declared sizes`() {
        val plan = plan(100L, 200L)
        var state = readyForItems(plan)
        val itemId = plan.items[0].itemId

        state = reduce(state, BatchTransferEvent.BeginItem(itemId))
        state = updateItem(state, itemId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(state, itemId, BatchTransferItemEvent.Accepted(100L))
        state = updateItem(state, itemId, BatchTransferItemEvent.TransferStarted)
        state = updateItem(
            state,
            itemId,
            BatchTransferItemEvent.ProgressAcknowledged(64L),
        )

        assertThrows(InvalidBatchTransferTransitionException::class.java) {
            updateItem(
                state,
                itemId,
                BatchTransferItemEvent.ProgressAcknowledged(63L),
            )
        }
        assertThrows(InvalidBatchTransferTransitionException::class.java) {
            updateItem(
                state,
                itemId,
                BatchTransferItemEvent.ProgressAcknowledged(101L),
            )
        }
    }

    @Test
    fun `receipt must match the exact batch item ordinal size and digest`() {
        val plan = plan(4L, 8L)
        var state = readyForItems(plan)
        val item = plan.items[0]

        state = reduce(state, BatchTransferEvent.BeginItem(item.itemId))
        state = updateItem(state, item.itemId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(state, item.itemId, BatchTransferItemEvent.Accepted(4L))
        state = updateItem(state, item.itemId, BatchTransferItemEvent.TransferStarted)
        state = updateItem(
            state,
            item.itemId,
            BatchTransferItemEvent.ProgressAcknowledged(4L),
        )
        state = updateItem(
            state,
            item.itemId,
            BatchTransferItemEvent.VerificationStarted(4L, "d".repeat(64)),
        )
        state = updateItem(state, item.itemId, BatchTransferItemEvent.PublicationStarted)

        val invalidReceipt = BatchTransferItemReceipt(
            batchId = UUID.randomUUID(),
            itemId = item.itemId,
            ordinal = item.ordinal,
            actualSizeBytes = 4L,
            sha256Hex = "d".repeat(64),
        )
        assertThrows(InvalidBatchTransferTransitionException::class.java) {
            updateItem(
                state,
                item.itemId,
                BatchTransferItemEvent.Verified(invalidReceipt),
            )
        }
    }

    @Test
    fun `whole batch rejection leaves every item not started`() {
        val plan = plan(1L, 2L)
        var state = reduce(BatchTransferStateMachine.initial(plan), BatchTransferEvent.OfferSent)
        state = reduce(state, BatchTransferEvent.BatchConsentRequested)
        state = reduce(state, BatchTransferEvent.BatchRejected)

        assertEquals(BatchTransferPhase.REJECTED, state.phase)
        assertEquals(2, state.summary.notStartedItems)
        assertEquals(0, state.summary.completedItems)
        assertEquals(BatchTransferCancellationStatus.NOT_REQUESTED, state.cancellationStatus)
    }

    @Test
    fun `confirmed whole batch cancellation cancels active item and marks queued items not started`() {
        val plan = plan(10L, 20L, 30L)
        var state = readyForItems(plan)
        val activeId = plan.items[0].itemId

        state = reduce(state, BatchTransferEvent.BeginItem(activeId))
        state = updateItem(state, activeId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(state, activeId, BatchTransferItemEvent.Accepted(10L))
        state = updateItem(state, activeId, BatchTransferItemEvent.TransferStarted)
        state = reduce(state, BatchTransferEvent.CancellationRequested)
        state = reduce(state, BatchTransferEvent.CancellationAcknowledged)

        assertEquals(BatchTransferPhase.CANCELLED, state.phase)
        assertEquals(BatchTransferCancellationStatus.CONFIRMED, state.cancellationStatus)
        assertEquals(BatchTransferItemPhase.CANCELLED, state.items[0].phase)
        assertEquals(BatchTransferItemPhase.NOT_STARTED, state.items[1].phase)
        assertEquals(BatchTransferItemPhase.NOT_STARTED, state.items[2].phase)
    }

    @Test
    fun `unconfirmed whole batch cancellation reports unknown active item outcome`() {
        val plan = plan(10L, 20L)
        var state = readyForItems(plan)
        val activeId = plan.items[0].itemId

        state = reduce(state, BatchTransferEvent.BeginItem(activeId))
        state = updateItem(state, activeId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(state, activeId, BatchTransferItemEvent.Accepted(10L))
        state = updateItem(state, activeId, BatchTransferItemEvent.TransferStarted)
        state = reduce(state, BatchTransferEvent.CancellationRequested)
        state = reduce(state, BatchTransferEvent.CancellationUnconfirmed)

        assertEquals(BatchTransferPhase.CANCELLED, state.phase)
        assertEquals(BatchTransferCancellationStatus.UNCONFIRMED, state.cancellationStatus)
        assertEquals(BatchTransferItemPhase.FAILED, state.items[0].phase)
        assertEquals(
            BatchTransferItemFailureReason.REMOTE_OUTCOME_UNKNOWN,
            state.items[0].failureReason,
        )
        assertEquals(BatchTransferItemPhase.NOT_STARTED, state.items[1].phase)
    }

    @Test
    fun `channel failure terminates active item and prevents queued items from starting`() {
        val plan = plan(10L, 20L)
        var state = readyForItems(plan)
        val activeId = plan.items[0].itemId

        state = reduce(state, BatchTransferEvent.BeginItem(activeId))
        state = updateItem(state, activeId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(state, activeId, BatchTransferItemEvent.Accepted(10L))
        state = updateItem(state, activeId, BatchTransferItemEvent.TransferStarted)
        state = reduce(
            state,
            BatchTransferEvent.Failed(BatchTransferFailureReason.CHANNEL_LOST),
        )

        assertEquals(BatchTransferPhase.FAILED, state.phase)
        assertEquals(BatchTransferItemPhase.FAILED, state.items[0].phase)
        assertEquals(BatchTransferItemFailureReason.CHANNEL_LOST, state.items[0].failureReason)
        assertEquals(BatchTransferItemPhase.NOT_STARTED, state.items[1].phase)
    }

    @Test
    fun `cancellation after completion dispatch is rejected and crossing peer cancellation is ignored`() {
        val plan = plan(1L, 2L)
        var state = readyForItems(plan)
        state = completeItem(state, plan.items[0], 1L, "e".repeat(64))
        state = completeItem(state, plan.items[1], 2L, "f".repeat(64))
        state = reduce(state, BatchTransferEvent.CompletionSent)

        val afterLateCancellation = reduce(
            state,
            BatchTransferEvent.LatePeerCancellationIgnored,
        )
        assertSame(state, afterLateCancellation)
        assertThrows(InvalidBatchTransferTransitionException::class.java) {
            reduce(state, BatchTransferEvent.CancellationRequested)
        }
    }

    @Test
    fun `snapshot item collection is immutable`() {
        val plan = plan(1L, 2L)
        val state = BatchTransferStateMachine.initial(plan)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (state.items as MutableList<BatchTransferItemSnapshot>).clear()
        }
    }

    private fun readyForItems(plan: BatchTransferPlan): BatchTransferSnapshot {
        var state = BatchTransferStateMachine.initial(plan)
        state = reduce(state, BatchTransferEvent.OfferSent)
        state = reduce(state, BatchTransferEvent.BatchConsentRequested)
        state = reduce(state, BatchTransferEvent.BatchAccepted)
        state = reduce(state, BatchTransferEvent.DestinationSelectionStarted)
        state = reduce(state, BatchTransferEvent.DestinationSelected)
        return state
    }

    private fun completeItem(
        stateBefore: BatchTransferSnapshot,
        planItem: BatchTransferPlanItem,
        sizeBytes: Long,
        sha256: String,
    ): BatchTransferSnapshot {
        var state = reduce(
            stateBefore,
            BatchTransferEvent.BeginItem(planItem.itemId),
        )
        state = updateItem(state, planItem.itemId, BatchTransferItemEvent.ConsentRequested)
        state = updateItem(
            state,
            planItem.itemId,
            BatchTransferItemEvent.Accepted(maximumAcceptedBytes = sizeBytes),
        )
        state = updateItem(state, planItem.itemId, BatchTransferItemEvent.TransferStarted)
        state = updateItem(
            state,
            planItem.itemId,
            BatchTransferItemEvent.ProgressAcknowledged(sizeBytes),
        )
        state = updateItem(
            state,
            planItem.itemId,
            BatchTransferItemEvent.VerificationStarted(sizeBytes, sha256),
        )
        state = updateItem(
            state,
            planItem.itemId,
            BatchTransferItemEvent.PublicationStarted,
        )
        val receipt = BatchTransferItemReceipt(
            batchId = state.batchId,
            itemId = planItem.itemId,
            ordinal = planItem.ordinal,
            actualSizeBytes = sizeBytes,
            sha256Hex = sha256,
        )
        return updateItem(
            state,
            planItem.itemId,
            BatchTransferItemEvent.Verified(receipt),
        )
    }

    private fun updateItem(
        state: BatchTransferSnapshot,
        itemId: UUID,
        event: BatchTransferItemEvent,
    ): BatchTransferSnapshot = reduce(
        state,
        BatchTransferEvent.UpdateItem(itemId, event),
    )

    private fun reduce(
        state: BatchTransferSnapshot,
        event: BatchTransferEvent,
    ): BatchTransferSnapshot = BatchTransferStateMachine.reduce(state, event)

    private fun plan(vararg sizes: Long?): BatchTransferPlan {
        val items = sizes.mapIndexed { index, size ->
            ShareItem(
                id = "source-" + index,
                category = ShareCategory.FILES,
                displayName = "file-" + index + ".bin",
                mimeType = "application/octet-stream",
                sizeBytes = size,
                modifiedAtEpochMillis = null,
                source = ShareSource.ContentUri("content://provider/" + index),
            )
        }
        return BatchTransferPlan.create(items)
    }
}
