package com.peyvand.app.presentation.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peyvand.app.R
import com.peyvand.core.domain.chat.ChatMessage
import com.peyvand.core.domain.chat.ChatMessageDeliveryState
import com.peyvand.core.domain.chat.ChatMessageDirection
import com.peyvand.core.domain.chat.PeerIdentityId
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private val ChatCanvas = Color(0xFFF4F8FD)
private val ChatBlue = Color(0xFF1769D2)
private val ChatPaleBlue = Color(0xFFE4F0FF)
private val ChatMuted = Color(0xFF718198)

@Composable
fun ChatHomeScreen(
    state: ChatHomeUiState,
    onOpenConversation: (PeerIdentityId) -> Unit,
    onOpenNearby: () -> Unit,
    onOpenTransfers: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.conversations, search) {
        if (search.isBlank()) state.conversations
        else state.conversations.filter {
            it.peer.displayName.contains(search, ignoreCase = true) ||
                it.latestMessage?.body?.contains(search, ignoreCase = true) == true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PeyvandBrandGlyph(Modifier.size(42.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = stringResource(R.string.chat_brand_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = ChatBlue,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.6).sp,
                    )
                    Text(
                        text = stringResource(R.string.chat_brand_tagline),
                        style = MaterialTheme.typography.labelSmall,
                        color = ChatMuted,
                    )
                }
            }
            val nearbyDescription = stringResource(R.string.chat_accessibility_nearby)
            IconButton(
                onClick = onOpenNearby,
                modifier = Modifier
                    .size(46.dp)
                    .background(ChatPaleBlue, CircleShape)
                    .semantics { contentDescription = nearbyDescription },
            ) {
                Text("＋", color = ChatBlue, fontSize = 28.sp, fontWeight = FontWeight.Light)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.chat_home_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFF172A43),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.chat_home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ChatMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text(stringResource(R.string.chat_search_placeholder)) },
                leadingIcon = { Text("⌕", fontSize = 25.sp, color = ChatMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White,
                    unfocusedBorderColor = Color(0xFFE4EAF2),
                    focusedBorderColor = ChatBlue,
                ),
            )

            Spacer(Modifier.height(16.dp))
            NearbyEntryCard(onClick = onOpenNearby)
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.chat_recent_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF24374E),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = filtered.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = ChatMuted,
                )
            }
            Spacer(Modifier.height(8.dp))

            when {
                state.isLoading -> ChatLoadingState()
                filtered.isEmpty() && search.isNotBlank() -> ChatEmptySearchState()
                filtered.isEmpty() -> ChatEmptyHomeState(onOpenNearby)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.peer.identityId.sha256Fingerprint }) { preview ->
                        ChatConversationRow(
                            preview = preview,
                            isConnected = state.connectedPeerFingerprint ==
                                preview.peer.identityId.sha256Fingerprint,
                            onClick = { onOpenConversation(preview.peer.identityId) },
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        ChatBottomNavigation(
            selected = ChatBottomDestination.CHATS,
            onChats = { search = "" },
            onTransfers = onOpenTransfers,
            onNearby = onOpenNearby,
        )
    }
}

private enum class ChatBottomDestination { CHATS, TRANSFERS, NEARBY }

@Composable
private fun ChatBottomNavigation(
    selected: ChatBottomDestination,
    onChats: () -> Unit,
    onTransfers: () -> Unit,
    onNearby: () -> Unit,
) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp,
    ) {
        NavigationBarItem(
            selected = selected == ChatBottomDestination.CHATS,
            onClick = onChats,
            icon = { Text("▤", fontSize = 20.sp) },
            label = { Text(stringResource(R.string.chat_tab_chats)) },
        )
        NavigationBarItem(
            selected = selected == ChatBottomDestination.TRANSFERS,
            onClick = onTransfers,
            icon = { Text("⇄", fontSize = 22.sp) },
            label = { Text(stringResource(R.string.chat_tab_transfers)) },
        )
        NavigationBarItem(
            selected = selected == ChatBottomDestination.NEARBY,
            onClick = onNearby,
            icon = { Text("⌖", fontSize = 22.sp) },
            label = { Text(stringResource(R.string.chat_tab_nearby)) },
        )
    }
}

@Composable
private fun NearbyEntryCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF1769D2),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("⌖", color = Color.White, fontSize = 28.sp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.chat_nearby_card_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.chat_nearby_card_subtitle),
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("‹", color = Color.White, fontSize = 30.sp)
        }
    }
}

@Composable
private fun ChatConversationRow(
    preview: ChatConversationPreview,
    isConnected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeerAvatar(
                label = preview.peer.displayName,
                modifier = Modifier.size(52.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preview.peer.displayName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFF172A43),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = preview.latestMessage?.let(::formatChatTime) ?: "",
                        color = ChatMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val last = preview.latestMessage
                    Text(
                        text = when {
                            last == null -> stringResource(R.string.chat_row_start_message)
                            last.direction == ChatMessageDirection.OUTGOING &&
                                last.deliveryState == ChatMessageDeliveryState.WAITING_FOR_CONNECTION ->
                                stringResource(R.string.chat_message_waiting)
                            else -> last.body.replace('\n', ' ')
                        },
                        modifier = Modifier.weight(1f),
                        color = ChatMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (preview.pendingOutboxCount > 0) {
                        Box(
                            modifier = Modifier
                                .background(ChatPaleBlue, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = preview.pendingOutboxCount.toString(),
                                color = ChatBlue,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        Text(
                            text = if (isConnected) stringResource(R.string.chat_status_connected)
                            else stringResource(R.string.chat_status_trusted),
                            color = if (isConnected) Color(0xFF16835A) else ChatMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatLoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.chat_loading), color = ChatMuted)
    }
}

@Composable
private fun ChatEmptySearchState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.chat_search_empty), color = ChatMuted)
    }
}

@Composable
private fun ChatEmptyHomeState(onOpenNearby: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PeyvandConnectionGlyph(Modifier.size(108.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.chat_empty_title),
            color = Color(0xFF223650),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.chat_empty_body),
            modifier = Modifier.padding(top = 7.dp, bottom = 18.dp),
            color = ChatMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onOpenNearby,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ChatBlue),
        ) {
            Text(stringResource(R.string.chat_empty_action))
        }
    }
}

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
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text("‹", color = Color(0xFF1D3552), fontSize = 34.sp)
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
                Text(
                    text = peer?.displayName ?: stringResource(R.string.chat_conversation_title),
                    color = Color(0xFF162D49),
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
                    color = if (state.isConnected) Color(0xFF16835A) else ChatMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            IconButton(
                enabled = false,
                onClick = {},
                modifier = Modifier.semantics {
                    contentDescription = voiceCallDescription
                },
            ) { Text("☎", color = Color(0xFF8291A3), fontSize = 22.sp) }
            IconButton(
                enabled = false,
                onClick = {},
                modifier = Modifier.semantics {
                    contentDescription = videoCallDescription
                },
            ) { Text("▣", color = Color(0xFF8291A3), fontSize = 21.sp) }
        }

        if (!state.isConnected) {
            Surface(
                color = if (state.isConnectedToDifferentPeer) Color(0xFFFFF1E8) else Color(0xFFEAF3FF),
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
                        color = if (state.isConnectedToDifferentPeer) Color(0xFF9A4C1A) else Color(0xFF365C88),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.chat_connect_action),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onConnect)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        color = ChatBlue,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        } else if (state.pendingOutboxCount > 0) {
            Text(
                text = stringResource(R.string.chat_pending_count, state.pendingOutboxCount),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEAF3FF))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color(0xFF365C88),
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
                    items(state.messages, key = { it.id.value }) { message ->
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
                    .background(Color(0xFFFFF4E7))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                color = Color(0xFF8B571D),
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
                            Text(
                                emoji,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        draft += emoji
                                        emojiMenuExpanded = false
                                    }
                                    .padding(5.dp),
                                fontSize = 22.sp,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box {
                        IconButton(
                            onClick = { attachmentMenuExpanded = true },
                            enabled = state.isConnected,
                            modifier = Modifier.semantics {
                                contentDescription = attachmentDescription
                            },
                        ) {
                            Text("＋", color = if (state.isConnected) ChatBlue else ChatMuted, fontSize = 27.sp)
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
                                leadingIcon = { Text("↗", color = ChatBlue, fontSize = 20.sp) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach_receive)) },
                                onClick = {
                                    attachmentMenuExpanded = false
                                    onPrepareReceive()
                                },
                                leadingIcon = { Text("↓", color = ChatBlue, fontSize = 20.sp) },
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { emojiMenuExpanded = !emojiMenuExpanded },
                            modifier = Modifier.semantics {
                                contentDescription = emojiDescription
                            },
                        ) {
                            Text("☺", color = ChatMuted, fontSize = 25.sp)
                        }
                    }
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
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Color(0xFFF7F9FC),
                            focusedContainerColor = Color(0xFFF7F9FC),
                            unfocusedBorderColor = Color(0xFFE5EBF2),
                            focusedBorderColor = Color(0xFF9CC2F0),
                        ),
                    )
                    IconButton(
                        onClick = {
                            if (!sending && draft.isNotBlank()) {
                                val content = draft
                                sending = true
                                scope.launch {
                                    val result = onSendMessage(content)
                                    if (result == ChatSendResult.Saved) draft = ""
                                    sending = false
                                }
                            }
                        },
                        enabled = draft.isNotBlank() && !sending,
                        modifier = Modifier
                            .padding(bottom = 3.dp)
                            .size(48.dp)
                            .background(
                                color = if (draft.isNotBlank() && !sending) ChatBlue else Color(0xFFB7C5D6),
                                shape = CircleShape,
                            )
                            .semantics {
                                contentDescription = sendDescription
                            },
                    ) {
                        Text("➤", color = Color.White, fontSize = 22.sp)
                    }
                }
            }
        }
    }
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
                color = if (outgoing) Color(0xFFE1EEFF) else Color.White,
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
                    Text(
                        text = message.body,
                        color = Color(0xFF20364F),
                        style = MaterialTheme.typography.bodyLarge,
                    )
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
                                    Color(0xFF1672C9)
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
        color = Color(0xD92C5B51),
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeyvandConnectionGlyph(Modifier.size(100.dp), inverted = true)
            Text(
                stringResource(R.string.chat_empty_conversation_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.chat_empty_conversation_body),
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 24.sp,
            )
        }
    }
}

@Composable
fun NearbyChatHubScreen(
    onBack: () -> Unit,
    onFindPeer: () -> Unit,
    onPrepareToBeFound: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("‹", fontSize = 34.sp, color = Color(0xFF1D3552)) }
            Text(
                stringResource(R.string.chat_nearby_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF182D47),
            )
        }
        Text(
            stringResource(R.string.chat_nearby_explanation),
            color = ChatMuted,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 23.sp,
        )
        NearbyRoleCard(
            glyph = "⌕",
            title = stringResource(R.string.chat_nearby_find_title),
            description = stringResource(R.string.chat_nearby_find_body),
            action = stringResource(R.string.chat_nearby_find_action),
            onClick = onFindPeer,
        )
        NearbyRoleCard(
            glyph = "⌁",
            title = stringResource(R.string.chat_nearby_ready_title),
            description = stringResource(R.string.chat_nearby_ready_body),
            action = stringResource(R.string.chat_nearby_ready_action),
            onClick = onPrepareToBeFound,
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.chat_nearby_local_only_note),
            modifier = Modifier.fillMaxWidth(),
            color = ChatMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NearbyRoleCard(
    glyph: String,
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(ChatPaleBlue, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(glyph, color = ChatBlue, fontSize = 26.sp)
                }
                Text(
                    title,
                    color = Color(0xFF1D3450),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(description, color = ChatMuted, style = MaterialTheme.typography.bodyMedium)
            Text(action, color = ChatBlue, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PeerAvatar(
    label: String,
    modifier: Modifier = Modifier,
) {
    val initials = label.firstOrNull()?.toString() ?: "پ"
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFFDCEBFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = ChatBlue,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PeyvandBrandGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ChatBlue),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            val stroke = size.minDimension * 0.085f
            drawLine(
                Color.White,
                Offset(size.width * 0.22f, size.height * 0.30f),
                Offset(size.width * 0.72f, size.height * 0.70f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                Color.White,
                Offset(size.width * 0.72f, size.height * 0.30f),
                Offset(size.width * 0.22f, size.height * 0.70f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(Color.White, size.minDimension * 0.13f, Offset(size.width * 0.22f, size.height * 0.30f))
            drawCircle(Color.White, size.minDimension * 0.13f, Offset(size.width * 0.72f, size.height * 0.30f))
            drawCircle(Color.White, size.minDimension * 0.13f, Offset(size.width * 0.22f, size.height * 0.70f))
            drawCircle(Color.White, size.minDimension * 0.13f, Offset(size.width * 0.72f, size.height * 0.70f))
        }
    }
}

@Composable
private fun PeyvandConnectionGlyph(
    modifier: Modifier = Modifier,
    inverted: Boolean = false,
) {
    val primary = if (inverted) Color.White else ChatBlue
    val secondary = if (inverted) Color.White.copy(alpha = 0.62f) else Color(0xFF8BB8EC)
    Canvas(modifier) {
        val stroke = size.minDimension * 0.035f
        val left = Offset(size.width * 0.23f, size.height * 0.50f)
        val right = Offset(size.width * 0.77f, size.height * 0.50f)
        val top = Offset(size.width * 0.50f, size.height * 0.22f)
        val bottom = Offset(size.width * 0.50f, size.height * 0.78f)
        drawLine(secondary, left, top, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(secondary, top, right, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(secondary, left, bottom, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(secondary, bottom, right, strokeWidth = stroke, cap = StrokeCap.Round)
        listOf(left, top, right, bottom).forEachIndexed { index, point ->
            drawCircle(if (index == 0 || index == 2) primary else secondary, size.minDimension * 0.105f, point)
            drawCircle(Color.White, size.minDimension * 0.035f, point)
        }
        drawCircle(secondary, size.minDimension * 0.10f, Offset(size.width * 0.50f, size.height * 0.50f))
        drawCircle(primary, size.minDimension * 0.045f, Offset(size.width * 0.50f, size.height * 0.50f))
    }
}

@Composable
private fun PeyvandChatWallpaper(modifier: Modifier = Modifier) {
    val ink = Color(0xFF5A82AA).copy(alpha = 0.11f)
    Canvas(modifier.background(Color(0xFFEAF2F8))) {
        val stepX = size.width / 4f
        val stepY = size.height / 6f
        for (row in 0..6) {
            for (column in 0..4) {
                val x = column * stepX + if (row % 2 == 0) stepX * 0.35f else 0f
                val y = row * stepY + stepY * 0.28f
                val radius = size.minDimension * 0.022f
                drawCircle(ink, radius, Offset(x, y))
                drawCircle(ink, radius * 0.48f, Offset(x + radius * 2.8f, y + radius * 1.8f))
                drawLine(
                    ink,
                    Offset(x + radius, y),
                    Offset(x + radius * 2.4f, y + radius * 1.8f),
                    strokeWidth = radius * 0.18f,
                )
                val bubble = Path().apply {
                    moveTo(x + radius * 3.5f, y - radius * 1.5f)
                    quadraticTo(x + radius * 5.2f, y - radius * 2.1f, x + radius * 5.7f, y - radius * 0.4f)
                    quadraticTo(x + radius * 5.8f, y + radius * 1.1f, x + radius * 4.1f, y + radius * 1.1f)
                    lineTo(x + radius * 3.7f, y + radius * 2f)
                    lineTo(x + radius * 3.75f, y + radius * 0.9f)
                    close()
                }
                drawPath(bubble, ink, style = Stroke(width = radius * 0.2f))
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(x - radius * 1.5f, y + radius * 3f),
                    size = androidx.compose.ui.geometry.Size(radius * 2.6f, radius * 3.3f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.35f),
                    style = Stroke(width = radius * 0.2f),
                )
            }
        }
    }
}

private fun formatChatTime(message: ChatMessage): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAtEpochMillis))
