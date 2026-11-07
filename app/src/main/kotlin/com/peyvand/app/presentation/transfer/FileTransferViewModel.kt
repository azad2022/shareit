package com.peyvand.app.presentation.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.domain.connection.PeerConnectivityRepository
import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import com.peyvand.core.domain.transfer.TransferFileStorageRepository
import com.peyvand.core.domain.transfer.TransferStagingSink
import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.session.SingleFileTransferProtocol
import com.peyvand.core.transfer.session.TransferSessionOutcome
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FileTransferPhase {
    PREPARING,
    READY_TO_SEND,
    WAITING_FOR_INCOMING,
    WAITING_FOR_CONSENT,
    TRANSFERRING,
    AWAITING_DESTINATION,
    PUBLISHING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
}

enum class FileTransferError {
    SELECTION_REQUIRED,
    UNSUPPORTED_SOURCE,
    CHANNEL_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    TRANSFER_FAILED,
}

data class FileTransferUiState(
    val phase: FileTransferPhase = FileTransferPhase.PREPARING,
    val isSender: Boolean = false,
    val displayName: String? = null,
    val mimeType: String? = null,
    val totalSizeBytes: Long? = null,
    val transferredBytes: Long = 0L,
    val error: FileTransferError? = null,
    val destinationPickerRequestId: Int = 0,
)

class FileTransferViewModel(
    private val connectivityRepository: PeerConnectivityRepository,
    private val storageRepository: TransferFileStorageRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(FileTransferUiState())
    val state = mutableState.asStateFlow()

    private var operationId = 0L
    private var prepared = false
    private var selectedItem: ShareItem? = null
    private var authenticatedChannel: AuthenticatedPeerChannel? = null
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
                        phase = FileTransferPhase.FAILED,
                        isSender = true,
                        error = FileTransferError.SELECTION_REQUIRED,
                    )
                }
                return
            }
            if (item.source !is ShareSource.ContentUri) {
                update(operation) {
                    it.copy(
                        phase = FileTransferPhase.FAILED,
                        isSender = true,
                        displayName = item.displayName,
                        error = FileTransferError.UNSUPPORTED_SOURCE,
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
                    phase = FileTransferPhase.FAILED,
                    isSender = isSender,
                    error = FileTransferError.CHANNEL_UNAVAILABLE,
                )
            }
            return
        }

        authenticatedChannel = channel
        if (isSender) {
            val item = selectedItem ?: return
            mutableState.value = FileTransferUiState(
                phase = FileTransferPhase.READY_TO_SEND,
                isSender = true,
                displayName = item.displayName,
                mimeType = item.mimeType,
                totalSizeBytes = item.sizeBytes,
            )
        } else {
            mutableState.value = FileTransferUiState(
                phase = FileTransferPhase.WAITING_FOR_INCOMING,
                isSender = false,
            )
            transferJob = viewModelScope.launch(Dispatchers.IO) {
                receiveOne(channel, operation)
            }
        }
    }

    fun startSending() {
        if (mutableState.value.phase != FileTransferPhase.READY_TO_SEND) return
        val channel = authenticatedChannel ?: return
        val item = selectedItem ?: return
        val operation = operationId
        transferJob = viewModelScope.launch(Dispatchers.IO) {
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
        val currentPhase = mutableState.value.phase
        if (!currentPhase.isTerminal()) {
            mutableState.update {
                it.copy(phase = FileTransferPhase.CANCELLED, error = null)
            }
        }
        consentDecision?.complete(false)
        destinationDecision?.complete(null)
        operationId += 1L
        transferJob?.cancel()
        closeTransport()
    }

    /** Called when leaving the transfer route; invalidates late callbacks from the old session. */
    fun leaveSession() {
        consentDecision?.complete(false)
        destinationDecision?.complete(null)
        operationId += 1L
        transferJob?.cancel()
        closeTransport()
        transferJob = null
        selectedItem = null
        prepared = false
        mutableState.value = FileTransferUiState()
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
                transferSource.openStream().use { sourceStream ->
                    val protocol = SingleFileTransferProtocol(Long.MAX_VALUE)
                    val outcome = protocol.sendOne(
                        sessionId = channel.sessionId,
                        manifest = manifest,
                        source = sourceStream,
                        channelInput = channel.inputStream,
                        channelOutput = channel.outputStream,
                        onWaitingForReceiverConsent = {
                            update(operation) {
                                it.copy(
                                    phase = FileTransferPhase.WAITING_FOR_CONSENT,
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
                                it.copy(phase = FileTransferPhase.TRANSFERRING)
                            }
                        },
                        onProgress = { bytes ->
                            update(operation) {
                                it.copy(
                                    phase = FileTransferPhase.TRANSFERRING,
                                    transferredBytes = bytes,
                                )
                            }
                        },
                    )
                    applyOutcome(outcome, operation)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            update(operation) {
                it.copy(
                    phase = FileTransferPhase.FAILED,
                    error = FileTransferError.TRANSFER_FAILED,
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
                            phase = FileTransferPhase.WAITING_FOR_CONSENT,
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
                        val staging = storageRepository.createPrivateStaging(maximumBytes)
                        update(operation) {
                            it.copy(phase = FileTransferPhase.TRANSFERRING)
                        }
                        staging
                    }
                },
                publishVerified = { manifest, receipt, staging ->
                    val requestId = mutableState.value.destinationPickerRequestId + 1
                    update(operation) {
                        it.copy(
                            phase = FileTransferPhase.AWAITING_DESTINATION,
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
                            it.copy(phase = FileTransferPhase.PUBLISHING)
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
                            phase = FileTransferPhase.TRANSFERRING,
                            transferredBytes = bytes,
                        )
                    }
                },
            )

            if (outcome is TransferSessionOutcome.Rejected &&
                outcome.manifest.sizeBytes != null &&
                outcome.manifest.sizeBytes > maximumBytes
            ) {
                update(operation) {
                    it.copy(
                        phase = FileTransferPhase.FAILED,
                        displayName = outcome.manifest.displayName,
                        totalSizeBytes = outcome.manifest.sizeBytes,
                        error = FileTransferError.INSUFFICIENT_STORAGE,
                    )
                }
            } else {
                applyOutcome(outcome, operation)
            }
        } catch (cancellation: CancellationException) {
            update(operation) {
                if (it.phase.isTerminal()) it
                else it.copy(phase = FileTransferPhase.CANCELLED, error = null)
            }
            throw cancellation
        } catch (_: Exception) {
            update(operation) {
                it.copy(
                    phase = FileTransferPhase.FAILED,
                    error = FileTransferError.TRANSFER_FAILED,
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
                    phase = FileTransferPhase.COMPLETED,
                    displayName = outcome.manifest.displayName,
                    mimeType = outcome.manifest.mimeType,
                    totalSizeBytes = outcome.receipt.actualSizeBytes,
                    transferredBytes = outcome.receipt.actualSizeBytes,
                    error = null,
                )
            }

            is TransferSessionOutcome.Rejected -> update(operation) {
                it.copy(
                    phase = FileTransferPhase.REJECTED,
                    displayName = outcome.manifest.displayName,
                    error = null,
                )
            }

            is TransferSessionOutcome.Cancelled -> update(operation) {
                it.copy(phase = FileTransferPhase.CANCELLED, error = null)
            }
        }
    }

    private fun update(
        operation: Long,
        transform: (FileTransferUiState) -> FileTransferUiState,
    ) {
        if (operation == operationId) {
            mutableState.update(transform)
        }
    }

    private fun closeTransport() {
        runCatching { authenticatedChannel?.close() }
        authenticatedChannel = null
        connectivityRepository.disconnect()
    }

    private fun FileTransferPhase.isTerminal(): Boolean = when (this) {
        FileTransferPhase.COMPLETED,
        FileTransferPhase.REJECTED,
        FileTransferPhase.CANCELLED,
        FileTransferPhase.FAILED,
        -> true

        else -> false
    }

    private companion object {
        const val USER_CONSENT_TIMEOUT_MILLIS = 90_000L
        const val DESTINATION_SELECTION_TIMEOUT_MILLIS = 300_000L
    }
}
