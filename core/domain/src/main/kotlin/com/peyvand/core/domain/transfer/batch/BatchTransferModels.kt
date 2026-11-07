package com.peyvand.core.domain.transfer.batch

import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import java.net.URI
import java.util.Collections
import java.util.UUID

class InvalidBatchTransferTransitionException(message: String) :
    IllegalStateException(message)

/**
 * Immutable, validated sender-side selection for one v2 batch.
 *
 * A one-item selection intentionally remains on the existing v1 single-file path.
 * Source URIs are retained only by this execution plan; they are never copied into
 * observable transfer snapshots.
 */
class BatchTransferPlan private constructor(
    val batchId: UUID,
    items: List<BatchTransferPlanItem>,
    val aggregateSizeBytes: Long?,
) {
    val items: List<BatchTransferPlanItem> =
        Collections.unmodifiableList(ArrayList(items))

    init {
        require(this.items.size in MIN_BATCH_ITEMS..MAX_BATCH_ITEMS)
        require(this.items.map { it.itemId }.toSet().size == this.items.size)
        require(this.items.none { it.itemId == batchId })
        this.items.forEachIndexed { index, item ->
            require(item.ordinal == index)
        }
        require(aggregateSizeBytes == calculateAggregateSize(this.items))
    }

    companion object {
        const val MIN_BATCH_ITEMS = 2
        const val MAX_BATCH_ITEMS = 100

        /**
         * Creates a batch with cryptographically random UUID identifiers.
         * UUID.randomUUID() uses a secure random source on the supported Java runtime.
         */
        fun create(selectedItems: List<ShareItem>): BatchTransferPlan {
            require(selectedItems.size in MIN_BATCH_ITEMS..MAX_BATCH_ITEMS) {
                "A v2 batch must contain between 2 and 100 items"
            }

            val batchId = UUID.randomUUID()
            val usedIds = HashSet<UUID>(selectedItems.size + 1)
            usedIds.add(batchId)

            val frozenSelection = selectedItems.toList()
            val plannedItems = frozenSelection.mapIndexed { ordinal, item ->
                val source = item.source as? ShareSource.ContentUri
                    ?: throw IllegalArgumentException(
                        "CP-004 supports user-selected content URIs only",
                    )

                val parsedUri = try {
                    URI(source.value)
                } catch (exception: Exception) {
                    throw IllegalArgumentException("Invalid content URI", exception)
                }

                require(parsedUri.scheme.equals("content", ignoreCase = true)) {
                    "Batch sources must use the content URI scheme"
                }
                require(!parsedUri.rawAuthority.isNullOrBlank()) {
                    "Content URI authority must not be blank"
                }
                require(item.displayName.isNotBlank()) {
                    "Selected item display name must not be blank"
                }
                require(item.mimeType.isNotBlank()) {
                    "Selected item MIME type must not be blank"
                }
                require(item.sizeBytes == null || item.sizeBytes >= 0L) {
                    "Selected item size must be non-negative or unknown"
                }

                var itemId: UUID
                do {
                    itemId = UUID.randomUUID()
                } while (!usedIds.add(itemId))

                BatchTransferPlanItem(
                    itemId = itemId,
                    ordinal = ordinal,
                    sourceItem = item,
                )
            }

            return BatchTransferPlan(
                batchId = batchId,
                items = plannedItems,
                aggregateSizeBytes = calculateAggregateSize(plannedItems),
            )
        }

        private fun calculateAggregateSize(
            items: List<BatchTransferPlanItem>,
        ): Long? {
            var total = 0L
            for (item in items) {
                val size = item.sourceItem.sizeBytes ?: return null
                if (size > Long.MAX_VALUE - total) return null
                total += size
            }
            return total
        }
    }
}

data class BatchTransferPlanItem internal constructor(
    val itemId: UUID,
    val ordinal: Int,
    val sourceItem: ShareItem,
)

enum class BatchTransferPhase {
    CREATED,
    OFFERED,
    AWAITING_BATCH_CONSENT,
    ACCEPTED,
    SELECTING_DESTINATION,
    PROCESSING,
    AWAITING_COMPLETION_ACK,
    CANCELLING,
    COMPLETED_SUCCESSFULLY,
    COMPLETED_WITH_ERRORS,
    REJECTED,
    CANCELLED,
    FAILED,
}

enum class BatchTransferCancellationStatus {
    NOT_REQUESTED,
    REQUESTED,
    CONFIRMED,
    UNCONFIRMED,
}

enum class BatchTransferFailureReason {
    BATCH_CONSENT_TIMEOUT,
    DESTINATION_SELECTION_TIMEOUT,
    CHANNEL_LOST,
    PROTOCOL_ERROR,
    COMPLETION_ACK_TIMEOUT,
    CANCELLATION_ACK_TIMEOUT,
    CLEANUP_FAILED,
    RESOURCE_LIMIT_EXCEEDED,
    INTERNAL_ERROR,
}

enum class BatchTransferItemPhase {
    QUEUED,
    OFFERED,
    AWAITING_CONSENT,
    ACCEPTED,
    TRANSFERRING,
    VERIFYING,
    PUBLISHING,
    ABORTING,
    AWAITING_TERMINAL_COMMIT,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
    NOT_STARTED,
}

enum class BatchTransferItemOutcome {
    REJECTED,
    CANCELLED,
    FAILED,
}

enum class BatchTransferItemFailureReason {
    CONSENT_TIMEOUT,
    SOURCE_UNAVAILABLE,
    SOURCE_READ_FAILED,
    SOURCE_PERMISSION_REVOKED,
    SOURCE_CHANGED,
    DESTINATION_UNAVAILABLE,
    DESTINATION_WRITE_FAILED,
    DESTINATION_READ_BACK_FAILED,
    STORAGE_EXHAUSTED,
    INTEGRITY_MISMATCH,
    ITEM_TIMEOUT,
    ABORT_HANDSHAKE_FAILED,
    CLEANUP_FAILED,
    RESOURCE_LIMIT_EXCEEDED,
    PROTOCOL_ERROR,
    CHANNEL_LOST,
    INVALID_RECEIPT,
    REMOTE_OUTCOME_UNKNOWN,
    BATCH_CANCELLED,
    UNKNOWN,
}

data class BatchTransferItemReceipt(
    val batchId: UUID,
    val itemId: UUID,
    val ordinal: Int,
    val actualSizeBytes: Long,
    val sha256Hex: String,
) {
    init {
        require(ordinal >= 0)
        require(actualSizeBytes >= 0L)
        require(SHA256_HEX.matches(sha256Hex))
    }

    private companion object {
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Observable item state. It deliberately contains no URI, filesystem path or source handle.
 * The receipt is populated only after the caller has validated the authenticated receipt.
 */
data class BatchTransferItemSnapshot(
    val batchId: UUID,
    val itemId: UUID,
    val ordinal: Int,
    val displayName: String,
    val mimeType: String,
    val declaredSizeBytes: Long?,
    val phase: BatchTransferItemPhase = BatchTransferItemPhase.QUEUED,
    /** Cumulative bytes committed by the receiver and acknowledged to the sender. */
    val acknowledgedBytes: Long = 0L,
    val maximumAcceptedBytes: Long? = null,
    val actualSizeBytes: Long? = null,
    val sha256Hex: String? = null,
    val pendingOutcome: BatchTransferItemOutcome? = null,
    val failureReason: BatchTransferItemFailureReason? = null,
    val receipt: BatchTransferItemReceipt? = null,
) {
    init {
        require(ordinal >= 0)
        require(displayName.isNotBlank())
        require(mimeType.isNotBlank())
        require(declaredSizeBytes == null || declaredSizeBytes >= 0L)
        require(acknowledgedBytes >= 0L)
        require(maximumAcceptedBytes == null || maximumAcceptedBytes >= 0L)
        require(actualSizeBytes == null || actualSizeBytes >= 0L)
        require(sha256Hex == null || SHA256_HEX.matches(sha256Hex))
        require(actualSizeBytes == null || sha256Hex != null)
        require(sha256Hex == null || actualSizeBytes != null)
        require(maximumAcceptedBytes == null || acknowledgedBytes <= maximumAcceptedBytes)
        require(declaredSizeBytes == null || acknowledgedBytes <= declaredSizeBytes)
        require(actualSizeBytes == null || acknowledgedBytes <= actualSizeBytes)
        require(maximumAcceptedBytes == null || actualSizeBytes == null ||
            actualSizeBytes <= maximumAcceptedBytes)
        require(declaredSizeBytes == null || actualSizeBytes == null ||
            actualSizeBytes == declaredSizeBytes)
        require(
            (phase == BatchTransferItemPhase.ABORTING ||
                phase == BatchTransferItemPhase.AWAITING_TERMINAL_COMMIT) ==
                (pendingOutcome != null),
        )
        require(receipt == null || phase == BatchTransferItemPhase.COMPLETED)
        if (phase == BatchTransferItemPhase.COMPLETED) {
            val verifiedReceipt = requireNotNull(receipt)
            require(actualSizeBytes != null)
            require(sha256Hex != null)
            require(acknowledgedBytes == actualSizeBytes)
            require(verifiedReceipt.batchId == batchId)
            require(verifiedReceipt.itemId == itemId)
            require(verifiedReceipt.ordinal == ordinal)
            require(actualSizeBytes == verifiedReceipt.actualSizeBytes)
            require(sha256Hex == verifiedReceipt.sha256Hex)
        }
    }

    private companion object {
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

data class BatchTransferSummary(
    val totalItems: Int,
    val completedItems: Int,
    val rejectedItems: Int,
    val cancelledItems: Int,
    val failedItems: Int,
    val notStartedItems: Int,
    val pendingItems: Int,
    /** Counts only items completed with a verified receiver receipt. */
    val verifiedBytes: Long,
) {
    val terminalItems: Int
        get() = completedItems + rejectedItems + cancelledItems + failedItems + notStartedItems
}

/**
 * Immutable aggregate state. The item list is defensively copied and made unmodifiable.
 * The source URI is not present in this snapshot, so presentation can observe it safely.
 */
class BatchTransferSnapshot internal constructor(
    val batchId: UUID,
    val aggregateSizeBytes: Long?,
    val phase: BatchTransferPhase,
    items: List<BatchTransferItemSnapshot>,
    val nextItemOrdinal: Int,
    val activeItemId: UUID?,
    val failureReason: BatchTransferFailureReason?,
    val cancellationStatus: BatchTransferCancellationStatus,
) {
    val items: List<BatchTransferItemSnapshot> =
        Collections.unmodifiableList(ArrayList(items))

    val summary: BatchTransferSummary = summarize(this.items)

    init {
        require(this.items.size in BatchTransferPlan.MIN_BATCH_ITEMS..BatchTransferPlan.MAX_BATCH_ITEMS)
        require(aggregateSizeBytes == null || aggregateSizeBytes >= 0L)
        require(nextItemOrdinal in 0..this.items.size)
        require(this.items.map { it.itemId }.toSet().size == this.items.size)
        this.items.forEachIndexed { index, item ->
            require(item.batchId == batchId)
            require(item.ordinal == index)
        }

        for (index in 0 until nextItemOrdinal) {
            require(this.items[index].phase.isTerminalOutcome())
        }

        if (activeItemId != null) {
            require(nextItemOrdinal < this.items.size)
            val activeItem = this.items[nextItemOrdinal]
            require(activeItem.itemId == activeItemId)
            require(!activeItem.phase.isTerminalOutcome())
        }

        if (phase == BatchTransferPhase.PROCESSING &&
            activeItemId == null &&
            nextItemOrdinal < this.items.size
        ) {
            require(this.items[nextItemOrdinal].phase == BatchTransferItemPhase.QUEUED)
        }

        if (phase.isTerminal() || phase == BatchTransferPhase.AWAITING_COMPLETION_ACK) {
            require(nextItemOrdinal == this.items.size)
            require(activeItemId == null)
            require(this.items.all { it.phase.isTerminalOutcome() })
        }
    }

    internal fun evolve(
        phase: BatchTransferPhase = this.phase,
        items: List<BatchTransferItemSnapshot> = this.items,
        nextItemOrdinal: Int = this.nextItemOrdinal,
        activeItemId: UUID? = this.activeItemId,
        failureReason: BatchTransferFailureReason? = this.failureReason,
        cancellationStatus: BatchTransferCancellationStatus = this.cancellationStatus,
    ): BatchTransferSnapshot = BatchTransferSnapshot(
        batchId = batchId,
        aggregateSizeBytes = aggregateSizeBytes,
        phase = phase,
        items = items,
        nextItemOrdinal = nextItemOrdinal,
        activeItemId = activeItemId,
        failureReason = failureReason,
        cancellationStatus = cancellationStatus,
    )

    private companion object {
        fun summarize(items: List<BatchTransferItemSnapshot>): BatchTransferSummary {
            val completed = items.count { it.phase == BatchTransferItemPhase.COMPLETED }
            val rejected = items.count { it.phase == BatchTransferItemPhase.REJECTED }
            val cancelled = items.count { it.phase == BatchTransferItemPhase.CANCELLED }
            val failed = items.count { it.phase == BatchTransferItemPhase.FAILED }
            val notStarted = items.count { it.phase == BatchTransferItemPhase.NOT_STARTED }
            val terminal = completed + rejected + cancelled + failed + notStarted

            var verifiedBytes = 0L
            items.asSequence()
                .filter { it.phase == BatchTransferItemPhase.COMPLETED }
                .forEach { item ->
                    val size = checkNotNull(item.actualSizeBytes)
                    if (size > Long.MAX_VALUE - verifiedBytes) {
                        throw InvalidBatchTransferTransitionException("Verified batch byte count overflows Long")
                    }
                    verifiedBytes += size
                }

            return BatchTransferSummary(
                totalItems = items.size,
                completedItems = completed,
                rejectedItems = rejected,
                cancelledItems = cancelled,
                failedItems = failed,
                notStartedItems = notStarted,
                pendingItems = items.size - terminal,
                verifiedBytes = verifiedBytes,
            )
        }
    }
}

internal fun BatchTransferItemPhase.isTerminalOutcome(): Boolean = when (this) {
    BatchTransferItemPhase.COMPLETED,
    BatchTransferItemPhase.REJECTED,
    BatchTransferItemPhase.CANCELLED,
    BatchTransferItemPhase.FAILED,
    BatchTransferItemPhase.NOT_STARTED,
    -> true

    else -> false
}

internal fun BatchTransferPhase.isTerminal(): Boolean = when (this) {
    BatchTransferPhase.COMPLETED_SUCCESSFULLY,
    BatchTransferPhase.COMPLETED_WITH_ERRORS,
    BatchTransferPhase.REJECTED,
    BatchTransferPhase.CANCELLED,
    BatchTransferPhase.FAILED,
    -> true

    else -> false
}
