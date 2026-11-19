package com.peyvand.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peyvand.app.R
import com.peyvand.core.designsystem.theme.PeyvandColors

private enum class MessagingPreviewTab(val titleRes: Int) {
    GROUPS(R.string.ui_preview_tab_groups),
    CONVERSATION(R.string.ui_preview_tab_conversation),
    ATTACHMENTS(R.string.ui_preview_tab_attachments),
    VOICE(R.string.ui_preview_tab_voice),
    MEMBERS(R.string.ui_preview_tab_members),
}

/**
 * Debug-only visual review surface. Every identity, message, filename and state shown here
 * is illustrative and is never persisted, transmitted, or used as evidence of connectivity.
 */
@Composable
fun MessagingUiPreviewScreen(
    onBack: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MessagingPreviewTab.GROUPS) }
    var groupName by rememberSaveable {
        mutableStateOf("")
    }
    val defaultGroupName = stringResource(R.string.ui_preview_group_name)
    val resolvedGroupName = groupName.ifBlank { defaultGroupName }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PeyvandColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = stringResource(R.string.ui_preview_title),
                    color = PeyvandColors.OnSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.ui_preview_subtitle),
                    color = PeyvandColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = PeyvandColors.Warning.copy(alpha = 0.10f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_preview_notice_title),
                    color = PeyvandColors.Warning,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.ui_preview_notice_body),
                    color = PeyvandColors.OnSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MessagingPreviewTab.values().forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    label = { Text(stringResource(tab.titleRes)) },
                )
            }
        }

        when (selectedTab) {
            MessagingPreviewTab.GROUPS -> GroupListPreviewPane(
                onOpenGroup = { selectedTab = MessagingPreviewTab.CONVERSATION },
                onCreateGroup = { createdName ->
                    groupName = createdName
                    selectedTab = MessagingPreviewTab.CONVERSATION
                },
            )

            MessagingPreviewTab.CONVERSATION -> GroupConversationPreviewPane(
                groupName = resolvedGroupName,
                onOpenMembers = { selectedTab = MessagingPreviewTab.MEMBERS },
                onOpenAttachments = { selectedTab = MessagingPreviewTab.ATTACHMENTS },
                onOpenVoice = { selectedTab = MessagingPreviewTab.VOICE },
            )

            MessagingPreviewTab.ATTACHMENTS -> AttachmentPreviewPane()
            MessagingPreviewTab.VOICE -> VoiceMessagePreviewPane()
            MessagingPreviewTab.MEMBERS -> GroupMembersPreviewPane(groupName = resolvedGroupName)
        }
    }
}
