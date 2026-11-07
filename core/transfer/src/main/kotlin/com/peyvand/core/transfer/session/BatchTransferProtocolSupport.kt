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

internal class BatchFrameReader(
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

internal sealed interface SenderEvent {
    data class FrameReceived(val frame: TransferFrame) : SenderEvent
    data class LocalCancellation(val itemId: UUID) : SenderEvent
    data class ReadCompleted(val result: Result<ByteArray?>) : SenderEvent
}

internal sealed interface ReceiverEvent {
    data class FrameReceived(val frame: TransferFrame) : ReceiverEvent
    data class LocalCancellation(val itemId: UUID) : ReceiverEvent
}


internal const val IO_BUFFER_BYTES = 8 * 1024
internal const val INBOUND_FRAME_CAPACITY = TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS + 2
internal val BATCH_ABORT_ITEM_ID = UUID(0L, 0L)

internal fun addOutcome(
    outcomes: MutableList<BatchItemOutcome>,
    outcome: BatchItemOutcome,
    onItemOutcome: (BatchItemOutcome) -> Unit,
) {
    outcomes += outcome
    onItemOutcome(outcome)
}

internal fun cancelledResult(manifest: BatchTransferManifest): BatchTransferResult {
    val outcomes = manifest.items.map { BatchItemOutcome(it.itemId, BatchItemOutcomeStatus.CANCELLED) }
    val digest = BatchOutcomeDigest.compute(
        manifest.batchId,
        manifest.items.map(TransferManifestItem::itemId),
        outcomes,
    )
    return BatchTransferResult(outcomes, digest)
}

internal fun rejectedResult(manifest: BatchTransferManifest): BatchTransferResult {
    val outcomes = manifest.items.map { BatchItemOutcome(it.itemId, BatchItemOutcomeStatus.REJECTED) }
    val digest = BatchOutcomeDigest.compute(
        manifest.batchId,
        manifest.items.map(TransferManifestItem::itemId),
        outcomes,
    )
    return BatchTransferResult(outcomes, digest)
}

internal fun requireType(frame: TransferFrame, expected: TransferFrameType) {
    if (frame.type != expected) {
        throw TransferProtocolException("Expected " + expected + ", received " + frame.type)
    }
}

internal fun requireReference(frame: TransferFrame, expected: BatchItemReference) {
    requireBatchReference(
        BatchItemReferenceCodec.decode(frame.payloadBytes()),
        expected.batchId,
        expected.itemId,
    )
}

internal fun requireBatchReference(actual: BatchItemReference, batchId: UUID, itemId: UUID) {
    if (actual.batchId != batchId || actual.itemId != itemId) {
        throw TransferProtocolException("Batch frame belongs to a different batch or item")
    }
}

internal fun decodeFailure(frame: TransferFrame, expected: BatchItemReference): BatchItemFailureRecord {
    val failure = BatchItemFailureCodec.decode(frame.payloadBytes())
    requireBatchReference(
        BatchItemReference(failure.batchId, failure.itemId),
        expected.batchId,
        expected.itemId,
    )
    return failure
}

internal fun writeFrame(
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

internal fun writeReference(
    output: OutputStream,
    sessionId: UUID,
    type: TransferFrameType,
    reference: BatchItemReference,
) = writeFrame(output, sessionId, type, BatchItemReferenceCodec.encode(reference))

internal fun writeFailure(
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

internal fun sendBatchAck(
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

internal suspend fun awaitReference(
    reader: BatchFrameReader,
    sessionId: UUID,
    type: TransferFrameType,
    reference: BatchItemReference,
) {
    val frame = reader.next(sessionId)
    requireType(frame, type)
    requireReference(frame, reference)
}

internal fun readChunk(input: InputStream): ByteArray? {
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

internal fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        chars[index * 2] = alphabet[value ushr 4]
        chars[index * 2 + 1] = alphabet[value and 0x0f]
    }
    return String(chars)
}

