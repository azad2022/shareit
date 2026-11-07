package com.peyvand.core.transfer.session

import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.integrity.TransferIntegrityVerifier
import com.peyvand.core.transfer.integrity.VerifiedTransferReceipt
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.TransferChunkAcknowledgement
import com.peyvand.core.transfer.protocol.TransferChunkAcknowledgementCodec
import com.peyvand.core.transfer.protocol.TransferChunkSequenceValidator
import com.peyvand.core.transfer.protocol.TransferCompletionCodec
import com.peyvand.core.transfer.protocol.TransferCompletionRecord
import com.peyvand.core.transfer.protocol.TransferDataChunk
import com.peyvand.core.transfer.protocol.TransferDataChunkCodec
import com.peyvand.core.transfer.protocol.TransferFlowControlPolicy
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.protocol.TransferProtocolException
import com.peyvand.core.transfer.protocol.TransferSendWindow
import com.peyvand.core.transfer.stream.TransferStreamResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface TransferSessionOutcome {
    data class Completed(
        val manifest: TransferManifestItem,
        val receipt: VerifiedTransferReceipt,
    ) : TransferSessionOutcome

    data class Rejected(val manifest: TransferManifestItem) : TransferSessionOutcome

    data class Cancelled(val manifest: TransferManifestItem?) : TransferSessionOutcome
}

/**
 * Drives exactly one arbitrary file over an already authenticated, session-bound
 * TLS byte stream. This class does not establish sockets or own Android storage.
 *
 * One coroutine must own each direction of the channel. The sender uses a bounded
 * cumulative-ACK window; the receiver acknowledges only after its staging sink syncs.
 */
class SingleFileTransferProtocol(
    private val maximumFileSizeBytes: Long,
) {
    init {
        require(maximumFileSizeBytes >= 0L)
    }

    suspend fun sendOne(
        sessionId: UUID,
        manifest: TransferManifestItem,
        source: InputStream,
        channelInput: InputStream,
        channelOutput: OutputStream,
        onProgress: (Long) -> Unit = {},
        onWaitingForReceiverConsent: () -> Unit = {},
        onConsentAccepted: () -> Unit = {},
        onWaitingForVerification: () -> Unit = {},
    ): TransferSessionOutcome {
        require(manifest.sizeBytes == null || manifest.sizeBytes <= maximumFileSizeBytes) {
            "Manifest size exceeds the configured sender limit"
        }

        val input = BufferedInputStream(channelInput, IO_BUFFER_BYTES)
        val output = BufferedOutputStream(channelOutput, IO_BUFFER_BYTES)
        val context = currentCoroutineContext()
        val window = TransferSendWindow(
            expectedItemId = manifest.itemId,
            maximumSizeBytes = maximumFileSizeBytes,
        )
        val digest = MessageDigest.getInstance("SHA-256")
        val chunkBuffer = ByteArray(TransferFrameCodec.MAX_DATA_CHUNK_BYTES)
        var totalBytes = 0L

        writeFrame(
            output,
            sessionId,
            TransferFrameType.MANIFEST,
            com.peyvand.core.transfer.manifest.TransferManifestCodec.encode(manifest),
        )
        onWaitingForReceiverConsent()

        try {
            val decision = readSessionFrame(input, sessionId)
            when (decision.type) {
                TransferFrameType.REJECT -> {
                    requireEmptyPayload(decision)
                    return TransferSessionOutcome.Rejected(manifest)
                }
                TransferFrameType.CANCEL -> {
                    requireEmptyPayload(decision)
                    return TransferSessionOutcome.Cancelled(manifest)
                }
                TransferFrameType.ACCEPT -> requireEmptyPayload(decision)
                else -> throw TransferProtocolException("Expected receiver consent response")
            }
            onConsentAccepted()

            while (true) {
                context.ensureActive()
                if (!window.hasCapacity) {
                    output.flush()
                    onProgress(receiveAcknowledgement(input, sessionId, window))
                }

                val count = readChunk(source, chunkBuffer)
                if (count < 0) break
                val countLong = count.toLong()
                if (countLong > maximumFileSizeBytes - totalBytes ||
                    (manifest.sizeBytes != null && countLong > manifest.sizeBytes - totalBytes)
                ) {
                    writeError(output, sessionId)
                    throw TransferProtocolException("Source exceeds the permitted manifest size")
                }

                val chunkBytes = chunkBuffer.copyOf(count)
                val chunk = TransferDataChunk(manifest.itemId, totalBytes, chunkBytes)
                if (!window.tryReserve(chunk)) {
                    throw TransferProtocolException("Sender window rejected a chunk despite available capacity")
                }
                TransferFrameCodec.write(
                    TransferFrame(
                        protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                        type = TransferFrameType.DATA_CHUNK,
                        sessionId = sessionId,
                        payload = TransferDataChunkCodec.encodePayload(chunk),
                    ),
                    output,
                )
                digest.update(chunkBytes)
                totalBytes += countLong

                if (!window.hasCapacity) {
                    output.flush()
                    onProgress(receiveAcknowledgement(input, sessionId, window))
                }
            }

            if (manifest.sizeBytes != null && totalBytes != manifest.sizeBytes) {
                writeError(output, sessionId)
                throw TransferProtocolException("Source length differs from the declared manifest size")
            }

            output.flush()
            val senderCompletion = TransferCompletionRecord(
                itemId = manifest.itemId,
                actualSizeBytes = totalBytes,
                sha256Hex = digest.digest().toLowerHex(),
            )
            // Send the terminal byte count/digest before waiting for the final partial
            // receive window. The receiver uses ITEM_COMPLETE to flush and ACK that tail.
            writeFrame(
                output,
                sessionId,
                TransferFrameType.ITEM_COMPLETE,
                TransferCompletionCodec.encode(senderCompletion),
            )
            onWaitingForVerification()

            while (window.inFlightChunkCount > 0) {
                context.ensureActive()
                onProgress(receiveAcknowledgement(input, sessionId, window))
            }

            val receiverFrame = readSessionFrame(input, sessionId)
            if (receiverFrame.type == TransferFrameType.CANCEL) {
                requireEmptyPayload(receiverFrame)
                return TransferSessionOutcome.Cancelled(manifest)
            }
            if (receiverFrame.type == TransferFrameType.ERROR) {
                throw TransferProtocolException("Receiver could not publish the verified file")
            }
            if (receiverFrame.type != TransferFrameType.ITEM_VERIFIED) {
                throw TransferProtocolException("Expected authenticated integrity receipt")
            }

            val receiptRecord = TransferCompletionCodec.decode(receiverFrame.payloadBytes())
            val localResult = TransferStreamResult(totalBytes, senderCompletion.sha256Hex)
            val receipt = TransferIntegrityVerifier.verify(
                manifest = manifest,
                localStreamResult = localResult,
                remoteRecord = receiptRecord,
            )
            return TransferSessionOutcome.Completed(manifest, receipt)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            runCatching { writeFrame(output, sessionId, TransferFrameType.CANCEL, byteArrayOf()) }
            throw cancelled
        } catch (exception: Exception) {
            runCatching { writeError(output, sessionId) }
            throw exception
        }
    }

    suspend fun receiveOne(
        sessionId: UUID,
        channelInput: InputStream,
        channelOutput: OutputStream,
        acquireConsentAndStaging: suspend (TransferManifestItem) -> TransferStagingSink?,
        publishVerified: suspend (
            TransferManifestItem,
            VerifiedTransferReceipt,
            TransferStagingSink,
        ) -> Boolean,
        onProgress: (Long) -> Unit = {},
        onVerifying: () -> Unit = {},
    ): TransferSessionOutcome {
        val input = BufferedInputStream(channelInput, IO_BUFFER_BYTES)
        val output = BufferedOutputStream(channelOutput, IO_BUFFER_BYTES)
        val context = currentCoroutineContext()
        val manifestFrame = readSessionFrame(input, sessionId)
        if (manifestFrame.type != TransferFrameType.MANIFEST) {
            throw TransferProtocolException("Expected a file manifest")
        }
        val manifest = com.peyvand.core.transfer.manifest.TransferManifestCodec
            .decode(manifestFrame.payloadBytes())

        if (manifest.sizeBytes != null && manifest.sizeBytes > maximumFileSizeBytes) {
            writeFrame(output, sessionId, TransferFrameType.REJECT, byteArrayOf())
            return TransferSessionOutcome.Rejected(manifest)
        }

        var sink: TransferStagingSink? = null
        var published = false
        try {
            val staged = acquireConsentAndStaging(manifest)
            if (staged == null) {
                writeFrame(output, sessionId, TransferFrameType.REJECT, byteArrayOf())
                return TransferSessionOutcome.Rejected(manifest)
            }
            sink = staged

            writeFrame(output, sessionId, TransferFrameType.ACCEPT, byteArrayOf())
            val validator = TransferChunkSequenceValidator(
                expectedItemId = manifest.itemId,
                maximumSizeBytes = maximumFileSizeBytes,
            )
            val digest = MessageDigest.getInstance("SHA-256")
            val pendingChunks = ArrayList<TransferDataChunk>(
                TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS,
            )
            var receivedOffsetBytes = 0L
            var totalBytes = 0L
            var previousChunkSizeBytes: Int? = null

            fun commitPendingChunks() {
                if (pendingChunks.isEmpty()) return
                // Persist the whole bounded receive window before advancing the
                // validator's committed offset or acknowledging any of its bytes.
                staged.sync()
                var acknowledgement: TransferChunkAcknowledgement? = null
                pendingChunks.forEach { chunk ->
                    acknowledgement = validator.commit(chunk)
                }
                val committed = acknowledgement
                    ?: throw TransferProtocolException("A non-empty receive window produced no acknowledgement")
                writeFrame(
                    output,
                    sessionId,
                    TransferFrameType.CHUNK_ACK,
                    TransferChunkAcknowledgementCodec.encode(committed),
                )
                onProgress(committed.nextExpectedOffset)
                pendingChunks.clear()
            }

            while (true) {
                context.ensureActive()
                val frame = readSessionFrame(input, sessionId)
                when (frame.type) {
                    TransferFrameType.DATA_CHUNK -> {
                        val chunk = TransferDataChunkCodec.decodePayload(frame.payloadBytes())
                        if (previousChunkSizeBytes != null &&
                            previousChunkSizeBytes != TransferFrameCodec.MAX_DATA_CHUNK_BYTES
                        ) {
                            throw TransferProtocolException(
                                "Only the final data chunk may be shorter than the configured chunk size",
                            )
                        }
                        if (chunk.itemId != manifest.itemId) {
                            throw TransferProtocolException("Chunk belongs to a different transfer item")
                        }
                        if (chunk.offsetBytes != receivedOffsetBytes) {
                            throw TransferProtocolException(
                                "Non-contiguous receive offset: expected ${receivedOffsetBytes}, received ${chunk.offsetBytes}",
                            )
                        }
                        if (chunk.sizeBytes.toLong() > maximumFileSizeBytes - receivedOffsetBytes) {
                            throw TransferProtocolException("Chunk exceeds receiver transfer-size limit")
                        }
                        if (pendingChunks.size >= TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS) {
                            throw TransferProtocolException("Sender exceeded the receiver's bounded in-flight window")
                        }

                        val bytes = chunk.bytes()
                        staged.write(bytes, 0, bytes.size)
                        digest.update(bytes)
                        totalBytes += bytes.size.toLong()
                        receivedOffsetBytes = totalBytes
                        previousChunkSizeBytes = chunk.sizeBytes
                        pendingChunks += chunk

                        if (pendingChunks.size == TransferFlowControlPolicy.MAX_IN_FLIGHT_CHUNKS) {
                            commitPendingChunks()
                        }
                    }

                    TransferFrameType.ITEM_COMPLETE -> {
                        onVerifying()
                        commitPendingChunks()
                        val senderCompletion = TransferCompletionCodec.decode(frame.payloadBytes())
                        val localResult = TransferStreamResult(
                            bytesTransferred = totalBytes,
                            sha256Hex = digest.digest().toLowerHex(),
                        )
                        val receipt = TransferIntegrityVerifier.verify(
                            manifest = manifest,
                            localStreamResult = localResult,
                            remoteRecord = senderCompletion,
                        )
                        staged.seal()
                        if (!publishVerified(manifest, receipt, staged)) {
                            writeFrame(output, sessionId, TransferFrameType.CANCEL, byteArrayOf())
                            staged.delete()
                            return TransferSessionOutcome.Cancelled(manifest)
                        }
                        published = true
                        val verifiedRecord = TransferCompletionRecord(
                            itemId = manifest.itemId,
                            actualSizeBytes = receipt.actualSizeBytes,
                            sha256Hex = receipt.sha256Hex,
                        )
                        writeFrame(
                            output,
                            sessionId,
                            TransferFrameType.ITEM_VERIFIED,
                            TransferCompletionCodec.encode(verifiedRecord),
                        )
                        return TransferSessionOutcome.Completed(manifest, receipt)
                    }

                    TransferFrameType.CANCEL -> {
                        requireEmptyPayload(frame)
                        staged.delete()
                        return TransferSessionOutcome.Cancelled(manifest)
                    }

                    TransferFrameType.ERROR -> {
                        throw TransferProtocolException("Sender reported a transfer error")
                    }

                    else -> throw TransferProtocolException("Unexpected frame during file reception")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (!published) runCatching { sink?.delete() }
            runCatching { writeFrame(output, sessionId, TransferFrameType.CANCEL, byteArrayOf()) }
            throw cancelled
        } catch (exception: Exception) {
            if (!published) runCatching { sink?.delete() }
            runCatching { writeError(output, sessionId) }
            throw exception
        } finally {
            runCatching { sink?.close() }
        }
    }

    private suspend fun receiveAcknowledgement(
        input: InputStream,
        sessionId: UUID,
        window: TransferSendWindow,
    ): Long {
        val frame = readSessionFrame(input, sessionId)
        if (frame.type == TransferFrameType.ERROR) {
            throw TransferProtocolException("Receiver rejected a data chunk")
        }
        if (frame.type != TransferFrameType.CHUNK_ACK) {
            throw TransferProtocolException("Expected a cumulative chunk acknowledgement")
        }
        val acknowledgement = TransferChunkAcknowledgementCodec.decode(frame.payloadBytes())
        window.acknowledge(acknowledgement)
        return window.acknowledgedOffsetBytes
    }

    private suspend fun readChunk(source: InputStream, buffer: ByteArray): Int {
        val context = currentCoroutineContext()
        var count = 0
        while (count < buffer.size) {
            context.ensureActive()
            val read = source.read(buffer, count, buffer.size - count)
            when {
                read < 0 -> return count.takeIf { it > 0 } ?: -1
                read == 0 -> {
                    val single = source.read()
                    if (single < 0) return count.takeIf { it > 0 } ?: -1
                    buffer[count++] = single.toByte()
                }
                else -> count += read
            }
        }
        return count
    }

    private fun readSessionFrame(input: InputStream, sessionId: UUID): TransferFrame {
        val frame = TransferFrameCodec.read(input)
            ?: throw TransferProtocolException("Unexpected end of authenticated transfer stream")
        if (frame.sessionId != sessionId) {
            throw TransferProtocolException("Frame belongs to a different authenticated session")
        }
        return frame
    }

    private fun writeFrame(
        output: OutputStream,
        sessionId: UUID,
        type: TransferFrameType,
        payload: ByteArray,
    ) {
        TransferFrameCodec.write(
            TransferFrame(
                protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                type = type,
                sessionId = sessionId,
                payload = payload,
            ),
            output,
        )
        output.flush()
    }

    private fun writeError(output: OutputStream, sessionId: UUID) {
        writeFrame(output, sessionId, TransferFrameType.ERROR, byteArrayOf())
    }

    private fun requireEmptyPayload(frame: TransferFrame) {
        if (frame.payloadSize != 0) {
            throw TransferProtocolException("Control frame must not contain a payload")
        }
    }

    private fun ByteArray.toLowerHex(): String {
        val alphabet = "0123456789abcdef"
        val chars = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xff
            chars[index * 2] = alphabet[value ushr 4]
            chars[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(chars)
    }

    private companion object {
        const val IO_BUFFER_BYTES =
            TransferFlowControlPolicy.MAX_IN_FLIGHT_BYTES + TransferFrameCodec.HEADER_BYTES
    }
}
