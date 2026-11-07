package com.peyvand.core.transfer.session

import com.peyvand.core.domain.transfer.TransferFileSource
import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.manifest.BatchTransferManifest
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.BatchItemCompletionRecord
import com.peyvand.core.transfer.protocol.BatchItemOutcome
import com.peyvand.core.transfer.protocol.BatchOffer
import com.peyvand.core.transfer.protocol.TransferFrame
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Small facade that keeps sender and receiver protocol state machines independently testable.
 * Version 1 remains implemented by SingleFileTransferProtocol.
 */
class MultiItemTransferProtocol(
    maximumFileSizeBytes: Long,
) {
    private val sender = BatchTransferSenderProtocol(maximumFileSizeBytes)
    private val receiver = BatchTransferReceiverProtocol(maximumFileSizeBytes)

    suspend fun sendBatch(
        sessionId: UUID,
        manifest: BatchTransferManifest,
        channelInput: InputStream,
        channelOutput: OutputStream,
        openSource: suspend (TransferManifestItem) -> TransferFileSource,
        cancellationRequests: ReceiveChannel<UUID>,
        isCancelledBeforeStart: (UUID) -> Boolean = { false },
        onActiveSourceChanged: (UUID, InputStream?) -> Unit = { _, _ -> },
        onItemProgress: (UUID, Long) -> Unit = { _, _ -> },
        onItemVerifying: (UUID) -> Unit = {},
        onItemOutcome: (BatchItemOutcome) -> Unit = {},
    ): BatchTransferResult = sender.sendBatch(
        sessionId,
        manifest,
        channelInput,
        channelOutput,
        openSource,
        cancellationRequests,
        isCancelledBeforeStart,
        onActiveSourceChanged,
        onItemProgress,
        onItemVerifying,
        onItemOutcome,
    )

    suspend fun receiveBatch(
        sessionId: UUID,
        firstOfferFrame: TransferFrame,
        channelInput: InputStream,
        channelOutput: OutputStream,
        acquireOfferConsent: suspend (BatchOffer) -> Boolean,
        acquireSelection: suspend (BatchTransferManifest) -> BatchReceiverSelection?,
        acquireStaging: suspend (TransferManifestItem) -> TransferStagingSink,
        publishVerified: suspend (
            TransferManifestItem,
            TransferStagingSink,
            String,
            BatchItemCompletionRecord,
        ) -> Boolean,
        cancellationRequests: ReceiveChannel<UUID>,
        isCancelledBeforeStart: (UUID) -> Boolean = { false },
        onItemProgress: (UUID, Long) -> Unit = { _, _ -> },
        onItemVerifying: (UUID) -> Unit = {},
        onItemOutcome: (BatchItemOutcome) -> Unit = {},
    ): BatchTransferResult? = receiver.receiveBatch(
        sessionId,
        firstOfferFrame,
        channelInput,
        channelOutput,
        acquireOfferConsent,
        acquireSelection,
        acquireStaging,
        publishVerified,
        cancellationRequests,
        isCancelledBeforeStart,
        onItemProgress,
        onItemVerifying,
        onItemOutcome,
    )
}
