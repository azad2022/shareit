package com.peyvand.core.transfer.session

import com.peyvand.core.domain.transfer.TransferFileSource
import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.integrity.TransferIntegrityVerifier
import com.peyvand.core.transfer.manifest.BatchConsentDecision
import com.peyvand.core.transfer.manifest.BatchConsentDecisionCodec
import com.peyvand.core.transfer.manifest.BatchTransferManifest
import com.peyvand.core.transfer.manifest.BatchTransferManifestCodec
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.BatchChunkAcknowledgement
import com.peyvand.core.transfer.protocol.BatchChunkAcknowledgementCodec
import com.peyvand.core.transfer.protocol.BatchItemCompletionCodec
import com.peyvand.core.transfer.protocol.BatchOffer
import com.peyvand.core.transfer.protocol.BatchOfferCodec
import com.peyvand.core.transfer.protocol.BatchItemCompletionRecord
import com.peyvand.core.transfer.protocol.BatchItemFailureCode
import com.peyvand.core.transfer.protocol.BatchItemFailureCodec
import com.peyvand.core.transfer.protocol.BatchItemFailureRecord
import com.peyvand.core.transfer.protocol.BatchItemOutcome
import com.peyvand.core.transfer.protocol.BatchItemOutcomeStatus
import com.peyvand.core.transfer.protocol.BatchItemReference
import com.peyvand.core.transfer.protocol.BatchItemReferenceCodec
import com.peyvand.core.transfer.protocol.BatchOutcomeDigest
import com.peyvand.core.transfer.protocol.BatchTerminalRecord
import com.peyvand.core.transfer.protocol.BatchTerminalRecordCodec
import com.peyvand.core.transfer.protocol.BatchTransferDataChunk
import com.peyvand.core.transfer.protocol.BatchTransferDataChunkCodec
import com.peyvand.core.transfer.protocol.TransferChunkAcknowledgement
import com.peyvand.core.transfer.protocol.TransferDataChunk
import com.peyvand.core.transfer.protocol.TransferFlowControlPolicy
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.protocol.TransferProtocolException
import com.peyvand.core.transfer.protocol.TransferSendWindow
import com.peyvand.core.transfer.stream.TransferStreamResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

internal class BatchTransferSenderProtocol(
    private val maximumFileSizeBytes: Long,
) {
    init {
        require(maximumFileSizeBytes >= 0L)
    }

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
    ): BatchTransferResult {
        val output = BufferedOutputStream(channelOutput, IO_BUFFER_BYTES)
        val reader = BatchFrameReader(channelInput, CoroutineScope(currentCoroutineContext()), sessionId)
        val outcomes = ArrayList<BatchItemOutcome>(manifest.items.size)

        try {
            // First disclose only a random batch identifier. Names, MIME types, sizes and
            // item count remain undisclosed until the receiver explicitly permits the preview.
            val offer = BatchOffer(manifest.batchId)
            writeFrame(
                output,
                sessionId,
                TransferFrameType.BATCH_OFFER,
                BatchOfferCodec.encode(offer),
            )
            val offerResponse = reader.next(sessionId)
            if (offerResponse.type == TransferFrameType.BATCH_ABORT) {
                val abortedOffer = BatchOfferCodec.decode(offerResponse.payloadBytes())
                if (abortedOffer.batchId != manifest.batchId) {
                    throw TransferProtocolException("Batch abort belongs to a different offer")
                }
                return rejectedResult(manifest)
            }
            requireType(offerResponse, TransferFrameType.BATCH_OFFER_ACCEPTED)
            if (BatchOfferCodec.decode(offerResponse.payloadBytes()) != offer) {
                throw TransferProtocolException("Batch offer approval belongs to a different batch")
            }

            val encodedManifest = BatchTransferManifestCodec.encode(manifest)
            writeFrame(output, sessionId, TransferFrameType.BATCH_MANIFEST, encodedManifest)

            val decisionFrame = reader.next(sessionId)
            if (decisionFrame.type == TransferFrameType.BATCH_ABORT) {
                val abortedOffer = BatchOfferCodec.decode(decisionFrame.payloadBytes())
                if (abortedOffer.batchId != manifest.batchId) {
                    throw TransferProtocolException("Batch abort belongs to a different manifest")
                }
                return cancelledResult(manifest)
            }
            requireType(decisionFrame, TransferFrameType.BATCH_DECISION)
            val decision = BatchConsentDecisionCodec.decode(decisionFrame.payloadBytes(), manifest)

            manifest.items.forEachIndexed { index, item ->
                if (!decision.accepts(index)) {
                    addOutcome(outcomes, BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.REJECTED), onItemOutcome)
                    return@forEachIndexed
                }

                val reference = BatchItemReference(manifest.batchId, item.itemId)
                if (isCancelledBeforeStart(item.itemId)) {
                    writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED, reference)
                    awaitReference(reader, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED_ACK, reference)
                    addOutcome(outcomes, BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.CANCELLED), onItemOutcome)
                    return@forEachIndexed
                }

                writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_START, reference)
                val ready = reader.next(sessionId)
                when (ready.type) {
                    TransferFrameType.BATCH_ITEM_FAILED -> {
                        decodeFailure(ready, reference)
                        addOutcome(outcomes, BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.FAILED), onItemOutcome)
                        return@forEachIndexed
                    }
                    TransferFrameType.BATCH_ITEM_READY -> requireReference(ready, reference)
                    TransferFrameType.BATCH_ITEM_CANCELLED -> {
                        requireReference(ready, reference)
                        writeReference(
                            output,
                            sessionId,
                            TransferFrameType.BATCH_ITEM_CANCELLED_ACK,
                            reference,
                        )
                        addOutcome(
                            outcomes,
                            BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.CANCELLED),
                            onItemOutcome,
                        )
                        return@forEachIndexed
                    }
                    else -> throw TransferProtocolException("Expected batch item readiness")
                }

                val source = try {
                    openSource(item)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    val outcome = abortItem(reader, output, sessionId, reference, BatchItemFailureCode.SOURCE_UNAVAILABLE)
                    addOutcome(outcomes, outcome, onItemOutcome)
                    return@forEachIndexed
                }

                val sourceStream = try {
                    source.openStream()
                } catch (cancellation: CancellationException) {
                    try {
                        source.close()
                    } catch (closeFailure: Throwable) {
                        cancellation.addSuppressed(closeFailure)
                    }
                    throw cancellation
                } catch (sourceFailure: Exception) {
                    try {
                        source.close()
                    } catch (closeFailure: Throwable) {
                        sourceFailure.addSuppressed(closeFailure)
                        throw sourceFailure
                    }
                    val outcome = abortItem(
                        reader,
                        output,
                        sessionId,
                        reference,
                        BatchItemFailureCode.SOURCE_UNAVAILABLE,
                    )
                    addOutcome(outcomes, outcome, onItemOutcome)
                    return@forEachIndexed
                }

                onActiveSourceChanged(item.itemId, sourceStream)
                val outcome = try {
                    sendItem(
                        sessionId = sessionId,
                        batchId = manifest.batchId,
                        manifestItem = item,
                        sourceStream = sourceStream,
                        reader = reader,
                        output = output,
                        cancellationRequests = cancellationRequests,
                        onActiveSourceChanged = onActiveSourceChanged,
                        onItemProgress = onItemProgress,
                        onItemVerifying = onItemVerifying,
                    )
                } finally {
                    onActiveSourceChanged(item.itemId, null)
                    var closeFailure: Throwable? = null
                    try {
                        sourceStream.close()
                    } catch (failure: Throwable) {
                        closeFailure = failure
                    }
                    try {
                        source.close()
                    } catch (failure: Throwable) {
                        if (closeFailure == null) closeFailure = failure
                        else closeFailure.addSuppressed(failure)
                    }
                    closeFailure?.let { throw it }
                }
                addOutcome(outcomes, outcome, onItemOutcome)
            }

            val digest = BatchOutcomeDigest.compute(
                manifest.batchId,
                manifest.items.map(TransferManifestItem::itemId),
                outcomes,
            )
            val terminal = BatchTerminalRecord(manifest.batchId, manifest.items.size, digest)
            writeFrame(output, sessionId, TransferFrameType.BATCH_COMPLETE, BatchTerminalRecordCodec.encode(terminal))
            val receiptFrame = reader.next(sessionId)
            requireType(receiptFrame, TransferFrameType.BATCH_RECEIPT)
            val remote = BatchTerminalRecordCodec.decode(receiptFrame.payloadBytes())
            if (remote != terminal) {
                throw TransferProtocolException("Final batch receipt does not match item outcomes")
            }
            return BatchTransferResult(outcomes.toList(), digest)
        } finally {
            reader.close()
        }
    }

    private suspend fun sendItem(
        sessionId: UUID,
        batchId: UUID,
        manifestItem: TransferManifestItem,
        sourceStream: InputStream,
        reader: BatchFrameReader,
        output: OutputStream,
        cancellationRequests: ReceiveChannel<UUID>,
        onActiveSourceChanged: (UUID, InputStream?) -> Unit,
        onItemProgress: (UUID, Long) -> Unit,
        onItemVerifying: (UUID) -> Unit,
    ): BatchItemOutcome {
        val itemId = manifestItem.itemId
        val reference = BatchItemReference(batchId, itemId)
        val maximumSize = minOf(maximumFileSizeBytes, manifestItem.sizeBytes ?: maximumFileSizeBytes)
        val window = TransferSendWindow(itemId, maximumSize)
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        var pendingRead: Deferred<Result<ByteArray?>>? = null

        try {
            while (true) {
                if (pendingRead == null && window.hasCapacity) {
                    pendingRead = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).async(Dispatchers.IO) {
                        runCatching { readChunk(sourceStream) }
                    }
                }

                when (val event = awaitSenderEvent(reader, cancellationRequests, pendingRead)) {
                    is SenderEvent.FrameReceived -> {
                        when (event.frame.type) {
                            TransferFrameType.BATCH_CHUNK_ACK -> {
                                val acknowledgement = BatchChunkAcknowledgementCodec.decode(event.frame.payloadBytes())
                                requireBatchReference(
                                    BatchItemReference(acknowledgement.batchId, acknowledgement.itemId),
                                    batchId,
                                    itemId,
                                )
                                window.acknowledge(
                                    TransferChunkAcknowledgement(itemId, acknowledgement.nextExpectedOffset),
                                )
                                onItemProgress(itemId, window.acknowledgedOffsetBytes)
                            }
                            TransferFrameType.BATCH_ITEM_CANCEL_REQUEST -> {
                                requireReference(event.frame, reference)
                                pendingRead?.cancel()
                                sourceStream.close()
                                onActiveSourceChanged(itemId, null)
                                writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED, reference)
                                return awaitCancelledOutcome(reader, sessionId, reference)
                            }
                            TransferFrameType.BATCH_ITEM_FAILED_REQUEST -> {
                                val failure = decodeFailure(event.frame, reference)
                                pendingRead?.cancel()
                                sourceStream.close()
                                onActiveSourceChanged(itemId, null)
                                writeFailure(
                                    output,
                                    sessionId,
                                    TransferFrameType.BATCH_ITEM_ABORT,
                                    reference,
                                    failure.failureCode,
                                )
                                return awaitFailedOutcome(reader, sessionId, reference)
                            }
                            else -> throw TransferProtocolException(
                                "Unexpected frame while sending batch item data",
                            )
                        }
                    }

                    is SenderEvent.LocalCancellation -> {
                        if (event.itemId != itemId) continue
                        pendingRead?.cancel()
                        sourceStream.close()
                        onActiveSourceChanged(itemId, null)
                        writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED, reference)
                        return awaitCancelledOutcome(reader, sessionId, reference)
                    }

                    is SenderEvent.ReadCompleted -> {
                        pendingRead = null
                        val bytes = try {
                            event.result.getOrThrow()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            writeFailure(
                                output,
                                sessionId,
                                TransferFrameType.BATCH_ITEM_ABORT,
                                reference,
                                BatchItemFailureCode.SOURCE_READ_FAILED,
                            )
                            return awaitFailedOutcome(reader, sessionId, reference)
                        }

                        if (bytes == null) {
                            onItemVerifying(itemId)
                            val localHash = digest.digest().toLowerHex()
                            if (manifestItem.sizeBytes != null && manifestItem.sizeBytes != totalBytes) {
                                writeFailure(
                                    output,
                                    sessionId,
                                    TransferFrameType.BATCH_ITEM_ABORT,
                                    reference,
                                    BatchItemFailureCode.SOURCE_READ_FAILED,
                                )
                                return awaitFailedOutcome(reader, sessionId, reference)
                            }
                            val completion = BatchItemCompletionRecord(batchId, itemId, totalBytes, localHash)
                            writeFrame(
                                output,
                                sessionId,
                                TransferFrameType.BATCH_ITEM_COMPLETE,
                                BatchItemCompletionCodec.encode(completion),
                            )
                            return awaitCompletedOrFailed(
                                reader,
                                sessionId,
                                manifestItem,
                                reference,
                                completion,
                                totalBytes,
                                localHash,
                                window,
                                onItemProgress,
                            )
                        }

                        if (bytes.isEmpty() ||
                            bytes.size > TransferFrameCodec.MAX_DATA_CHUNK_BYTES ||
                            bytes.size.toLong() > maximumSize - totalBytes ||
                            (manifestItem.sizeBytes != null &&
                                bytes.size.toLong() > manifestItem.sizeBytes - totalBytes)
                        ) {
                            writeFailure(
                                output,
                                sessionId,
                                TransferFrameType.BATCH_ITEM_ABORT,
                                reference,
                                BatchItemFailureCode.SOURCE_READ_FAILED,
                            )
                            return awaitFailedOutcome(reader, sessionId, reference)
                        }

                        val windowChunk = TransferDataChunk(itemId, totalBytes, bytes)
                        if (!window.tryReserve(windowChunk)) {
                            throw TransferProtocolException("Batch sender window rejected a chunk")
                        }
                        writeFrame(
                            output,
                            sessionId,
                            TransferFrameType.BATCH_DATA_CHUNK,
                            BatchTransferDataChunkCodec.encode(
                                BatchTransferDataChunk(batchId, itemId, totalBytes, bytes),
                            ),
                        )
                        digest.update(bytes)
                        totalBytes += bytes.size.toLong()
                    }
                }
            }
        } finally {
            pendingRead?.cancel()
        }
    }

    private suspend fun awaitCompletedOrFailed(
        reader: BatchFrameReader,
        sessionId: UUID,
        manifestItem: TransferManifestItem,
        reference: BatchItemReference,
        localCompletion: BatchItemCompletionRecord,
        localBytes: Long,
        localHash: String,
        window: TransferSendWindow,
        onItemProgress: (UUID, Long) -> Unit,
    ): BatchItemOutcome {
        while (true) {
            when (val frame = reader.next(sessionId)) {
                else -> when (frame.type) {
                    TransferFrameType.BATCH_CHUNK_ACK -> {
                        val acknowledgement = BatchChunkAcknowledgementCodec.decode(frame.payloadBytes())
                        requireBatchReference(
                            BatchItemReference(acknowledgement.batchId, acknowledgement.itemId),
                            reference.batchId,
                            reference.itemId,
                        )
                        window.acknowledge(
                            TransferChunkAcknowledgement(reference.itemId, acknowledgement.nextExpectedOffset),
                        )
                        onItemProgress(reference.itemId, window.acknowledgedOffsetBytes)
                    }
                    TransferFrameType.BATCH_ITEM_VERIFIED -> {
                        val remote = BatchItemCompletionCodec.decode(frame.payloadBytes())
                        if (remote != localCompletion) {
                            throw TransferProtocolException("Receiver integrity receipt differs from sender bytes")
                        }
                        val verified = TransferIntegrityVerifier.verify(
                            manifestItem,
                            TransferStreamResult(localBytes, localHash),
                            com.peyvand.core.transfer.protocol.TransferCompletionRecord(
                                remote.itemId,
                                remote.actualSizeBytes,
                                remote.sha256Hex,
                            ),
                        )
                        return BatchItemOutcome(
                            reference.itemId,
                            BatchItemOutcomeStatus.COMPLETED,
                            verified.actualSizeBytes,
                            verified.sha256Hex,
                        )
                    }
                    TransferFrameType.BATCH_ITEM_FAILED -> {
                        decodeFailure(frame, reference)
                        return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.FAILED)
                    }
                    else -> throw TransferProtocolException("Unexpected frame while waiting for item receipt")
                }
            }
        }
    }

    private suspend fun abortItem(
        reader: BatchFrameReader,
        output: OutputStream,
        sessionId: UUID,
        reference: BatchItemReference,
        failureCode: BatchItemFailureCode,
    ): BatchItemOutcome {
        writeFailure(output, sessionId, TransferFrameType.BATCH_ITEM_ABORT, reference, failureCode)
        return awaitFailedOutcome(reader, sessionId, reference)
    }

    private suspend fun awaitFailedOutcome(
        reader: BatchFrameReader,
        sessionId: UUID,
        reference: BatchItemReference,
    ): BatchItemOutcome {
        while (true) {
            val frame = reader.next(sessionId)
            when (frame.type) {
                TransferFrameType.BATCH_CHUNK_ACK -> {
                    val ack = BatchChunkAcknowledgementCodec.decode(frame.payloadBytes())
                    requireBatchReference(BatchItemReference(ack.batchId, ack.itemId), reference.batchId, reference.itemId)
                }
                TransferFrameType.BATCH_ITEM_FAILED_ACK -> {
                    requireReference(frame, reference)
                    return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.FAILED)
                }
                TransferFrameType.BATCH_ITEM_CANCELLED_ACK -> {
                    requireReference(frame, reference)
                    return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.CANCELLED)
                }
                else -> throw TransferProtocolException("Unexpected frame while awaiting failure acknowledgement")
            }
        }
    }

    private suspend fun awaitCancelledOutcome(
        reader: BatchFrameReader,
        sessionId: UUID,
        reference: BatchItemReference,
    ): BatchItemOutcome {
        while (true) {
            val frame = reader.next(sessionId)
            when (frame.type) {
                TransferFrameType.BATCH_CHUNK_ACK -> {
                    val ack = BatchChunkAcknowledgementCodec.decode(frame.payloadBytes())
                    requireBatchReference(BatchItemReference(ack.batchId, ack.itemId), reference.batchId, reference.itemId)
                }
                TransferFrameType.BATCH_ITEM_CANCELLED_ACK -> {
                    requireReference(frame, reference)
                    return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.CANCELLED)
                }
                TransferFrameType.BATCH_ITEM_FAILED_ACK -> {
                    requireReference(frame, reference)
                    return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.FAILED)
                }
                else -> throw TransferProtocolException("Unexpected frame while awaiting cancellation acknowledgement")
            }
        }
    }

    private suspend fun awaitSenderEvent(
        reader: BatchFrameReader,
        cancellations: ReceiveChannel<UUID>,
        pendingRead: Deferred<Result<ByteArray?>>?,
    ): SenderEvent {
        return if (pendingRead == null) {
            select {
                reader.frames.onReceiveCatching { result ->
                    SenderEvent.FrameReceived(
                        result.getOrNull() ?: throw (result.exceptionOrNull()
                            ?: EOFException("Batch protocol input closed while awaiting a frame")),
                    )
                }
                cancellations.onReceiveCatching { result ->
                    SenderEvent.LocalCancellation(
                        result.getOrNull() ?: throw (result.exceptionOrNull()
                            ?: CancellationException("Batch cancellation channel was closed")),
                    )
                }
            }
        } else {
            select {
                reader.frames.onReceiveCatching { result ->
                    SenderEvent.FrameReceived(
                        result.getOrNull() ?: throw (result.exceptionOrNull()
                            ?: EOFException("Batch protocol input closed while awaiting a frame")),
                    )
                }
                cancellations.onReceiveCatching { result ->
                    SenderEvent.LocalCancellation(
                        result.getOrNull() ?: throw (result.exceptionOrNull()
                            ?: CancellationException("Batch cancellation channel was closed")),
                    )
                }
                pendingRead.onAwait { SenderEvent.ReadCompleted(it) }
            }
        }
    }
}

