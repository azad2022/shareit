package com.peyvand.core.transfer.session

import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.transfer.TransferFileStorageRepository
import com.peyvand.core.transfer.manifest.BatchTransferManifest
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.BatchOffer
import com.peyvand.core.transfer.protocol.BatchItemOutcome
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
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
        batchId: UUID = UUID.randomUUID(),
        channelInput: InputStream = channel.inputStream,
        protocolVersion: Int = TransferFrameCodec.BATCH_PROTOCOL_VERSION,
    ): BatchTransferResult {
        require(
            protocolVersion == TransferFrameCodec.BATCH_PROTOCOL_VERSION ||
                protocolVersion == TransferFrameCodec.CHAT_ATTACHMENT_PROTOCOL_VERSION,
        )
        val minimumItems = if (protocolVersion == TransferFrameCodec.BATCH_PROTOCOL_VERSION) {
            BatchTransferManifest.LEGACY_MIN_ITEMS
        } else {
            BatchTransferManifest.MIN_ITEMS
        }
        require(selectedItems.size in minimumItems..BatchTransferManifest.MAX_ITEMS)
        val sourceIds = LinkedHashMap<String, UUID>()
        val sourcesByTransferId = LinkedHashMap<UUID, ShareItem>()
        val manifestItems = ArrayList<TransferManifestItem>(selectedItems.size)

        // The catalog resolved metadata while the user selected these URIs. Avoid a
        // second provider query for every item before receiver consent: providers may
        // return transient/incomplete metadata, and failures must remain item-scoped.
        // Source handles and streams are opened only after the matching READY frame.
        selectedItems.forEach { selected ->
            val transferId = UUID.randomUUID()
            sourceIds[selected.id] = transferId
            sourcesByTransferId[transferId] = selected
            manifestItems += TransferManifestItem(
                itemId = transferId,
                displayName = selected.displayName,
                mimeType = selected.mimeType,
                sizeBytes = selected.sizeBytes,
            )
        }

        onManifestReady(sourceIds.toMap())
        val manifest = BatchTransferManifest(batchId, manifestItems)
        return MultiItemTransferProtocol(Long.MAX_VALUE, protocolVersion).sendBatch(
            sessionId = channel.sessionId,
            manifest = manifest,
            channelInput = channelInput,
            channelOutput = channel.outputStream,
            openSource = { manifestItem ->
                val selected = sourcesByTransferId[manifestItem.itemId]
                    ?: throw IOException("Batch source identity is no longer available")
                val source = storageRepository.openSource(selected)
                val declaredSize = manifestItem.sizeBytes
                val currentSize = source.metadata.sizeBytes
                if (declaredSize != null && currentSize != null && currentSize != declaredSize) {
                    runCatching { source.close() }
                    throw IOException("Source byte length changed after the manifest was approved")
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
        channelInput: InputStream = channel.inputStream,
    ): BatchTransferResult? {
        val maximumReceiveSizeBytes = storageRepository.maximumReceiveSizeBytes()
        return MultiItemTransferProtocol(maximumReceiveSizeBytes, firstOfferFrame.protocolVersion).receiveBatch(
            sessionId = channel.sessionId,
            firstOfferFrame = firstOfferFrame,
            acquireOfferConsent = acquireOfferConsent,
            channelInput = channelInput,
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
            publishVerified = { item, staging, completion ->
                onItemPublishing(item, completion.actualSizeBytes)
                storageRepository.publishVerifiedToDefaultDestination(
                    staging = staging,
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
