package com.peyvand.core.transfer.session

import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.domain.connection.PeerConnectivityRepository
import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import com.peyvand.core.domain.transfer.TransferFileStorageRepository
import com.peyvand.core.transfer.manifest.TransferManifestItem
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class TransferSessionPhase {
    PREPARING,
    READY_TO_SEND,
    WAITING_FOR_INCOMING,
    WAITING_FOR_CONSENT,
    TRANSFERRING,
    VERIFYING,
    AWAITING_DESTINATION,
    PUBLISHING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
}

enum class TransferSessionError {
    SELECTION_REQUIRED,
    UNSUPPORTED_SOURCE,
    CHANNEL_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    TRANSFER_FAILED,
}

data class TransferSessionSnapshot(
    val phase: TransferSessionPhase = TransferSessionPhase.PREPARING,
    val isSender: Boolean = false,
    val displayName: String? = null,
    val mimeType: String? = null,
    val totalSizeBytes: Long? = null,
    val transferredBytes: Long = 0L,
    val error: TransferSessionError? = null,
    val destinationPickerRequestId: Int = 0,
)

/**
 * Owns the end-to-end transfer use case. Presentation observes [state] and submits
 * user decisions; this coordinator alone claims the authenticated channel, opens
 * URI sources, stages incoming bytes and invokes the wire protocol.
 */
class SingleFileTransferCoordinator(
    private val scope: CoroutineScope,
    private val connectivityRepository: PeerConnectivityRepository,
    private val storageRepository: TransferFileStorageRepository,
) : AutoCloseable {

    private val mutableState = MutableStateFlow(TransferSessionSnapshot())
    val state = mutableState.asStateFlow()

    @Volatile
    private var operationId = 0L
    private var prepared = false
    private var selectedItem: ShareItem? = null
    @Volatile
    private var authenticatedChannel: AuthenticatedPeerChannel? = null
    @Volatile
    private var activeSourceStream: InputStream? = null
    private var transferJob: Job? = null

    @Volatile
    private var consentDecision: CompletableDeferred<Boolean>? = null

    @Volatile
    private var destinationDecision: CompletableDeferred<String?>? = null

    fun prepareSession(
        isSender: Boolean,
        selectedItems: List<ShareItem>,
    ) {
        if (prepared) return
        prepared = true
        val operation = ++operationId

        if (isSender) {
            val item = selectedItems.singleOrNull()
            if (item == null) {
                update(operation) {
                    it.copy(
                        phase = TransferSessionPhase.FAILED,
                        isSender = true,
                        error = TransferSessionError.SELECTION_REQUIRED,
                    )
                }
                return
            }
            if (item.source !is ShareSource.ContentUri) {
                update(operation) {
                    it.copy(
                        phase = TransferSessionPhase.FAILED,
                        isSender = true,
                        displayName = item.displayName,
                        error = TransferSessionError.UNSUPPORTED_SOURCE,
                    )
                }
                return
            }
            selectedItem = item
        }

        val channel = connectivityRepository.claimAuthenticatedChannel()
        if (channel == null) {
            update(operation) {
                it.copy(
                    phase = TransferSessionPhase.FAILED,
                    isSender = isSender,
                    error = TransferSessionError.CHANNEL_UNAVAILABLE,
                )
            }
            return
        }

        authenticatedChannel = channel
        if (isSender) {
            val item = selectedItem ?: return
            mutableState.value = TransferSessionSnapshot(
                phase = TransferSessionPhase.READY_TO_SEND,
                isSender = true,
                displayName = item.displayName,
                mimeType = item.mimeType,
                totalSizeBytes = item.sizeBytes,
            )
        } else {
            mutableState.value = TransferSessionSnapshot(
                phase = TransferSessionPhase.WAITING_FOR_INCOMING,
                isSender = false,
            )
            transferJob = scope.launch(Dispatchers.IO) {
                receiveOne(channel, operation)
            }
        }
    }

    fun startSending() {
        if (mutableState.value.phase != TransferSessionPhase.READY_TO_SEND) return
        val channel = authenticatedChannel ?: return
        val item = selectedItem ?: return
        val operation = operationId
        transferJob = scope.launch(Dispatchers.IO) {
            sendOne(channel, item, operation)
        }
    }

    fun acceptIncoming() {
        consentDecision?.complete(true)
    }

    fun rejectIncoming() {
        consentDecision?.complete(false)
    }

    fun provideDestination(uri: String?) {
        destinationDecision?.complete(uri)
    }

    fun cancelTransfer() {
        if (!mutableState.value.phase.isTerminal()) {
            mutableState.update {
                it.copy(phase = TransferSessionPhase.CANCELLED, error = null)
            }
        }
        consentDecision?.complete(false)
        destinationDecision?.complete(null)
        operationId += 1L
        closeTransport()
        transferJob?.cancel()
    }

    fun leaveSession() {
        consentDecision?.complete(false)
        destinationDecision?.complete(null)
        operationId += 1L
        closeTransport()
        transferJob?.cancel()
        transferJob = null
        selectedItem = null
        prepared = false
        mutableState.value = TransferSessionSnapshot()
    }

    override fun close() {
        consentDecision?.complete(false)
        destinationDecision?.complete(null)
        operationId += 1L
        closeTransport()
        transferJob?.cancel()
    }

    private suspend fun sendOne(
        channel: AuthenticatedPeerChannel,
        item: ShareItem,
        operation: Long,
    ) {
        try {
            val source = storageRepository.openSource(item)
            source.use { transferSource ->
                val metadata = transferSource.metadata
                val manifest = TransferManifestItem(
                    itemId = UUID.randomUUID(),
                    displayName = metadata.displayName,
                    mimeType = metadata.mimeType,
                    sizeBytes = metadata.sizeBytes,
                )
                val protocol = SingleFileTransferProtocol(Long.MAX_VALUE)
                val outcome = try {
                    protocol.sendOne(
                        sessionId = channel.sessionId,
                        manifest = manifest,
                        openSource = {
                            transferSource.openStream().also { activeSourceStream = it }
                        },
                        channelInput = channel.inputStream,
                        channelOutput = channel.outputStream,
                        onWaitingForReceiverConsent = {
                            update(operation) {
                                it.copy(
                                    phase = TransferSessionPhase.WAITING_FOR_CONSENT,
                                    displayName = manifest.displayName,
                                    mimeType = manifest.mimeType,
                                    totalSizeBytes = manifest.sizeBytes,
                                    transferredBytes = 0L,
                                    error = null,
                                )
                            }
                        },
                        onConsentAccepted = {
                            update(operation) {
                                it.copy(phase = TransferSessionPhase.TRANSFERRING)
                            }
                        },
                        onWaitingForVerification = {
                            update(operation) {
                                it.copy(phase = TransferSessionPhase.VERIFYING)
                            }
                        },
                        onProgress = { bytes ->
                            update(operation) {
                                it.copy(
                                    phase = if (it.phase == TransferSessionPhase.VERIFYING) {
                                        TransferSessionPhase.VERIFYING
                                    } else {
                                        TransferSessionPhase.TRANSFERRING
                                    },
                                    transferredBytes = bytes,
                                )
                            }
                        },
                    )
                } finally {
                    activeSourceStream = null
                }
                applyOutcome(outcome, operation)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            update(operation) {
                it.copy(
                    phase = TransferSessionPhase.FAILED,
                    error = TransferSessionError.TRANSFER_FAILED,
                )
            }
        } finally {
            if (operation == operationId) closeTransport()
        }
    }

    private suspend fun receiveOne(
        channel: AuthenticatedPeerChannel,
        operation: Long,
    ) {
        try {
            val maximumBytes = storageRepository.maximumReceiveSizeBytes()
            val protocol = SingleFileTransferProtocol(maximumBytes)
            val outcome = protocol.receiveOne(
                sessionId = channel.sessionId,
                channelInput = channel.inputStream,
                channelOutput = channel.outputStream,
                acquireConsentAndStaging = { manifest ->
                    update(operation) {
                        it.copy(
                            phase = TransferSessionPhase.WAITING_FOR_CONSENT,
                            displayName = manifest.displayName,
                            mimeType = manifest.mimeType,
                            totalSizeBytes = manifest.sizeBytes,
                            transferredBytes = 0L,
                            error = null,
                        )
                    }
                    val decision = CompletableDeferred<Boolean>()
                    consentDecision = decision
                    val accepted = try {
                        withTimeout(USER_CONSENT_TIMEOUT_MILLIS) { decision.await() }
                    } finally {
                        if (consentDecision === decision) consentDecision = null
                    }
                    if (!accepted) {
                        null
                    } else {
                        val stagingLimit = manifest.sizeBytes ?: maximumBytes
                        val staging = storageRepository.createPrivateStaging(stagingLimit)
                        update(operation) {
                            it.copy(phase = TransferSessionPhase.TRANSFERRING)
                        }
                        staging
                    }
                },
                publishVerified = { manifest, receipt, staging ->
                    val requestId = mutableState.value.destinationPickerRequestId + 1
                    update(operation) {
                        it.copy(
                            phase = TransferSessionPhase.AWAITING_DESTINATION,
                            displayName = manifest.displayName,
                            mimeType = manifest.mimeType,
                            totalSizeBytes = receipt.actualSizeBytes,
                            transferredBytes = receipt.actualSizeBytes,
                            destinationPickerRequestId = requestId,
                        )
                    }
                    val destination = CompletableDeferred<String?>()
                    destinationDecision = destination
                    val destinationUri = try {
                        withTimeout(DESTINATION_SELECTION_TIMEOUT_MILLIS) {
                            destination.await()
                        }
                    } finally {
                        if (destinationDecision === destination) destinationDecision = null
                    }

                    if (destinationUri.isNullOrBlank()) {
                        false
                    } else {
                        update(operation) {
                            it.copy(phase = TransferSessionPhase.PUBLISHING)
                        }
                        storageRepository.publishVerified(
                            staging = staging,
                            destinationUri = destinationUri,
                            displayName = manifest.displayName,
                            mimeType = manifest.mimeType,
                            expectedSizeBytes = receipt.actualSizeBytes,
                            expectedSha256Hex = receipt.sha256Hex,
                        )
                    }
                },
                onProgress = { bytes ->
                    update(operation) {
                        it.copy(
                            phase = if (it.phase == TransferSessionPhase.VERIFYING) {
                                TransferSessionPhase.VERIFYING
                            } else {
                                TransferSessionPhase.TRANSFERRING
                            },
                            transferredBytes = bytes,
                        )
                    }
                },
                onVerifying = {
                    update(operation) {
                        it.copy(phase = TransferSessionPhase.VERIFYING)
                    }
                },
            )

            if (outcome is TransferSessionOutcome.Rejected &&
                outcome.manifest.sizeBytes != null &&
                outcome.manifest.sizeBytes > maximumBytes
            ) {
                update(operation) {
                    it.copy(
                        phase = TransferSessionPhase.FAILED,
                        displayName = outcome.manifest.displayName,
                        totalSizeBytes = outcome.manifest.sizeBytes,
                        error = TransferSessionError.INSUFFICIENT_STORAGE,
                    )
                }
            } else {
                applyOutcome(outcome, operation)
            }
        } catch (cancellation: CancellationException) {
            update(operation) {
                if (it.phase.isTerminal()) it
                else it.copy(phase = TransferSessionPhase.CANCELLED, error = null)
            }
            throw cancellation
        } catch (_: Exception) {
            update(operation) {
                it.copy(
                    phase = TransferSessionPhase.FAILED,
                    error = TransferSessionError.TRANSFER_FAILED,
                )
            }
        } finally {
            if (operation == operationId) closeTransport()
        }
    }

    private fun applyOutcome(
        outcome: TransferSessionOutcome,
        operation: Long,
    ) {
        when (outcome) {
            is TransferSessionOutcome.Completed -> update(operation) {
                it.copy(
                    phase = TransferSessionPhase.COMPLETED,
                    displayName = outcome.manifest.displayName,
                    mimeType = outcome.manifest.mimeType,
                    totalSizeBytes = outcome.receipt.actualSizeBytes,
                    transferredBytes = outcome.receipt.actualSizeBytes,
                    error = null,
                )
            }

            is TransferSessionOutcome.Rejected -> update(operation) {
                it.copy(
                    phase = TransferSessionPhase.REJECTED,
                    displayName = outcome.manifest.displayName,
                    error = null,
                )
            }

            is TransferSessionOutcome.Cancelled -> update(operation) {
                it.copy(phase = TransferSessionPhase.CANCELLED, error = null)
            }
        }
    }

    private fun update(
        operation: Long,
        transform: (TransferSessionSnapshot) -> TransferSessionSnapshot,
    ) {
        if (operation == operationId) {
            mutableState.update(transform)
        }
    }

    private fun closeTransport() {
        // Closing the current source stream interrupts a blocking ContentResolver read
        // before the transfer job is cancelled. Coroutine cancellation alone does not
        // reliably unblock a platform InputStream read.
        val source = activeSourceStream
        activeSourceStream = null
        runCatching { source?.close() }
        val channel = authenticatedChannel
        authenticatedChannel = null
        runCatching { channel?.close() }
        connectivityRepository.disconnect()
    }

    private fun TransferSessionPhase.isTerminal(): Boolean = when (this) {
        TransferSessionPhase.COMPLETED,
        TransferSessionPhase.REJECTED,
        TransferSessionPhase.CANCELLED,
        TransferSessionPhase.FAILED,
        -> true

        else -> false
    }

    private companion object {
        const val USER_CONSENT_TIMEOUT_MILLIS = 90_000L
        const val DESTINATION_SELECTION_TIMEOUT_MILLIS = 300_000L
    }
}
