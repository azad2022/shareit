package com.peyvand.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peyvand.app.R
import com.peyvand.core.designsystem.theme.PeyvandColors

private data class PreviewGroupMessage(
    val id: Int,
    val sender: String,
    val body: String,
    val time: String,
    val outgoing: Boolean,
)

@Composable
internal fun GroupConversationPreviewPane(
    groupName: String,
    onOpenMembers: () -> Unit,
    onOpenAttachments: () -> Unit,
    onOpenVoice: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var selectedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var operationNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val voiceRecordDescription = stringResource(R.string.ui_preview_voice_record_button)
    val sendDisabledNotice = stringResource(R.string.ui_preview_send_disabled_notice)
    val sendEmptyNotice = stringResource(R.string.ui_preview_send_empty_notice)
    val actionUnavailableNotice = stringResource(R.string.ui_preview_action_unavailable)
    val sampleMessages = listOf(
        PreviewGroupMessage(
            1,
            stringResource(R.string.ui_preview_member_sara),
            stringResource(R.string.ui_preview_message_one),
            stringResource(R.string.ui_preview_time_one),
            false,
        ),
        PreviewGroupMessage(
            2,
            stringResource(R.string.ui_preview_member_you),
            stringResource(R.string.ui_preview_message_two),
            stringResource(R.string.ui_preview_time_two),
            true,
        ),
        PreviewGroupMessage(
            3,
            stringResource(R.string.ui_preview_member_omid),
            stringResource(R.string.ui_preview_message_three),
            stringResource(R.string.ui_preview_time_three),
            false,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PeerAvatar(groupName, Modifier.size(48.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        groupName,
                        color = PeyvandColors.OnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.ui_preview_member_count),
                        color = PeyvandColors.OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onOpenMembers) {
                    Text(stringResource(R.string.ui_preview_manage))
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = PeyvandColors.PrimaryBlueContainer,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    stringResource(R.string.ui_preview_read_receipt),
                    color = PeyvandColors.OnPrimaryBlueContainer,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.ui_preview_typing),
                    color = PeyvandColors.OnPrimaryBlueContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(sampleMessages, key = { it.id }) { message ->
                PreviewGroupMessageBubble(
                    message = message,
                    onActions = { selectedMessage = message.body },
                )
            }
            item {
                AttachmentMessagePreview(
                    title = stringResource(R.string.ui_preview_image_file),
                    subtitle = stringResource(R.string.ui_preview_image_meta),
                    kind = stringResource(R.string.ui_preview_type_photo),
                )
            }
            item {
                VoiceMessagePreviewCard()
            }
        }

        operationNotice?.let { notice ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = PeyvandColors.SurfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = notice,
                    modifier = Modifier.padding(10.dp),
                    color = PeyvandColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenAttachments,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                    ) {
                        PeyvandChatIcon(PeyvandChatIconType.ATTACHMENT, PeyvandColors.PrimaryBlue)
                    }
                    OutlinedButton(
                        onClick = onOpenVoice,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = voiceRecordDescription },
                        shape = CircleShape,
                    ) {
                        PeyvandChatIcon(
                            PeyvandChatIconType.VOICE_NOTE,
                            PeyvandColors.PrimaryBlue,
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        color = PeyvandColors.SurfaceVariant,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (draft.isEmpty()) {
                                Text(
                                    stringResource(R.string.ui_preview_composer_hint),
                                    color = PeyvandColors.OnSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = PeyvandColors.OnSurface,
                                ),
                                singleLine = true,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            operationNotice = if (draft.isNotBlank()) {
                                sendDisabledNotice
                            } else {
                                sendEmptyNotice
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                    ) {
                        PeyvandChatIcon(PeyvandChatIconType.SEND, Color.White)
                    }
                }
                Text(
                    stringResource(R.string.ui_preview_composer_footer),
                    modifier = Modifier.fillMaxWidth(),
                    color = PeyvandColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    if (selectedMessage != null) {
        AlertDialog(
            onDismissRequest = { selectedMessage = null },
            title = { Text(stringResource(R.string.ui_preview_message_actions_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        selectedMessage.orEmpty(),
                        color = PeyvandColors.OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    listOf(
                        R.string.ui_preview_action_reply,
                        R.string.ui_preview_action_edit,
                        R.string.ui_preview_action_delete_self,
                        R.string.ui_preview_action_delete_all,
                        R.string.ui_preview_action_forward,
                    ).forEach { actionRes ->
                        TextButton(
                            onClick = {
                                operationNotice = actionUnavailableNotice
                                selectedMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(actionRes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessage = null }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
}

@Composable
private fun PreviewGroupMessageBubble(
    message: PreviewGroupMessage,
    onActions: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 310.dp),
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
        ) {
            if (!message.outgoing) {
                Text(
                    message.sender,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    color = PeyvandColors.PrimaryBlueDark,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Surface(
                modifier = Modifier.clickable(onClick = onActions),
                color = if (message.outgoing) PeyvandColors.PrimaryBlueContainer else Color.White,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomEnd = if (message.outgoing) 5.dp else 18.dp,
                    bottomStart = if (message.outgoing) 18.dp else 5.dp,
                ),
                shadowElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        message.body,
                        color = PeyvandColors.OnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message.time,
                            color = PeyvandColors.OnSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (message.outgoing) {
                            Text(
                                stringResource(R.string.ui_preview_message_status_sample),
                                color = PeyvandColors.PrimaryBlueDark,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onActions) {
                Text(stringResource(R.string.ui_preview_message_actions), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun AttachmentMessagePreview(
    title: String,
    subtitle: String,
    kind: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(PeyvandColors.PrimaryBlueContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    kind.take(3),
                    color = PeyvandColors.PrimaryBlueDark,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = PeyvandColors.OnSurface, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = PeyvandColors.OnSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(
                    stringResource(R.string.ui_preview_file_state),
                    color = PeyvandColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
internal fun VoiceMessagePreviewCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = PeyvandColors.PrimaryBlueContainer,
                    shape = CircleShape,
                ) {
                    PeyvandChatIcon(
                        PeyvandChatIconType.PLAY,
                        PeyvandColors.PrimaryBlueDark,
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp).size(18.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        stringResource(R.string.ui_preview_voice_title),
                        color = PeyvandColors.OnSurface,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.ui_preview_voice_duration),
                        color = PeyvandColors.OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    stringResource(R.string.ui_preview_sample_label),
                    color = PeyvandColors.Warning,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val heights = listOf(8, 15, 11, 23, 16, 28, 13, 20, 9, 25, 14, 18, 10, 22, 12)
                heights.forEach { height ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(height.dp)
                            .background(PeyvandColors.PrimaryBlue.copy(alpha = 0.55f), CircleShape),
                    )
                }
            }
            Text(
                stringResource(R.string.ui_preview_waveform_disclaimer),
                color = PeyvandColors.OnSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
