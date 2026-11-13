package com.peyvand.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peyvand.app.R
import com.peyvand.core.designsystem.theme.PeyvandColors
import com.peyvand.core.domain.chat.ChatMessageDeliveryState
import com.peyvand.core.domain.chat.ChatMessageDirection
import com.peyvand.core.domain.chat.PeerIdentityId

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
                PeyvandChatIcon(PeyvandChatIconType.ADD, ChatBlue)
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
                color = PeyvandColors.OnSurface,
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
                leadingIcon = { PeyvandChatIcon(PeyvandChatIconType.SEARCH, ChatMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White,
                    unfocusedBorderColor = PeyvandColors.SurfaceVariant,
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
                    color = PeyvandColors.OnSurface,
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
                            isSyncChannelReady = state.activeChatPeerFingerprint ==
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
            icon = { PeyvandChatIcon(PeyvandChatIconType.CHATS, if (selected == ChatBottomDestination.CHATS) ChatBlue else ChatMuted) },
            label = { Text(stringResource(R.string.chat_tab_chats)) },
        )
        NavigationBarItem(
            selected = selected == ChatBottomDestination.TRANSFERS,
            onClick = onTransfers,
            icon = { PeyvandChatIcon(PeyvandChatIconType.TRANSFERS, if (selected == ChatBottomDestination.TRANSFERS) ChatBlue else ChatMuted) },
            label = { Text(stringResource(R.string.chat_tab_transfers)) },
        )
        NavigationBarItem(
            selected = selected == ChatBottomDestination.NEARBY,
            onClick = onNearby,
            icon = { PeyvandChatIcon(PeyvandChatIconType.NEARBY, if (selected == ChatBottomDestination.NEARBY) ChatBlue else ChatMuted) },
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
        color = PeyvandColors.PrimaryBlue,
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
                PeyvandChatIcon(PeyvandChatIconType.NEARBY, Color.White, Modifier.size(27.dp))
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
            PeyvandChatIcon(PeyvandChatIconType.BACK, Color.White, Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ChatConversationRow(
    preview: ChatConversationPreview,
    isConnected: Boolean,
    isSyncChannelReady: Boolean,
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
                        color = PeyvandColors.OnSurface,
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
                                last.deliveryState == ChatMessageDeliveryState.WAITING_FOR_CONNECTION &&
                                isConnected && isSyncChannelReady ->
                                stringResource(R.string.chat_message_syncing)
                            last.direction == ChatMessageDirection.OUTGOING &&
                                last.deliveryState == ChatMessageDeliveryState.WAITING_FOR_CONNECTION &&
                                isConnected ->
                                stringResource(R.string.chat_message_channel_unavailable)
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
                            color = if (isConnected) PeyvandColors.Success else ChatMuted,
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
            color = PeyvandColors.OnSurface,
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
