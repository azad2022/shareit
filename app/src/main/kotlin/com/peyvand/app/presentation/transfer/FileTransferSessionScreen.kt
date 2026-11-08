package com.peyvand.app.presentation.transfer

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peyvand.app.R
import com.peyvand.core.transfer.session.TransferSessionError
import com.peyvand.core.transfer.session.TransferSessionPhase
import com.peyvand.core.transfer.session.TransferItemPhase
import com.peyvand.core.transfer.session.TransferItemSnapshot

@Composable
fun FileTransferSessionScreen(
    viewModel: FileTransferViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    var lastLaunchedPickerRequestId by rememberSaveable { mutableIntStateOf(0) }
    var lastLaunchedTreeRequestId by rememberSaveable { mutableIntStateOf(0) }
    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        viewModel.provideDestination(uri?.toString())
    }
    val openDestinationTree = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        viewModel.provideDestinationTree(uri?.toString())
    }

    LaunchedEffect(state.destinationPickerRequestId, state.phase) {
        if (state.phase != TransferSessionPhase.AWAITING_DESTINATION &&
            state.destinationPickerRequestId == 0
        ) {
            lastLaunchedPickerRequestId = 0
        }
        if (state.phase == TransferSessionPhase.AWAITING_DESTINATION &&
            state.destinationPickerRequestId > lastLaunchedPickerRequestId
        ) {
            lastLaunchedPickerRequestId = state.destinationPickerRequestId
            createDocument.launch(state.displayName ?: "received-file")
        }
    }

    LaunchedEffect(state.destinationTreePickerRequestId, state.phase) {
        if (state.phase != TransferSessionPhase.AWAITING_DESTINATION_TREE &&
            state.destinationTreePickerRequestId == 0
        ) {
            lastLaunchedTreeRequestId = 0
        }
        if (state.phase == TransferSessionPhase.AWAITING_DESTINATION_TREE &&
            state.destinationTreePickerRequestId > lastLaunchedTreeRequestId
        ) {
            lastLaunchedTreeRequestId = state.destinationTreePickerRequestId
            openDestinationTree.launch(null)
        }
    }

    val terminal = state.phase == TransferSessionPhase.COMPLETED ||
        state.phase == TransferSessionPhase.PARTIALLY_COMPLETED ||
        state.phase == TransferSessionPhase.REJECTED ||
        state.phase == TransferSessionPhase.CANCELLED ||
        state.phase == TransferSessionPhase.FAILED
    val progress = state.totalSizeBytes
        ?.takeIf { it > 0L }
        ?.let {
            (state.transferredBytes.toDouble() / it.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.file_transfer_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(phaseLabel(state.phase, state.isSender)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                if (state.isBatch) {
                    BatchItemsPanel(
                        items = state.items,
                        showConsent = state.phase == TransferSessionPhase.AWAITING_BATCH_CONSENT,
                        allowCancellation = state.phase != TransferSessionPhase.AWAITING_BATCH_CONSENT,
                        onToggleConsent = viewModel::toggleBatchConsentItem,
                        onCancelItem = viewModel::cancelItem,
                    )
                }

                state.displayName?.let {
                    Text(
                        text = stringResource(R.string.file_transfer_file_name, it),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.mimeType?.let {
                    Text(
                        text = stringResource(R.string.file_transfer_file_type, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.totalSizeBytes?.let {
                    Text(
                        text = stringResource(
                            R.string.file_transfer_file_size,
                            Formatter.formatFileSize(context, it),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } ?: Text(
                    text = stringResource(R.string.file_transfer_unknown_size),
                    style = MaterialTheme.typography.bodySmall,
                )

                if (state.phase == TransferSessionPhase.TRANSFERRING ||
                    state.phase == TransferSessionPhase.VERIFYING ||
                    state.phase == TransferSessionPhase.PUBLISHING
                ) {
                    if (state.phase == TransferSessionPhase.VERIFYING || progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.file_transfer_progress,
                            Formatter.formatFileSize(context, state.transferredBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (state.phase == TransferSessionPhase.READY_TO_SEND) {
                    Text(
                        text = stringResource(R.string.file_transfer_ready_to_send),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = viewModel::startSending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.file_transfer_start))
                    }
                }

                if (state.phase == TransferSessionPhase.AWAITING_BATCH_OFFER_CONSENT) {
                    Text(
                        text = stringResource(R.string.file_transfer_batch_offer_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = viewModel::rejectIncoming,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.file_transfer_reject))
                        }
                        Button(
                            onClick = viewModel::acceptBatchOffer,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.file_transfer_show_batch_manifest))
                        }
                    }
                }

                if (state.phase == TransferSessionPhase.AWAITING_BATCH_CONSENT) {
                    Text(
                        text = stringResource(R.string.file_transfer_batch_consent_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = viewModel::rejectIncoming,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.file_transfer_reject))
                        }
                        Button(
                            onClick = viewModel::acceptBatchIncoming,
                            enabled = state.items.any { it.isSelectedForConsent },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.file_transfer_accept_selected))
                        }
                    }
                }

                if (state.phase == TransferSessionPhase.WAITING_FOR_CONSENT && !state.isSender) {
                    Text(
                        text = stringResource(R.string.file_transfer_waiting_consent_receiver),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = viewModel::rejectIncoming,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.file_transfer_reject))
                        }
                        Button(
                            onClick = viewModel::acceptIncoming,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.file_transfer_accept))
                        }
                    }
                }

                if (state.phase == TransferSessionPhase.AWAITING_DESTINATION_TREE) {
                    Text(
                        text = stringResource(R.string.file_transfer_destination_tree_picker),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (state.phase == TransferSessionPhase.AWAITING_DESTINATION) {
                    Text(
                        text = stringResource(R.string.file_transfer_destination_picker),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                state.error?.let { error ->
                    Text(
                        text = stringResource(errorLabel(error)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (terminal) {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.file_transfer_back))
            }
        } else {
            OutlinedButton(
                onClick = viewModel::cancelTransfer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.file_transfer_cancel))
            }
        }
    }
}

private fun phaseLabel(phase: TransferSessionPhase, isSender: Boolean): Int = when (phase) {
    TransferSessionPhase.PREPARING -> R.string.file_transfer_waiting_incoming
    TransferSessionPhase.READY_TO_SEND -> R.string.file_transfer_ready_to_send
    TransferSessionPhase.WAITING_FOR_INCOMING -> R.string.file_transfer_waiting_incoming
    TransferSessionPhase.WAITING_FOR_CONSENT -> if (isSender) {
        R.string.file_transfer_waiting_consent_sender
    } else {
        R.string.file_transfer_waiting_consent_receiver
    }
    TransferSessionPhase.AWAITING_BATCH_OFFER_CONSENT -> R.string.file_transfer_batch_offer_title
    TransferSessionPhase.AWAITING_BATCH_CONSENT -> R.string.file_transfer_batch_consent_title
    TransferSessionPhase.AWAITING_DESTINATION_TREE -> R.string.file_transfer_destination_tree_picker
    TransferSessionPhase.TRANSFERRING -> R.string.file_transfer_transferring
    TransferSessionPhase.VERIFYING -> R.string.file_transfer_verifying
    TransferSessionPhase.AWAITING_DESTINATION -> R.string.file_transfer_awaiting_destination
    TransferSessionPhase.PUBLISHING -> R.string.file_transfer_publishing
    TransferSessionPhase.COMPLETED -> R.string.file_transfer_completed
    TransferSessionPhase.PARTIALLY_COMPLETED -> R.string.file_transfer_partially_completed
    TransferSessionPhase.REJECTED -> R.string.file_transfer_rejected
    TransferSessionPhase.CANCELLED -> R.string.file_transfer_cancelled
    TransferSessionPhase.FAILED -> R.string.file_transfer_failed
}

private fun errorLabel(error: TransferSessionError): Int = when (error) {
    TransferSessionError.SELECTION_REQUIRED -> R.string.file_transfer_error_selection
    TransferSessionError.BATCH_LIMIT_EXCEEDED -> R.string.file_transfer_error_batch_limit
    TransferSessionError.UNSUPPORTED_SOURCE -> R.string.file_transfer_error_source
    TransferSessionError.CHANNEL_UNAVAILABLE -> R.string.file_transfer_error_channel
    TransferSessionError.INSUFFICIENT_STORAGE -> R.string.file_transfer_error_storage
    TransferSessionError.TRANSFER_FAILED -> R.string.file_transfer_failed
}


@Composable
private fun BatchItemsPanel(
    items: List<TransferItemSnapshot>,
    showConsent: Boolean,
    allowCancellation: Boolean,
    onToggleConsent: (String) -> Unit,
    onCancelItem: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.file_transfer_batch_items_count, items.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        items.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showConsent) {
                            Checkbox(
                                checked = item.isSelectedForConsent,
                                onCheckedChange = { onToggleConsent(item.id) },
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = item.mimeType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(itemPhaseLabel(item.phase)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.phase == TransferItemPhase.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val progress = item.totalSizeBytes
                        ?.takeIf { it > 0L }
                        ?.let {
                            (item.transferredBytes.toDouble() / it.toDouble())
                                .coerceIn(0.0, 1.0)
                                .toFloat()
                        }
                    if (item.phase == TransferItemPhase.TRANSFERRING ||
                        item.phase == TransferItemPhase.VERIFYING ||
                        item.phase == TransferItemPhase.PUBLISHING
                    ) {
                        if (progress == null || item.phase != TransferItemPhase.TRANSFERRING) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    val totalSizeBytes = item.totalSizeBytes
                    if (totalSizeBytes != null) {
                        Text(
                            text = stringResource(
                                R.string.file_transfer_item_progress,
                                Formatter.formatFileSize(LocalContext.current, item.transferredBytes),
                                Formatter.formatFileSize(LocalContext.current, totalSizeBytes),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (allowCancellation && item.phase in setOf(
                            TransferItemPhase.QUEUED,
                            TransferItemPhase.WAITING_FOR_RECEIVER,
                            TransferItemPhase.TRANSFERRING,
                        )
                    ) {
                        OutlinedButton(
                            onClick = { onCancelItem(item.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.file_transfer_cancel_item))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun itemPhaseLabel(phase: TransferItemPhase): Int = when (phase) {
    TransferItemPhase.QUEUED -> R.string.file_transfer_item_queued
    TransferItemPhase.WAITING_FOR_RECEIVER -> R.string.file_transfer_item_waiting
    TransferItemPhase.TRANSFERRING -> R.string.file_transfer_item_transferring
    TransferItemPhase.VERIFYING -> R.string.file_transfer_item_verifying
    TransferItemPhase.PUBLISHING -> R.string.file_transfer_item_publishing
    TransferItemPhase.COMPLETED -> R.string.file_transfer_item_completed
    TransferItemPhase.REJECTED -> R.string.file_transfer_item_rejected
    TransferItemPhase.CANCELLED -> R.string.file_transfer_item_cancelled
    TransferItemPhase.FAILED -> R.string.file_transfer_item_failed
}
