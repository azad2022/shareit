package com.peyvand.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peyvand.app.R
import com.peyvand.core.designsystem.theme.PeyvandColors
import com.peyvand.core.domain.chat.ChatMessage
import com.peyvand.core.domain.chat.ChatMessageDeliveryState
import com.peyvand.core.domain.chat.ChatMessageDirection
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun ChatConversationScreen(
    state: ChatConversationUiState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onSendAttachment: () -> Unit,
    onPrepareReceive: () -> Unit,
    onSendMessage: suspend (String) -> ChatSendResult,
) {
    val peer = state.peer
    var draft by rememberSaveable(peer?.identityId?.sha256Fingerprint) { mutableStateOf("") }
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    var emojiMenuExpanded by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val voiceCallDescription = stringResource(R.string.chat_accessibility_voice_call)
    val videoCallDescription = stringResource(R.string.chat_accessibility_video_call)
    val attachmentDescription = stringResource(R.string.chat_accessibility_attachment)
    val emojiDescription = stringResource(R.string.chat_accessibility_emoji)
    val sendDescription = stringResource(R.string.chat_accessibility_send)

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            val separatorCount = state.messages.indices.count { index ->
                index == 0 || !isSameLocalCalendarDay(
                    state.messages[index - 1].createdAtEpochMillis,
                    state.messages[index].createdAtEpochMillis,
                )
            }
            val lastMessageItemIndex = state.messages.lastIndex + separatorCount
            listState.animateScrollToItem(lastMessageItemIndex)
        }
    }

    fun submitDraft() {
        val content = draft
        if (sending || content.isBlank()) return
        sending = true
        scope.launch {
            try {
                if (onSendMessage(content) == ChatSendResult.Saved && draft == content) {
                    draft = ""
                }
            } finally {
                sending = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatCanvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    PeyvandChatIcon(PeyvandChatIconType.BACK, PeyvandColors.OnSurface, Modifier.size(24.dp))
                }
                PeerAvatar(
                    label = peer?.displayName.orEmpty(),
                    modifier = Modifier.size(43.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Text(
                            text = peer?.displayName ?: stringResource(R.string.chat_conversation_title),
                            color = PeyvandColors.OnSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            text = when {
                                state.isConnected -> stringResource(R.string.chat_status_connected)
                                state.isConnectedToDifferentPeer ->
                                    stringResource(R.string.chat_status_other_peer_connected)
                                else -> stringResource(R.string.chat_status_not_connected)
                            },
                            color = if (state.isConnected) PeyvandColors.Success else ChatMuted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.semantics {
                        contentDescription = voiceCallDescription
                    },
                ) { PeyvandChatIcon(PeyvandChatIconType.VOICE_CALL, PeyvandColors.Outline, Modifier.size(22.dp)) }
                IconButton(
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.semantics {
                        contentDescription = videoCallDescription
                    },
                ) { PeyvandChatIcon(PeyvandChatIconType.VIDEO_CALL, PeyvandColors.Outline, Modifier.size(22.dp)) }
            }
        }

        if (!state.isConnected) {
            Surface(
                color = if (state.isConnectedToDifferentPeer) PeyvandColors.SurfaceVariant else PeyvandColors.PrimaryBlueContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (state.isConnectedToDifferentPeer) {
                            stringResource(R.string.chat_other_peer_active)
                        } else {
                            stringResource(R.string.chat_offline_banner)
                        },
                        modifier = Modifier.weight(1f),
                        color = if (state.isConnectedToDifferentPeer) PeyvandColors.Warning else PeyvandColors.OnPrimaryBlueContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = onConnect,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.chat_connect_action),
                            color = ChatBlue,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        } else if (state.pendingOutboxCount > 0 && state.isSyncChannelReady) {
            Text(
                text = stringResource(R.string.chat_pending_count, state.pendingOutboxCount),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PeyvandColors.PrimaryBlueContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = PeyvandColors.OnPrimaryBlueContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            PeyvandChatWallpaper(Modifier.fillMaxSize())
            if (state.messages.isEmpty()) {
                ChatConversationEmptyCard(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    itemsIndexed(
                        items = state.messages,
                        key = { _, message -> message.id.value },
                    ) { index, message ->
                        val previous = state.messages.getOrNull(index - 1)
                        if (previous == null ||
                            !isSameLocalCalendarDay(
                                previous.createdAtEpochMillis,
                                message.createdAtEpochMillis,
                            )
                        ) {
                            ChatDateSeparator(message.createdAtEpochMillis)
                        }
                        ChatMessageBubble(message)
                    }
                }
            }
        }

        state.notice?.let { currentNotice ->
            val noticeText = when (currentNotice) {
                ChatUiNotice.MESSAGE_SAVED_WAITING_FOR_DIRECT_CONNECTION ->
                    stringResource(R.string.chat_notice_waiting)
                ChatUiNotice.MESSAGE_STORAGE_FAILED ->
                    stringResource(R.string.chat_notice_storage_failed)
                ChatUiNotice.MESSAGE_QUEUE_FULL ->
                    stringResource(R.string.chat_notice_queue_full)
                ChatUiNotice.SECURE_SESSION_UNAVAILABLE ->
                    stringResource(R.string.chat_notice_session_unavailable)
                ChatUiNotice.PEER_IDENTITY_CHANGED ->
                    stringResource(R.string.chat_notice_identity_changed)
            }
            Text(
                text = noticeText,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PeyvandColors.SurfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                color = PeyvandColors.Warning,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Surface(
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (emojiMenuExpanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("🙂", "😄", "❤️", "👍", "🙏", "🌱").forEach { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        draft += emoji
                                        emojiMenuExpanded = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(emoji, fontSize = 22.sp)
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { emojiMenuExpanded = !emojiMenuExpanded },
                            modifier = Modifier.semantics {
                                contentDescription = emojiDescription
                            },
                        ) {
                            PeyvandChatIcon(PeyvandChatIconType.EMOJI, ChatMuted, Modifier.size(24.dp))
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(bottom = 2.dp),
                                placeholder = {
                                    Text(stringResource(R.string.chat_composer_placeholder))
                                },
                                shape = RoundedCornerShape(22.dp),
                                minLines = 1,
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { submitDraft() }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = PeyvandColors.Background,
                                    focusedContainerColor = PeyvandColors.Background,
                                    unfocusedBorderColor = PeyvandColors.SurfaceVariant,
                                    focusedBorderColor = PeyvandColors.Outline,
                                ),
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { attachmentMenuExpanded = true },
                                enabled = state.isConnected,
                                modifier = Modifier.semantics {
                                    contentDescription = attachmentDescription
                                },
                            ) {
                                PeyvandChatIcon(PeyvandChatIconType.ATTACHMENT, if (state.isConnected) ChatBlue else ChatMuted, Modifier.size(24.dp))
                            }
                            DropdownMenu(
                                expanded = attachmentMenuExpanded,
                                onDismissRequest = { attachmentMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_send)) },
                                    onClick = {
                                        attachmentMenuExpanded = false
                                        onSendAttachment()
                                    },
                                    leadingIcon = { PeyvandChatIcon(PeyvandChatIconType.SEND, ChatBlue) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_receive)) },
                                    onClick = {
                                        attachmentMenuExpanded = false
                                        onPrepareReceive()
                                    },
                                    leadingIcon = { PeyvandChatIcon(PeyvandChatIconType.RECEIVE, ChatBlue) },
                                )
                            }
                        }
                        IconButton(
                            onClick = ::submitDraft,
                            enabled = draft.isNotBlank() && !sending,
                            modifier = Modifier
                                .padding(bottom = 3.dp)
                                .size(48.dp)
                                .background(
                                    color = if (draft.isNotBlank() && !sending) ChatBlue else PeyvandColors.Outline,
                                    shape = CircleShape,
                                )
                                .semantics {
                                    contentDescription = sendDescription
                                },
                        ) {
                            PeyvandChatIcon(PeyvandChatIconType.SEND, Color.White, Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatDateSeparator(timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.92f),
            shape = RoundedCornerShape(50),
            shadowElevation = 1.dp,
        ) {
            Text(
                text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp)),
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                color = PeyvandColors.OnSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun isSameLocalCalendarDay(first: Long, second: Long): Boolean {
    val firstDay = Calendar.getInstance().apply { timeInMillis = first }
    val secondDay = Calendar.getInstance().apply { timeInMillis = second }
    return firstDay.get(Calendar.ERA) == secondDay.get(Calendar.ERA) &&
        firstDay.get(Calendar.YEAR) == secondDay.get(Calendar.YEAR) &&
        firstDay.get(Calendar.DAY_OF_YEAR) == secondDay.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun ChatMessageBubble(message: ChatMessage) {
    val outgoing = message.direction == ChatMessageDirection.OUTGOING
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = if (outgoing) PeyvandColors.PrimaryBlueContainer else Color.White,
                shape = if (outgoing) {
                    RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp)
                } else {
                    RoundedCornerShape(20.dp, 20.dp, 20.dp, 5.dp)
                },
                shadowElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = message.body,
                            color = PeyvandColors.OnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            formatChatTime(message),
                            color = ChatMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (outgoing) {
                            Text(
                                text = when (message.deliveryState) {
                                    ChatMessageDeliveryState.WAITING_FOR_CONNECTION ->
                                        stringResource(R.string.chat_message_waiting_short)
                                    ChatMessageDeliveryState.SYNCHRONIZING ->
                                        stringResource(R.string.chat_message_syncing)
                                    ChatMessageDeliveryState.DELIVERED ->
                                        stringResource(R.string.chat_message_delivered)
                                    ChatMessageDeliveryState.RECEIVED ->
                                        stringResource(R.string.chat_message_waiting_short)
                                },
                                color = if (message.deliveryState == ChatMessageDeliveryState.DELIVERED) {
                                    PeyvandColors.PrimaryBlue
                                } else {
                                    ChatMuted
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatConversationEmptyCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(horizontal = 34.dp)
            .widthIn(max = 390.dp),
        color = PeyvandColors.Surface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeyvandConnectionGlyph(Modifier.size(88.dp))
            Text(
                stringResource(R.string.chat_empty_conversation_title),
                color = PeyvandColors.OnSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.chat_empty_conversation_body),
                color = ChatMuted,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 24.sp,
            )
        }
    }
}
