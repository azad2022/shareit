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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peyvand.app.R
import com.peyvand.core.transfer.session.TransferSessionError
import com.peyvand.core.transfer.session.TransferSessionPhase

@Composable
fun FileTransferSessionScreen(
    viewModel: FileTransferViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        viewModel.provideDestination(uri?.toString())
    }

    LaunchedEffect(state.destinationPickerRequestId, state.phase) {
        if (state.phase == TransferSessionPhase.AWAITING_DESTINATION &&
            state.destinationPickerRequestId > 0
        ) {
            createDocument.launch(state.displayName ?: "received-file")
        }
    }

    val terminal = state.phase == TransferSessionPhase.COMPLETED ||
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
                    state.phase == TransferSessionPhase.PUBLISHING
                ) {
                    if (progress == null) {
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

        Spacer(modifier = Modifier.weight(1f))

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
    TransferSessionPhase.TRANSFERRING -> R.string.file_transfer_transferring
    TransferSessionPhase.AWAITING_DESTINATION -> R.string.file_transfer_awaiting_destination
    TransferSessionPhase.PUBLISHING -> R.string.file_transfer_publishing
    TransferSessionPhase.COMPLETED -> R.string.file_transfer_completed
    TransferSessionPhase.REJECTED -> R.string.file_transfer_rejected
    TransferSessionPhase.CANCELLED -> R.string.file_transfer_cancelled
    TransferSessionPhase.FAILED -> R.string.file_transfer_failed
}

private fun errorLabel(error: TransferSessionError): Int = when (error) {
    TransferSessionError.SELECTION_REQUIRED -> R.string.file_transfer_error_selection
    TransferSessionError.UNSUPPORTED_SOURCE -> R.string.file_transfer_error_source
    TransferSessionError.CHANNEL_UNAVAILABLE -> R.string.file_transfer_error_channel
    TransferSessionError.INSUFFICIENT_STORAGE -> R.string.file_transfer_error_storage
    TransferSessionError.TRANSFER_FAILED -> R.string.file_transfer_failed
}
