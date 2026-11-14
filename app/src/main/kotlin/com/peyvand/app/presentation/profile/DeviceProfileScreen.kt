package com.peyvand.app.presentation.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peyvand.app.R
import com.peyvand.app.presentation.chat.ChatBlue
import com.peyvand.app.presentation.chat.ChatMuted
import com.peyvand.app.presentation.chat.PeerAvatar
import com.peyvand.app.presentation.chat.PeyvandChatIcon
import com.peyvand.app.presentation.chat.PeyvandChatIconType
import com.peyvand.core.designsystem.theme.PeyvandColors

@Composable
fun DeviceProfileScreen(
    viewModel: DeviceProfileViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { viewModel.importAvatar(it.toString()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PeyvandColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                PeyvandChatIcon(PeyvandChatIconType.BACK, PeyvandColors.OnSurface)
            }
            Text(
                text = stringResource(R.string.device_profile_title),
                modifier = Modifier.weight(1f),
                color = PeyvandColors.OnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ChatBlue)
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        PeerAvatar(
                            label = state.profileNameDraft.ifBlank {
                                state.deviceNameDraft.ifBlank { state.defaultDeviceName }
                            },
                            avatarBytes = state.avatarDraft,
                            modifier = Modifier.size(112.dp),
                        )
                        Surface(
                            shape = CircleShape,
                            color = ChatBlue,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                PeyvandChatIcon(PeyvandChatIconType.ADD, Color.White, Modifier.size(18.dp))
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.device_profile_photo_hint),
                        color = ChatMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            enabled = !state.isImportingAvatar && !state.isSaving,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            if (state.isImportingAvatar) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(stringResource(R.string.device_profile_choose_photo))
                            }
                        }
                        if (state.avatarDraft != null) {
                            OutlinedButton(
                                onClick = viewModel::clearAvatar,
                                enabled = !state.isSaving && !state.isImportingAvatar,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(stringResource(R.string.device_profile_remove_photo))
                            }
                        }
                    }
                }

                ProfileSectionCard {
                    Text(
                        text = stringResource(R.string.device_profile_device_name_label),
                        color = PeyvandColors.OnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = state.deviceNameDraft,
                        onValueChange = viewModel::onDeviceNameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(state.defaultDeviceName) },
                        supportingText = {
                            Text(stringResource(R.string.device_profile_device_name_hint, state.defaultDeviceName))
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.device_profile_profile_name_label),
                        color = PeyvandColors.OnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = state.profileNameDraft,
                        onValueChange = viewModel::onProfileNameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.device_profile_profile_name_placeholder)) },
                        supportingText = {
                            Text(stringResource(R.string.device_profile_profile_name_hint))
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    HorizontalDivider(color = PeyvandColors.SurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.device_profile_share_title),
                                color = PeyvandColors.OnSurface,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.device_profile_share_hint),
                                color = ChatMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.shareWithTrustedPeersDraft,
                            onCheckedChange = viewModel::onShareWithTrustedPeersChanged,
                            enabled = !state.isSaving,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = stringResource(R.string.device_profile_preview_title),
                        color = PeyvandColors.OnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White,
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PeerAvatar(
                                label = state.profileNameDraft.ifBlank {
                                    state.deviceNameDraft.ifBlank { state.defaultDeviceName }
                                },
                                avatarBytes = state.avatarDraft,
                                modifier = Modifier.size(50.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = state.deviceNameDraft.ifBlank { state.defaultDeviceName },
                                    color = PeyvandColors.OnSurface,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (state.profileNameDraft.isNotBlank()) {
                                    Text(
                                        text = state.profileNameDraft,
                                        color = ChatMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }

                state.error?.let { error ->
                    val message = when (error) {
                        DeviceProfileUiError.IMAGE_IMPORT_FAILED ->
                            stringResource(R.string.device_profile_error_import)
                        DeviceProfileUiError.IMAGE_INVALID ->
                            stringResource(R.string.device_profile_error_image)
                        DeviceProfileUiError.INPUT_INVALID ->
                            stringResource(R.string.device_profile_error_input)
                        DeviceProfileUiError.SAVE_FAILED ->
                            stringResource(R.string.device_profile_error_save)
                    }
                    Text(
                        text = message,
                        color = PeyvandColors.Error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.device_profile_security_note),
                    color = ChatMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.discardChanges()
                        onBack()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                    enabled = !state.isSaving && !state.isImportingAvatar,
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                    enabled = state.hasChanges && !state.isSaving && !state.isImportingAvatar,
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatBlue),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.device_profile_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
