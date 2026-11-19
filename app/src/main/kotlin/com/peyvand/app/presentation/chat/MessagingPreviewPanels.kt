package com.peyvand.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peyvand.app.R
import com.peyvand.core.designsystem.theme.PeyvandColors

@Composable
internal fun GroupListPreviewPane(
    onOpenGroup: () -> Unit,
    onCreateGroup: (String) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val sampleMembers = listOf(
        stringResource(R.string.ui_preview_member_sara),
        stringResource(R.string.ui_preview_member_omid),
        stringResource(R.string.ui_preview_member_nima),
    )
    var selectedMembers by rememberSaveable { mutableStateOf(setOf(0, 1)) }
    val defaultGroupName = stringResource(R.string.ui_preview_group_name)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ui_preview_groups_heading),
                    color = PeyvandColors.OnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.ui_preview_groups_subtitle),
                    color = PeyvandColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { showCreateDialog = true }) {
                Text(stringResource(R.string.ui_preview_create_group))
            }
        }

        PreviewGroupCard(
            title = stringResource(R.string.ui_preview_group_name),
            subtitle = stringResource(R.string.ui_preview_group_info),
            badge = stringResource(R.string.ui_preview_group_active_sample),
            onClick = onOpenGroup,
        )
        PreviewGroupCard(
            title = stringResource(R.string.ui_preview_group_second),
            subtitle = stringResource(R.string.ui_preview_group_second_info),
            badge = stringResource(R.string.ui_preview_group_inactive_sample),
            onClick = onOpenGroup,
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = PeyvandColors.SurfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                stringResource(R.string.ui_preview_single_active_group_note),
                modifier = Modifier.padding(12.dp),
                color = PeyvandColors.OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.ui_preview_create_group)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_preview_group_name_field)) },
                        singleLine = true,
                    )
                    Text(
                        stringResource(R.string.ui_preview_invite_members),
                        color = PeyvandColors.OnSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    sampleMembers.forEachIndexed { index, member ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedMembers = if (index in selectedMembers) {
                                        selectedMembers - index
                                    } else {
                                        selectedMembers + index
                                    }
                                },
                        ) {
                            Checkbox(
                                checked = index in selectedMembers,
                                onCheckedChange = { checked ->
                                    selectedMembers = if (checked) {
                                        selectedMembers + index
                                    } else {
                                        selectedMembers - index
                                    }
                                },
                            )
                            Text(member, color = PeyvandColors.OnSurface)
                        }
                    }
                    Text(
                        stringResource(R.string.ui_preview_capacity_note),
                        color = PeyvandColors.OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedMembers.isNotEmpty(),
                    onClick = {
                        onCreateGroup(name.ifBlank { defaultGroupName })
                        name = ""
                        showCreateDialog = false
                    },
                ) {
                    Text(stringResource(R.string.ui_preview_create_preview))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
}

@Composable
private fun PreviewGroupCard(
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeerAvatar(title, Modifier.size(50.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = PeyvandColors.OnSurface, fontWeight = FontWeight.Bold)
                Text(subtitle, color = PeyvandColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                badge,
                color = PeyvandColors.PrimaryBlueDark,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
internal fun GroupMembersPreviewPane(groupName: String) {
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable(groupName) { mutableStateOf(groupName) }
    var notice by rememberSaveable { mutableStateOf(false) }
    val members = listOf(
        stringResource(R.string.ui_preview_member_admin) to stringResource(R.string.ui_preview_role_admin),
        stringResource(R.string.ui_preview_member_sara) to stringResource(R.string.ui_preview_role_member),
        stringResource(R.string.ui_preview_member_omid) to stringResource(R.string.ui_preview_role_member),
        stringResource(R.string.ui_preview_member_nima) to stringResource(R.string.ui_preview_role_member),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            groupName,
            color = PeyvandColors.OnSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showRenameDialog = true }) {
                Text(stringResource(R.string.ui_preview_rename_group))
            }
            OutlinedButton(onClick = { notice = true }) {
                Text(stringResource(R.string.ui_preview_add_member))
            }
        }
        members.forEach { (name, role) ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PeerAvatar(name, Modifier.size(42.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    ) {
                        Text(name, color = PeyvandColors.OnSurface, fontWeight = FontWeight.SemiBold)
                        Text(role, color = PeyvandColors.OnSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { notice = true }) {
                        Text(stringResource(R.string.ui_preview_manage))
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = PeyvandColors.PrimaryBlueContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    stringResource(R.string.ui_preview_admin_failover_title),
                    color = PeyvandColors.OnPrimaryBlueContainer,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.ui_preview_admin_failover_body),
                    color = PeyvandColors.OnPrimaryBlueContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.ui_preview_admin_network_distinction),
                    color = PeyvandColors.OnPrimaryBlueContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        notice.takeIf { it }?.let {
            Text(
                stringResource(R.string.ui_preview_controls_not_connected),
                color = PeyvandColors.Warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.ui_preview_rename_group)) },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        notice = true
                    },
                ) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
}

@Composable
internal fun AttachmentPreviewPane() {
    val items = listOf(
        Triple(R.string.ui_preview_attach_photo, R.string.ui_preview_file_photo, R.string.ui_preview_meta_photo),
        Triple(R.string.ui_preview_attach_video, R.string.ui_preview_file_video, R.string.ui_preview_meta_video),
        Triple(R.string.ui_preview_attach_music, R.string.ui_preview_file_music, R.string.ui_preview_meta_music),
        Triple(R.string.ui_preview_attach_audio, R.string.ui_preview_file_audio, R.string.ui_preview_meta_audio),
        Triple(R.string.ui_preview_attach_document, R.string.ui_preview_file_document, R.string.ui_preview_meta_document),
        Triple(R.string.ui_preview_attach_pdf, R.string.ui_preview_file_pdf, R.string.ui_preview_meta_pdf),
        Triple(R.string.ui_preview_attach_archive, R.string.ui_preview_file_archive, R.string.ui_preview_meta_archive),
        Triple(R.string.ui_preview_attach_apk, R.string.ui_preview_file_apk, R.string.ui_preview_meta_apk),
        Triple(R.string.ui_preview_attach_other, R.string.ui_preview_file_other, R.string.ui_preview_meta_other),
    )

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            stringResource(R.string.ui_preview_attachments_heading),
            color = PeyvandColors.OnSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.ui_preview_multiselect_note),
            color = PeyvandColors.OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        items.forEach { (type, name, meta) ->
            AttachmentMessagePreview(
                title = stringResource(name),
                subtitle = stringResource(meta),
                kind = stringResource(type),
            )
        }
        Text(
            stringResource(R.string.ui_preview_native_picker_note),
            color = PeyvandColors.OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun VoiceMessagePreviewPane() {
    var previewState by rememberSaveable { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.ui_preview_voice_heading),
            color = PeyvandColors.OnSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.ui_preview_voice_controls_explainer),
                    color = PeyvandColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                VoiceMessagePreviewCard()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { previewState = 1 }) {
                        Text(stringResource(R.string.ui_preview_voice_hold))
                    }
                    OutlinedButton(onClick = { previewState = 2 }) {
                        Text(stringResource(R.string.ui_preview_voice_finish))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { previewState = 0 }) {
                        Text(stringResource(R.string.ui_preview_voice_cancel))
                    }
                    OutlinedButton(onClick = { previewState = 3 }) {
                        Text(stringResource(R.string.ui_preview_voice_review))
                    }
                }
                val messageRes = when (previewState) {
                    1 -> R.string.ui_preview_voice_state_recording
                    2 -> R.string.ui_preview_voice_state_finished
                    3 -> R.string.ui_preview_voice_state_review
                    else -> R.string.ui_preview_voice_state_idle
                }
                Text(
                    stringResource(messageRes),
                    color = PeyvandColors.PrimaryBlueDark,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.ui_preview_voice_controls_disclaimer),
                    color = PeyvandColors.Warning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
