package com.peyvand.app.presentation.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peyvand.core.designsystem.theme.PeyvandColors
import com.peyvand.core.domain.chat.ChatMessage
import java.text.DateFormat
import java.util.Date

internal val ChatCanvas = PeyvandColors.Background
internal val ChatBlue = PeyvandColors.PrimaryBlue
internal val ChatPaleBlue = PeyvandColors.PrimaryBlueContainer
internal val ChatMuted = PeyvandColors.OnSurfaceVariant

@Composable
internal fun PeerAvatar(
    label: String,
    modifier: Modifier = Modifier,
) {
    val initials = label.firstOrNull()?.toString() ?: "پ"
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(PeyvandColors.PrimaryBlueContainer),
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
internal fun PeyvandBrandGlyph(modifier: Modifier = Modifier) {
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
internal fun PeyvandConnectionGlyph(
    modifier: Modifier = Modifier,
    inverted: Boolean = false,
) {
    val primary = if (inverted) Color.White else ChatBlue
    val secondary = if (inverted) Color.White.copy(alpha = 0.62f) else PeyvandColors.PrimaryBlue
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
internal fun PeyvandChatWallpaper(modifier: Modifier = Modifier) {
    val ink = PeyvandColors.Outline.copy(alpha = 0.11f)
    Canvas(modifier.background(PeyvandColors.Background)) {
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

internal fun formatChatTime(message: ChatMessage): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAtEpochMillis))
