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

data class BatchReceiverSelection(
    val acceptedItemIds: Set<UUID>,
    val destinationTreeUri: String?,
) {
    init {
        require(acceptedItemIds.isNotEmpty() || destinationTreeUri == null)
        require(destinationTreeUri == null || destinationTreeUri.isNotBlank())
    }
}

data class BatchTransferResult(
    val outcomes: List<BatchItemOutcome>,
    val outcomeDigestHex: String,
) {
    val completedCount: Int
        get() = outcomes.count { it.status == BatchItemOutcomeStatus.COMPLETED }

    val rejectedCount: Int
        get() = outcomes.count { it.status == BatchItemOutcomeStatus.REJECTED }

    val cancelledCount: Int
        get() = outcomes.count { it.status == BatchItemOutcomeStatus.CANCELLED }

    val failedCount: Int
        get() = outcomes.count { it.status == BatchItemOutcomeStatus.FAILED }

    val hasPartialOutcome: Boolean
        get() = failedCount > 0 || rejectedCount > 0 || cancelledCount > 0
}

/**
 * Version-2 batch protocol. The input pump is the sole reader for the authenticated
 * stream and has a bounded queue. One item, source stream and staging sink are active
 * at a time. The caller retains ownership of the authenticated channel.
 */
class MultiItemTransferProtocol(
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
        onItemOutcome: (BatchItemOutcome) -> Unit = {},
    ): BatchTransferResult {
        val output = BufferedOutputStream(channelOutput, IO_BUFFER_BYTES)
        val reader = BatchFrameReader(channelInput, CoroutineScope(currentCoroutineContext()), sessionId)
        val outcomes = ArrayList<BatchItemOutcome>(manifest.items.size)

        try {
            val encodedManifest = BatchTransferManifestCodec.encode(manifest)
            writeFrame(output, sessionId, TransferFrameType.BATCH_MANIFEST, encodedManifest)

            val decisionFrame = reader.next(sessionId)
            if (decisionFrame.type == TransferFrameType.BATCH_ABORT) {
                val reference = BatchItemReferenceCodec.decode(decisionFrame.payloadBytes())
                requireBatchReference(reference, manifest.batchId, BATCH_ABORT_ITEM_ID)
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
                    TransferFrameType.BATCH_ITEM_CANCELLED_ACK -> {
                        requireReference(ready, reference)
                        addOutcome(outcomes, BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.CANCELLED), onItemOutcome)
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
                    runCatching { source.close() }
                    throw cancellation
                } catch (_: Exception) {
                    runCatching { source.close() }
                    val outcome = abortItem(reader, output, sessionId, reference, BatchItemFailureCode.SOURCE_UNAVAILABLE)
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
                    )
                } finally {
                    onActiveSourceChanged(item.itemId, null)
                    source.close()
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

    suspend fun receiveBatch(
        sessionId: UUID,
        firstManifestFrame: TransferFrame,
        channelInput: InputStream,
        channelOutput: OutputStream,
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
        onItemOutcome: (BatchItemOutcome) -> Unit = {},
    ): BatchTransferResult {
        if (firstManifestFrame.protocolVersion != TransferFrameCodec.BATCH_PROTOCOL_VERSION ||
            firstManifestFrame.sessionId != sessionId ||
            firstManifestFrame.type != TransferFrameType.BATCH_MANIFEST
        ) {
            throw TransferProtocolException("Invalid initial batch manifest frame")
        }

        val manifestBytes = firstManifestFrame.payloadBytes()
        val manifest = BatchTransferManifestCodec.decode(manifestBytes)
        val selection = acquireSelection(manifest)
        val output = BufferedOutputStream(channelOutput, IO_BUFFER_BYTES)

        if (selection == null) {
            writeReference(
                output,
                sessionId,
                TransferFrameType.BATCH_ABORT,
                BatchItemReference(manifest.batchId, BATCH_ABORT_ITEM_ID),
            )
            return cancelledResult(manifest)
        }

        val manifestIds = manifest.items.mapTo(HashSet(), TransferManifestItem::itemId)
        if (!manifestIds.containsAll(selection.acceptedItemIds)) {
            throw TransferProtocolException("Receiver selected an item outside the batch manifest")
        }
        if (selection.acceptedItemIds.isNotEmpty() && selection.destinationTreeUri.isNullOrBlank()) {
            throw TransferProtocolException("Accepted batch items require a user-selected SAF tree")
        }

        val acceptedMask = manifest.items.foldIndexed(0L) { index, mask, item ->
            if (item.itemId in selection.acceptedItemIds) mask or (1L shl index) else mask
        }
        val decision = BatchConsentDecision(
            manifest.batchId,
            BatchTransferManifestCodec.digest(manifestBytes),
            acceptedMask,
        )
        writeFrame(output, sessionId, TransferFrameType.BATCH_DECISION, BatchConsentDecisionCodec.encode(decision))

        val reader = BatchFrameReader(channelInput, CoroutineScope(currentCoroutineContext()))
        val outcomes = ArrayList<BatchItemOutcome>(manifest.items.size)
        try {
            manifest.items.forEach { item ->
                if (item.itemId !in selection.acceptedItemIds) {
                    addOutcome(outcomes, BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.REJECTED), onItemOutcome)
                    return@forEach
                }

                val reference = BatchItemReference(manifest.batchId, item.itemId)
                val start = reader.next(sessionId)
                if (start.type == TransferFrameType.BATCH_ITEM_CANCELLED) {
                    requireReference(start, reference)
                    writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED_ACK, reference)
                    addOutcome(outcomes, BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.CANCELLED), onItemOutcome)
                    return@forEach
                }
                requireType(start, TransferFrameType.BATCH_ITEM_START)
                requireReference(start, reference)
                if (isCancelledBeforeStart(item.itemId)) {
                    writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED, reference)
                    awaitReference(reader, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED_ACK, reference)
                    addOutcome(
                        outcomes,
                        BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.CANCELLED),
                        onItemOutcome,
                    )
                    return@forEach
                }

                val staging = try {
                    acquireStaging(item)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    writeFailure(
                        output,
                        sessionId,
                        TransferFrameType.BATCH_ITEM_FAILED,
                        reference,
                        BatchItemFailureCode.STORAGE_UNAVAILABLE,
                    )
                    addOutcome(outcomes, BatchItemOutcome(item.itemId, BatchItemOutcomeStatus.FAILED), onItemOutcome)
                    return@forEach
                }

                val outcome = try {
                    writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_READY, reference)
                    receiveItem(
                        sessionId,
                        item,
                        reference,
                        staging,
                        requireNotNull(selection.destinationTreeUri),
                        reader,
                        output,
                        publishVerified,
                        cancellationRequests,
                        onItemProgress,
                    )
                } finally {
                    staging.close()
                }
                addOutcome(outcomes, outcome, onItemOutcome)
            }

            val digest = BatchOutcomeDigest.compute(
                manifest.batchId,
                manifest.items.map(TransferManifestItem::itemId),
                outcomes,
            )
            val terminalFrame = reader.next(sessionId)
            requireType(terminalFrame, TransferFrameType.BATCH_COMPLETE)
            val terminal = BatchTerminalRecordCodec.decode(terminalFrame.payloadBytes())
            val expected = BatchTerminalRecord(manifest.batchId, manifest.items.size, digest)
            if (terminal != expected) {
                throw TransferProtocolException("Sender batch summary does not match receiver outcomes")
            }
            writeFrame(output, sessionId, TransferFrameType.BATCH_RECEIPT, BatchTerminalRecordCodec.encode(expected))
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

    private suspend fun receiveItem(
        sessionId: UUID,
        item: TransferManifestItem,
        reference: BatchItemReference,
        staging: TransferStagingSink,
        destinationTreeUri: String,
        reader: BatchFrameReader,
        output: OutputStream,
        publishVerified: suspend (
            TransferManifestItem,
            TransferStagingSink,
            String,
            BatchItemCompletionRecord,
        ) -> Boolean,
        cancellationRequests: ReceiveChannel<UUID>,
        onItemProgress: (UUID, Long) -> Unit,
    ): BatchItemOutcome {
        val maximum = minOf(maximumFileSizeBytes, item.sizeBytes ?: maximumFileSizeBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        var nextExpectedOffset = 0L
        var pendingChunks = 0
        var lastChunkSize = 0

        while (true) {
            when (val event = awaitReceiverEvent(reader, cancellationRequests)) {
                is ReceiverEvent.LocalCancellation -> {
                    if (event.itemId != item.itemId) continue
                    writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_CANCEL_REQUEST, reference)
                    return drainItemAfterRequest(
                        sessionId,
                        reference,
                        staging,
                        reader,
                        output,
                    )
                }

                is ReceiverEvent.FrameReceived -> {
                    val frame = event.frame
                    when (frame.type) {
                        TransferFrameType.BATCH_DATA_CHUNK -> {
                            val chunk = BatchTransferDataChunkCodec.decode(frame.payloadBytes())
                            requireBatchReference(
                                BatchItemReference(chunk.batchId, chunk.itemId),
                                reference.batchId,
                                reference.itemId,
                            )
                            if (chunk.offsetBytes != nextExpectedOffset) {
                                throw TransferProtocolException("Batch chunk offset is not contiguous")
                            }
                            if (lastChunkSize in 1 until TransferFrameCodec.MAX_DATA_CHUNK_BYTES) {
                                throw TransferProtocolException("A short batch chunk was not terminal")
                            }
                            if (chunk.sizeBytes.toLong() > maximum - nextExpectedOffset ||
                                (item.sizeBytes != null &&
                                    chunk.sizeBytes.toLong() > item.sizeBytes - nextExpectedOffset)
                            ) {
                                throw TransferProtocolException("Batch item exceeds its declared size limit")
                            }

                            try {
                                val bytes = chunk.bytes()
                                staging.write(bytes, 0, bytes.size)
                                digest.update(bytes)
                                nextExpectedOffset += bytes.size.toLong()
                                pendingChunks += 1
                                lastChunkSize = bytes.size
                                if (pendingChunks == TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS) {
                                    staging.sync()
                                    sendBatchAck(output, sessionId, reference, nextExpectedOffset)
                                    pendingChunks = 0
                                    onItemProgress(item.itemId, nextExpectedOffset)
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                writeFailure(
                                    output,
                                    sessionId,
                                    TransferFrameType.BATCH_ITEM_FAILED_REQUEST,
                                    reference,
                                    BatchItemFailureCode.STORAGE_WRITE_FAILED,
                                )
                                return drainItemAfterRequest(
                                    sessionId,
                                    reference,
                                    staging,
                                    reader,
                                    output,
                                )
                            }
                        }

                        TransferFrameType.BATCH_ITEM_COMPLETE -> {
                            val completion = BatchItemCompletionCodec.decode(frame.payloadBytes())
                            requireBatchReference(
                                BatchItemReference(completion.batchId, completion.itemId),
                                reference.batchId,
                                reference.itemId,
                            )
                            try {
                                if (pendingChunks > 0) {
                                    staging.sync()
                                    sendBatchAck(output, sessionId, reference, nextExpectedOffset)
                                    pendingChunks = 0
                                    onItemProgress(item.itemId, nextExpectedOffset)
                                }
                                val localHash = digest.digest().toLowerHex()
                                val verified = TransferIntegrityVerifier.verify(
                                    item,
                                    TransferStreamResult(nextExpectedOffset, localHash),
                                    com.peyvand.core.transfer.protocol.TransferCompletionRecord(
                                        completion.itemId,
                                        completion.actualSizeBytes,
                                        completion.sha256Hex,
                                    ),
                                )
                                staging.seal()
                                if (!publishVerified(item, staging, destinationTreeUri, completion)) {
                                    throw IOException("Destination provider did not confirm publication")
                                }
                                writeFrame(
                                    output,
                                    sessionId,
                                    TransferFrameType.BATCH_ITEM_VERIFIED,
                                    BatchItemCompletionCodec.encode(
                                        BatchItemCompletionRecord(
                                            reference.batchId,
                                            reference.itemId,
                                            verified.actualSizeBytes,
                                            verified.sha256Hex,
                                        ),
                                    ),
                                )
                                return BatchItemOutcome(
                                    reference.itemId,
                                    BatchItemOutcomeStatus.COMPLETED,
                                    verified.actualSizeBytes,
                                    verified.sha256Hex,
                                )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                staging.delete()
                                writeFailure(
                                    output,
                                    sessionId,
                                    TransferFrameType.BATCH_ITEM_FAILED,
                                    reference,
                                    BatchItemFailureCode.DESTINATION_FAILED,
                                )
                                return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.FAILED)
                            }
                        }

                        TransferFrameType.BATCH_ITEM_ABORT -> {
                            decodeFailure(frame, reference)
                            staging.delete()
                            writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_FAILED_ACK, reference)
                            return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.FAILED)
                        }

                        TransferFrameType.BATCH_ITEM_CANCELLED -> {
                            requireReference(frame, reference)
                            staging.delete()
                            writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED_ACK, reference)
                            return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.CANCELLED)
                        }

                        else -> throw TransferProtocolException("Unexpected frame while receiving batch item")
                    }
                }
            }
        }
    }

    private suspend fun drainItemAfterRequest(
        sessionId: UUID,
        reference: BatchItemReference,
        staging: TransferStagingSink,
        reader: BatchFrameReader,
        output: OutputStream,
    ): BatchItemOutcome {
        while (true) {
            val frame = reader.next(sessionId)
            when (frame.type) {
                TransferFrameType.BATCH_DATA_CHUNK -> {
                    val chunk = BatchTransferDataChunkCodec.decode(frame.payloadBytes())
                    requireBatchReference(
                        BatchItemReference(chunk.batchId, chunk.itemId),
                        reference.batchId,
                        reference.itemId,
                    )
                }
                TransferFrameType.BATCH_ITEM_COMPLETE -> {
                    val completion = BatchItemCompletionCodec.decode(frame.payloadBytes())
                    requireBatchReference(
                        BatchItemReference(completion.batchId, completion.itemId),
                        reference.batchId,
                        reference.itemId,
                    )
                }
                TransferFrameType.BATCH_ITEM_ABORT -> {
                    decodeFailure(frame, reference)
                    staging.delete()
                    writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_FAILED_ACK, reference)
                    return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.FAILED)
                }
                TransferFrameType.BATCH_ITEM_CANCELLED -> {
                    requireReference(frame, reference)
                    staging.delete()
                    writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_CANCELLED_ACK, reference)
                    return BatchItemOutcome(reference.itemId, BatchItemOutcomeStatus.CANCELLED)
                }
                else -> throw TransferProtocolException("Unexpected frame while draining an item")
            }
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
                reader.frames.onReceiveCatching { SenderEvent.FrameReceived(it.getOrElse { cause -> throw cause }) }
                cancellations.onReceiveCatching { SenderEvent.LocalCancellation(it.getOrElse { cause -> throw cause }) }
            }
        } else {
            select {
                reader.frames.onReceiveCatching { SenderEvent.FrameReceived(it.getOrElse { cause -> throw cause }) }
                cancellations.onReceiveCatching { SenderEvent.LocalCancellation(it.getOrElse { cause -> throw cause }) }
                pendingRead.onAwait { SenderEvent.ReadCompleted(it) }
            }
        }
    }

    private suspend fun awaitReceiverEvent(
        reader: BatchFrameReader,
        cancellations: ReceiveChannel<UUID>,
    ): ReceiverEvent =
        select {
            reader.frames.onReceiveCatching { ReceiverEvent.FrameReceived(it.getOrElse { cause -> throw cause }) }
            cancellations.onReceiveCatching { ReceiverEvent.LocalCancellation(it.getOrElse { cause -> throw cause }) }
        }

    private class BatchFrameReader(
        input: InputStream,
        scope: CoroutineScope,
        private val expectedSessionId: UUID,
    ) {
        val frames = Channel<TransferFrame>(INBOUND_FRAME_CAPACITY)
        private val readerJob: Job = scope.launch(Dispatchers.IO) {
            val buffered = BufferedInputStream(input, IO_BUFFER_BYTES)
            try {
                while (isActive) {
                    val frame = TransferFrameCodec.read(buffered)
                        ?: throw EOFException("Authenticated peer closed the batch protocol stream")
                    if (frame.sessionId != expectedSessionId ||
                        frame.protocolVersion != TransferFrameCodec.BATCH_PROTOCOL_VERSION
                    ) {
                        throw TransferProtocolException("Frame does not belong to the active batch session")
                    }
                    frames.send(frame)
                }
            } catch (cancellation: CancellationException) {
                frames.close(cancellation)
                throw cancellation
            } catch (exception: Exception) {
                frames.close(exception)
            }
        }

        suspend fun next(sessionId: UUID): TransferFrame {
            val result = frames.receiveCatching()
            val frame = result.getOrNull() ?: throw (result.exceptionOrNull()
                ?: EOFException("Batch protocol stream ended"))
            if (frame.sessionId != sessionId) {
                throw TransferProtocolException("Frame belongs to a different authenticated session")
            }
            if (frame.protocolVersion != TransferFrameCodec.BATCH_PROTOCOL_VERSION) {
                throw TransferProtocolException("Unexpected protocol version inside a batch session")
            }
            return frame
        }

        fun close() {
            frames.cancel()
            readerJob.cancel()
        }
    }

    private sealed interface SenderEvent {
        data class FrameReceived(val frame: TransferFrame) : SenderEvent
        data class LocalCancellation(val itemId: UUID) : SenderEvent
        data class ReadCompleted(val result: Result<ByteArray?>) : SenderEvent
    }

    private sealed interface ReceiverEvent {
        data class FrameReceived(val frame: TransferFrame) : ReceiverEvent
        data class LocalCancellation(val itemId: UUID) : ReceiverEvent
    }

    private companion object {
        const val IO_BUFFER_BYTES = 8 * 1024
        const val INBOUND_FRAME_CAPACITY = TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS + 2
        val BATCH_ABORT_ITEM_ID = UUID(0L, 0L)

        fun addOutcome(
            outcomes: MutableList<BatchItemOutcome>,
            outcome: BatchItemOutcome,
            onItemOutcome: (BatchItemOutcome) -> Unit,
        ) {
            outcomes += outcome
            onItemOutcome(outcome)
        }

        fun cancelledResult(manifest: BatchTransferManifest): BatchTransferResult {
            val outcomes = manifest.items.map { BatchItemOutcome(it.itemId, BatchItemOutcomeStatus.CANCELLED) }
            val digest = BatchOutcomeDigest.compute(
                manifest.batchId,
                manifest.items.map(TransferManifestItem::itemId),
                outcomes,
            )
            return BatchTransferResult(outcomes, digest)
        }

        fun requireType(frame: TransferFrame, expected: TransferFrameType) {
            if (frame.type != expected) {
                throw TransferProtocolException("Expected " + expected + ", received " + frame.type)
            }
        }

        fun requireReference(frame: TransferFrame, expected: BatchItemReference) {
            requireBatchReference(
                BatchItemReferenceCodec.decode(frame.payloadBytes()),
                expected.batchId,
                expected.itemId,
            )
        }

        fun requireBatchReference(actual: BatchItemReference, batchId: UUID, itemId: UUID) {
            if (actual.batchId != batchId || actual.itemId != itemId) {
                throw TransferProtocolException("Batch frame belongs to a different batch or item")
            }
        }

        fun decodeFailure(frame: TransferFrame, expected: BatchItemReference): BatchItemFailureRecord {
            val failure = BatchItemFailureCodec.decode(frame.payloadBytes())
            requireBatchReference(
                BatchItemReference(failure.batchId, failure.itemId),
                expected.batchId,
                expected.itemId,
            )
            return failure
        }

        fun writeFrame(
            output: OutputStream,
            sessionId: UUID,
            type: TransferFrameType,
            payload: ByteArray,
        ) {
            TransferFrameCodec.write(
                TransferFrame(TransferFrameCodec.BATCH_PROTOCOL_VERSION, type, sessionId, payload),
                output,
            )
            output.flush()
        }

        fun writeReference(
            output: OutputStream,
            sessionId: UUID,
            type: TransferFrameType,
            reference: BatchItemReference,
        ) = writeFrame(output, sessionId, type, BatchItemReferenceCodec.encode(reference))

        fun writeFailure(
            output: OutputStream,
            sessionId: UUID,
            type: TransferFrameType,
            reference: BatchItemReference,
            failureCode: BatchItemFailureCode,
        ) = writeFrame(
            output,
            sessionId,
            type,
            BatchItemFailureCodec.encode(BatchItemFailureRecord(reference.batchId, reference.itemId, failureCode)),
        )

        fun sendBatchAck(
            output: OutputStream,
            sessionId: UUID,
            reference: BatchItemReference,
            nextExpectedOffset: Long,
        ) = writeFrame(
            output,
            sessionId,
            TransferFrameType.BATCH_CHUNK_ACK,
            BatchChunkAcknowledgementCodec.encode(
                BatchChunkAcknowledgement(reference.batchId, reference.itemId, nextExpectedOffset),
            ),
        )

        suspend fun awaitReference(
            reader: BatchFrameReader,
            sessionId: UUID,
            type: TransferFrameType,
            reference: BatchItemReference,
        ) {
            val frame = reader.next(sessionId)
            requireType(frame, type)
            requireReference(frame, reference)
        }

        fun readChunk(input: InputStream): ByteArray? {
            val buffer = ByteArray(TransferFrameCodec.MAX_DATA_CHUNK_BYTES)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                when {
                    read < 0 -> return if (count == 0) null else buffer.copyOf(count)
                    read == 0 -> {
                        val single = input.read()
                        if (single < 0) return if (count == 0) null else buffer.copyOf(count)
                        buffer[count++] = single.toByte()
                    }
                    else -> count += read
                }
            }
            return buffer
        }

        fun ByteArray.toLowerHex(): String {
            val alphabet = "0123456789abcdef"
            val chars = CharArray(size * 2)
            for (index in indices) {
                val value = this[index].toInt() and 0xff
                chars[index * 2] = alphabet[value ushr 4]
                chars[index * 2 + 1] = alphabet[value and 0x0f]
            }
            return String(chars)
        }
    }
}
