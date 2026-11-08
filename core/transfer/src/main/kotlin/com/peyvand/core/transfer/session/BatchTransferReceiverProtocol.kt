package com.peyvand.core.transfer.session

import com.peyvand.core.domain.transfer.TransferFileSource
import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.integrity.TransferIntegrityException
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

internal class BatchTransferReceiverProtocol(
    private val maximumFileSizeBytes: Long,
) {
    init {
        require(maximumFileSizeBytes >= 0L)
    }

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
    ): BatchTransferResult? {
        if (firstOfferFrame.protocolVersion != TransferFrameCodec.BATCH_PROTOCOL_VERSION ||
            firstOfferFrame.sessionId != sessionId ||
            firstOfferFrame.type != TransferFrameType.BATCH_OFFER
        ) {
            throw TransferProtocolException("Invalid initial batch offer frame")
        }

        val output = BufferedOutputStream(channelOutput, IO_BUFFER_BYTES)
        val offer = BatchOfferCodec.decode(firstOfferFrame.payloadBytes())
        if (!acquireOfferConsent(offer)) {
            writeFrame(
                output,
                sessionId,
                TransferFrameType.BATCH_ABORT,
                BatchOfferCodec.encode(offer),
            )
            return null
        }
        writeFrame(
            output,
            sessionId,
            TransferFrameType.BATCH_OFFER_ACCEPTED,
            BatchOfferCodec.encode(offer),
        )

        val firstManifestFrame = TransferFrameCodec.read(channelInput)
            ?: throw EOFException("Sender closed before revealing the batch manifest")
        if (firstManifestFrame.protocolVersion != TransferFrameCodec.BATCH_PROTOCOL_VERSION ||
            firstManifestFrame.sessionId != sessionId ||
            firstManifestFrame.type != TransferFrameType.BATCH_MANIFEST
        ) {
            throw TransferProtocolException("Expected the batch manifest after offer consent")
        }
        val manifestBytes = firstManifestFrame.payloadBytes()
        val manifest = BatchTransferManifestCodec.decode(manifestBytes)
        val selection = acquireSelection(manifest)

        if (selection == null) {
            writeFrame(
                output,
                sessionId,
                TransferFrameType.BATCH_ABORT,
                BatchOfferCodec.encode(BatchOffer(manifest.batchId)),
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

        val reader = BatchFrameReader(channelInput, CoroutineScope(currentCoroutineContext()), sessionId)
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
                    addOutcome(
                        outcomes,
                        BatchItemOutcome(
                            item.itemId,
                            BatchItemOutcomeStatus.FAILED,
                            failureCode = BatchItemFailureCode.STORAGE_UNAVAILABLE,
                        ),
                        onItemOutcome,
                    )
                    return@forEach
                }

                var itemFailure: Throwable? = null
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
                        onItemVerifying,
                    )
                } catch (failure: Throwable) {
                    itemFailure = failure
                    throw failure
                } finally {
                    var cleanupFailure: Throwable? = null
                    try {
                        // close() only closes the writer in the Android adapter. Delete
                        // explicitly so interrupted or fatal items cannot leave staging behind.
                        staging.delete()
                    } catch (failure: Throwable) {
                        cleanupFailure = failure
                    }
                    try {
                        staging.close()
                    } catch (failure: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = failure
                        else cleanupFailure.addSuppressed(failure)
                    }
                    if (cleanupFailure != null) {
                        val previousFailure = itemFailure
                        if (previousFailure != null) previousFailure.addSuppressed(cleanupFailure)
                        else throw cleanupFailure
                    }
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
        onItemVerifying: (UUID) -> Unit,
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

                            val bytes = chunk.bytes()
                            try {
                                staging.write(bytes, 0, bytes.size)
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
                                    requestedFailureCode = BatchItemFailureCode.STORAGE_WRITE_FAILED,
                                )
                            }
                            digest.update(bytes)
                            nextExpectedOffset += bytes.size.toLong()
                            pendingChunks += 1
                            lastChunkSize = bytes.size
                            if (pendingChunks == TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS) {
                                try {
                                    staging.sync()
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
                                        requestedFailureCode = BatchItemFailureCode.STORAGE_WRITE_FAILED,
                                    )
                                }
                                sendBatchAck(output, sessionId, reference, nextExpectedOffset)
                                pendingChunks = 0
                                onItemProgress(item.itemId, nextExpectedOffset)
                            }
                        }

                        TransferFrameType.BATCH_ITEM_COMPLETE -> {
                            val completion = BatchItemCompletionCodec.decode(frame.payloadBytes())
                            requireBatchReference(
                                BatchItemReference(completion.batchId, completion.itemId),
                                reference.batchId,
                                reference.itemId,
                            )
                            onItemVerifying(item.itemId)
                            if (pendingChunks > 0) {
                                try {
                                    staging.sync()
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {
                                    staging.delete()
                                    writeFailure(
                                        output,
                                        sessionId,
                                        TransferFrameType.BATCH_ITEM_FAILED,
                                        reference,
                                        BatchItemFailureCode.STORAGE_WRITE_FAILED,
                                    )
                                    return BatchItemOutcome(
                                        reference.itemId,
                                        BatchItemOutcomeStatus.FAILED,
                                        failureCode = BatchItemFailureCode.STORAGE_WRITE_FAILED,
                                    )
                                }
                                sendBatchAck(output, sessionId, reference, nextExpectedOffset)
                                pendingChunks = 0
                                onItemProgress(item.itemId, nextExpectedOffset)
                            }

                            val localHash = digest.digest().toLowerHex()
                            val verified = try {
                                TransferIntegrityVerifier.verify(
                                    item,
                                    TransferStreamResult(nextExpectedOffset, localHash),
                                    com.peyvand.core.transfer.protocol.TransferCompletionRecord(
                                        completion.itemId,
                                        completion.actualSizeBytes,
                                        completion.sha256Hex,
                                    ),
                                )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: TransferIntegrityException) {
                                staging.delete()
                                writeFailure(
                                    output,
                                    sessionId,
                                    TransferFrameType.BATCH_ITEM_FAILED,
                                    reference,
                                    BatchItemFailureCode.INTEGRITY_VERIFICATION_FAILED,
                                )
                                return BatchItemOutcome(
                                    reference.itemId,
                                    BatchItemOutcomeStatus.FAILED,
                                    failureCode = BatchItemFailureCode.INTEGRITY_VERIFICATION_FAILED,
                                )
                            }

                            try {
                                staging.seal()
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                staging.delete()
                                writeFailure(
                                    output,
                                    sessionId,
                                    TransferFrameType.BATCH_ITEM_FAILED,
                                    reference,
                                    BatchItemFailureCode.STAGING_FINALIZATION_FAILED,
                                )
                                return BatchItemOutcome(
                                    reference.itemId,
                                    BatchItemOutcomeStatus.FAILED,
                                    failureCode = BatchItemFailureCode.STAGING_FINALIZATION_FAILED,
                                )
                            }

                            try {
                                if (!publishVerified(item, staging, destinationTreeUri, completion)) {
                                    throw IOException("Destination provider did not confirm publication")
                                }
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
                                return BatchItemOutcome(
                                    reference.itemId,
                                    BatchItemOutcomeStatus.FAILED,
                                    failureCode = BatchItemFailureCode.DESTINATION_FAILED,
                                )
                            }

                            // Publication succeeded. If the authenticated receipt cannot
                            // be sent, treat that as a session/protocol failure, not as a
                            // destination failure: the verified file may already exist.
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
                        }

                        TransferFrameType.BATCH_ITEM_ABORT -> {
                            val failure = decodeFailure(frame, reference)
                            staging.delete()
                            writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_FAILED_ACK, reference)
                            return BatchItemOutcome(
                                reference.itemId,
                                BatchItemOutcomeStatus.FAILED,
                                failureCode = failure.failureCode,
                            )
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
        requestedFailureCode: BatchItemFailureCode? = null,
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
                    val failure = decodeFailure(frame, reference)
                    staging.delete()
                    writeReference(output, sessionId, TransferFrameType.BATCH_ITEM_FAILED_ACK, reference)
                    return BatchItemOutcome(
                        reference.itemId,
                        BatchItemOutcomeStatus.FAILED,
                        failureCode = requestedFailureCode ?: failure.failureCode,
                    )
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

    private suspend fun awaitReceiverEvent(
        reader: BatchFrameReader,
        cancellations: ReceiveChannel<UUID>,
    ): ReceiverEvent =
        select {
            reader.frames.onReceiveCatching { result ->
                ReceiverEvent.FrameReceived(
                    result.getOrNull() ?: throw (result.exceptionOrNull()
                        ?: EOFException("Batch protocol input closed while awaiting a frame")),
                )
            }
            cancellations.onReceiveCatching { result ->
                ReceiverEvent.LocalCancellation(
                    result.getOrNull() ?: throw (result.exceptionOrNull()
                        ?: CancellationException("Batch cancellation channel was closed")),
                )
            }
        }
}

