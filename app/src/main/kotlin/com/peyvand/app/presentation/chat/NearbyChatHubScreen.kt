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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peyvand.app.R
import com.peyvand.core.designsystem.theme.PeyvandColors

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
            IconButton(onClick = onBack) { PeyvandChatIcon(PeyvandChatIconType.BACK, PeyvandColors.OnSurface, Modifier.size(24.dp)) }
            Text(
                stringResource(R.string.chat_nearby_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = PeyvandColors.OnSurface,
            )
        }
        Text(
            stringResource(R.string.chat_nearby_explanation),
            color = ChatMuted,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 23.sp,
        )
        NearbyRoleCard(
            glyph = PeyvandChatIconType.SEARCH,
            title = stringResource(R.string.chat_nearby_find_title),
            description = stringResource(R.string.chat_nearby_find_body),
            action = stringResource(R.string.chat_nearby_find_action),
            onClick = onFindPeer,
        )
        NearbyRoleCard(
            glyph = PeyvandChatIconType.NEARBY,
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
    glyph: PeyvandChatIconType,
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
                    PeyvandChatIcon(glyph, ChatBlue, Modifier.size(26.dp))
                }
                Text(
                    title,
                    color = PeyvandColors.OnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(description, color = ChatMuted, style = MaterialTheme.typography.bodyMedium)
            Text(action, color = ChatBlue, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}
