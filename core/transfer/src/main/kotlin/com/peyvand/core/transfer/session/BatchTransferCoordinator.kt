package com.peyvand.core.transfer.session

import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.transfer.TransferFileStorageRepository
import com.peyvand.core.transfer.manifest.BatchTransferManifest
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.BatchOffer
import com.peyvand.core.transfer.protocol.BatchItemOutcome
import com.peyvand.core.transfer.protocol.TransferFrame
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Owns the storage-facing orchestration for multi-item sessions.
 *
 * Session lifecycle, UI consent decisions and the authenticated channel remain owned by
 * the outer session coordinator. This class owns only URI metadata/source adapters,
 * private staging and SAF publication callbacks.
 */
internal class BatchTransferCoordinator(
    private val storageRepository: TransferFileStorageRepository,
) {
    suspend fun send(
        channel: com.peyvand.core.domain.connection.AuthenticatedPeerChannel,
        selectedItems: List<ShareItem>,
        cancellationRequests: ReceiveChannel<UUID>,
        isCancelledBeforeStart: (UUID) -> Boolean,
        onManifestReady: (Map<String, UUID>) -> Unit,
        onActiveSourceChanged: (UUID, InputStream?) -> Unit,
        onItemProgress: (UUID, Long) -> Unit,
        onItemVerifying: (UUID) -> Unit,
        onItemOutcome: (BatchItemOutcome) -> Unit,
    ): BatchTransferResult {
        require(selectedItems.size in 2..BatchTransferManifest.MAX_ITEMS)
        val sourceIds = LinkedHashMap<String, UUID>()
        val sourcesByTransferId = LinkedHashMap<UUID, ShareItem>()
        val manifestItems = ArrayList<TransferManifestItem>(selectedItems.size)

        // Read metadata one source at a time and close the adapter immediately.
        // No provider stream is opened until the receiver has accepted the item start.
        selectedItems.forEach { selected ->
            val transferId = UUID.randomUUID()
            val source = storageRepository.openSource(selected)
            val metadata = try {
                source.metadata
            } finally {
                source.close()
            }
            sourceIds[selected.id] = transferId
            sourcesByTransferId[transferId] = selected
            manifestItems += TransferManifestItem(
                itemId = transferId,
                displayName = metadata.displayName,
                mimeType = metadata.mimeType,
                sizeBytes = metadata.sizeBytes,
            )
        }

        onManifestReady(sourceIds.toMap())
        val manifest = BatchTransferManifest(UUID.randomUUID(), manifestItems)
        return MultiItemTransferProtocol(Long.MAX_VALUE).sendBatch(
            sessionId = channel.sessionId,
            manifest = manifest,
            channelInput = channel.inputStream,
            channelOutput = channel.outputStream,
            openSource = { manifestItem ->
                val selected = sourcesByTransferId[manifestItem.itemId]
                    ?: throw IOException("Batch source identity is no longer available")
                val source = storageRepository.openSource(selected)
                val metadata = source.metadata
                if (metadata.displayName != manifestItem.displayName ||
                    metadata.mimeType != manifestItem.mimeType ||
                    metadata.sizeBytes != manifestItem.sizeBytes
                ) {
                    runCatching { source.close() }
                    throw IOException("Source metadata changed after the manifest was sent")
                }
                source
            },
            cancellationRequests = cancellationRequests,
            isCancelledBeforeStart = isCancelledBeforeStart,
            onActiveSourceChanged = onActiveSourceChanged,
            onItemProgress = onItemProgress,
            onItemVerifying = onItemVerifying,
            onItemOutcome = onItemOutcome,
        )
    }

    suspend fun receive(
        channel: com.peyvand.core.domain.connection.AuthenticatedPeerChannel,
        firstOfferFrame: TransferFrame,
        acquireOfferConsent: suspend (BatchOffer) -> Boolean,
        cancellationRequests: ReceiveChannel<UUID>,
        isCancelledBeforeStart: (UUID) -> Boolean,
        acquireSelection: suspend (BatchTransferManifest) -> BatchReceiverSelection?,
        onManifest: (List<TransferManifestItem>) -> Unit,
        onItemProgress: (UUID, Long) -> Unit,
        onItemVerifying: (UUID) -> Unit,
        onItemPublishing: (TransferManifestItem, Long) -> Unit,
        onItemOutcome: (BatchItemOutcome) -> Unit,
    ): BatchTransferResult? {
        val maximumReceiveSizeBytes = storageRepository.maximumReceiveSizeBytes()
        return MultiItemTransferProtocol(maximumReceiveSizeBytes).receiveBatch(
            sessionId = channel.sessionId,
            firstOfferFrame = firstOfferFrame,
            acquireOfferConsent = acquireOfferConsent,
            channelInput = channel.inputStream,
            channelOutput = channel.outputStream,
            acquireSelection = { manifest ->
                onManifest(manifest.items)
                acquireSelection(manifest)
            },
            acquireStaging = { item ->
                if (item.sizeBytes != null && item.sizeBytes > maximumReceiveSizeBytes) {
                    throw IOException("Item size exceeds the current receive capacity")
                }
                storageRepository.createPrivateStaging(
                    minOf(item.sizeBytes ?: maximumReceiveSizeBytes, maximumReceiveSizeBytes),
                )
            },
            publishVerified = { item, staging, treeUri, completion ->
                onItemPublishing(item, completion.actualSizeBytes)
                storageRepository.publishVerifiedToTree(
                    staging = staging,
                    treeUri = treeUri,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    expectedSizeBytes = completion.actualSizeBytes,
                    expectedSha256Hex = completion.sha256Hex,
                )
            },
            cancellationRequests = cancellationRequests,
            isCancelledBeforeStart = isCancelledBeforeStart,
            onItemProgress = onItemProgress,
            onItemVerifying = onItemVerifying,
            onItemOutcome = onItemOutcome,
        )
    }
}
