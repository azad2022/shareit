package com.peyvand.app.presentation.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * PEYVAND-owned, Compose-native icon family for chat surfaces.
 * Geometry is vector-rendered at runtime; no external icon pack or copied messenger artwork.
 */
enum class PeyvandChatIconType {
    BACK,
    ADD,
    SEARCH,
    CHATS,
    TRANSFERS,
    NEARBY,
    VOICE_CALL,
    VIDEO_CALL,
    ATTACHMENT,
    EMOJI,
    SEND,
    RECEIVE,
}

@Composable
internal fun PeyvandChatIcon(
    type: PeyvandChatIconType,
    tint: Color,
    modifier: Modifier = Modifier.size(22.dp),
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.085f
        val center = Offset(w * 0.5f, h * 0.5f)

        fun line(from: Offset, to: Offset, width: Float = stroke) {
            drawLine(tint, from, to, strokeWidth = width, cap = StrokeCap.Round)
        }

        fun path(block: Path.() -> Unit) {
            val shape = Path().apply(block)
            drawPath(
                path = shape,
                color = tint,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        when (type) {
            PeyvandChatIconType.BACK -> {
                line(Offset(w * 0.78f, h * 0.5f), Offset(w * 0.22f, h * 0.5f))
                line(Offset(w * 0.22f, h * 0.5f), Offset(w * 0.48f, h * 0.23f))
                line(Offset(w * 0.22f, h * 0.5f), Offset(w * 0.48f, h * 0.77f))
            }
            PeyvandChatIconType.ADD -> {
                line(Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f))
                line(Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f))
            }
            PeyvandChatIconType.SEARCH -> {
                drawCircle(tint, radius = size.minDimension * 0.28f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(stroke))
                line(Offset(w * 0.62f, h * 0.62f), Offset(w * 0.84f, h * 0.84f))
            }
            PeyvandChatIconType.CHATS -> {
                path {
                    moveTo(w * 0.16f, h * 0.22f)
                    quadraticTo(w * 0.16f, h * 0.14f, w * 0.25f, h * 0.14f)
                    lineTo(w * 0.75f, h * 0.14f)
                    quadraticTo(w * 0.84f, h * 0.14f, w * 0.84f, h * 0.23f)
                    lineTo(w * 0.84f, h * 0.62f)
                    quadraticTo(w * 0.84f, h * 0.71f, w * 0.75f, h * 0.71f)
                    lineTo(w * 0.43f, h * 0.71f)
                    lineTo(w * 0.27f, h * 0.86f)
                    lineTo(w * 0.29f, h * 0.71f)
                    lineTo(w * 0.25f, h * 0.71f)
                    quadraticTo(w * 0.16f, h * 0.71f, w * 0.16f, h * 0.62f)
                    close()
                }
                line(Offset(w * 0.31f, h * 0.36f), Offset(w * 0.68f, h * 0.36f), stroke * 0.72f)
                line(Offset(w * 0.31f, h * 0.51f), Offset(w * 0.58f, h * 0.51f), stroke * 0.72f)
            }
            PeyvandChatIconType.TRANSFERS -> {
                line(Offset(w * 0.16f, h * 0.34f), Offset(w * 0.82f, h * 0.34f))
                line(Offset(w * 0.82f, h * 0.34f), Offset(w * 0.65f, h * 0.18f))
                line(Offset(w * 0.82f, h * 0.34f), Offset(w * 0.65f, h * 0.5f))
                line(Offset(w * 0.84f, h * 0.68f), Offset(w * 0.18f, h * 0.68f))
                line(Offset(w * 0.18f, h * 0.68f), Offset(w * 0.35f, h * 0.52f))
                line(Offset(w * 0.18f, h * 0.68f), Offset(w * 0.35f, h * 0.84f))
            }
            PeyvandChatIconType.NEARBY -> {
                drawCircle(tint, radius = size.minDimension * 0.32f, center = center, style = Stroke(stroke))
                drawCircle(tint, radius = size.minDimension * 0.12f, center = center, style = Stroke(stroke))
                line(Offset(w * 0.5f, h * 0.04f), Offset(w * 0.5f, h * 0.22f), stroke * 0.8f)
                line(Offset(w * 0.5f, h * 0.78f), Offset(w * 0.5f, h * 0.96f), stroke * 0.8f)
                line(Offset(w * 0.04f, h * 0.5f), Offset(w * 0.22f, h * 0.5f), stroke * 0.8f)
                line(Offset(w * 0.78f, h * 0.5f), Offset(w * 0.96f, h * 0.5f), stroke * 0.8f)
            }
            PeyvandChatIconType.VOICE_CALL -> {
                path {
                    moveTo(w * 0.29f, h * 0.13f)
                    quadraticTo(w * 0.19f, h * 0.08f, w * 0.14f, h * 0.2f)
                    quadraticTo(w * 0.1f, h * 0.45f, w * 0.37f, h * 0.7f)
                    quadraticTo(w * 0.63f, h * 0.96f, w * 0.82f, h * 0.85f)
                    quadraticTo(w * 0.94f, h * 0.79f, w * 0.87f, h * 0.67f)
                    lineTo(w * 0.69f, h * 0.55f)
                    lineTo(w * 0.55f, h * 0.68f)
                    quadraticTo(w * 0.36f, h * 0.59f, w * 0.31f, h * 0.39f)
                    lineTo(w * 0.43f, h * 0.25f)
                    close()
                }
            }
            PeyvandChatIconType.VIDEO_CALL -> {
                path {
                    moveTo(w * 0.13f, h * 0.25f)
                    quadraticTo(w * 0.13f, h * 0.17f, w * 0.22f, h * 0.17f)
                    lineTo(w * 0.58f, h * 0.17f)
                    quadraticTo(w * 0.67f, h * 0.17f, w * 0.67f, h * 0.26f)
                    lineTo(w * 0.67f, h * 0.74f)
                    quadraticTo(w * 0.67f, h * 0.83f, w * 0.58f, h * 0.83f)
                    lineTo(w * 0.22f, h * 0.83f)
                    quadraticTo(w * 0.13f, h * 0.83f, w * 0.13f, h * 0.74f)
                    close()
                }
                path {
                    moveTo(w * 0.71f, h * 0.4f)
                    lineTo(w * 0.9f, h * 0.28f)
                    lineTo(w * 0.9f, h * 0.72f)
                    lineTo(w * 0.71f, h * 0.6f)
                    close()
                }
            }
            PeyvandChatIconType.ATTACHMENT -> {
                path {
                    moveTo(w * 0.62f, h * 0.29f)
                    lineTo(w * 0.36f, h * 0.57f)
                    quadraticTo(w * 0.27f, h * 0.66f, w * 0.36f, h * 0.75f)
                    quadraticTo(w * 0.45f, h * 0.84f, w * 0.54f, h * 0.75f)
                    lineTo(w * 0.78f, h * 0.48f)
                    quadraticTo(w * 0.91f, h * 0.34f, w * 0.78f, h * 0.21f)
                    quadraticTo(w * 0.65f, h * 0.08f, w * 0.52f, h * 0.21f)
                    lineTo(w * 0.25f, h * 0.51f)
                    quadraticTo(w * 0.03f, h * 0.74f, w * 0.25f, h * 0.92f)
                    quadraticTo(w * 0.48f, h * 1.1f, w * 0.69f, h * 0.89f)
                }
            }
            PeyvandChatIconType.EMOJI -> {
                drawCircle(tint, radius = size.minDimension * 0.39f, center = center, style = Stroke(stroke))
                drawCircle(tint, radius = stroke * 0.75f, center = Offset(w * 0.37f, h * 0.42f))
                drawCircle(tint, radius = stroke * 0.75f, center = Offset(w * 0.63f, h * 0.42f))
                path {
                    moveTo(w * 0.3f, h * 0.58f)
                    quadraticTo(w * 0.5f, h * 0.78f, w * 0.7f, h * 0.58f)
                }
            }
            PeyvandChatIconType.SEND -> {
                path {
                    moveTo(w * 0.12f, h * 0.47f)
                    lineTo(w * 0.89f, h * 0.12f)
                    lineTo(w * 0.66f, h * 0.89f)
                    lineTo(w * 0.48f, h * 0.58f)
                    close()
                }
                line(Offset(w * 0.48f, h * 0.58f), Offset(w * 0.89f, h * 0.12f), stroke * 0.75f)
            }
            PeyvandChatIconType.RECEIVE -> {
                line(Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.72f))
                line(Offset(w * 0.5f, h * 0.72f), Offset(w * 0.26f, h * 0.48f))
                line(Offset(w * 0.5f, h * 0.72f), Offset(w * 0.74f, h * 0.48f))
                line(Offset(w * 0.2f, h * 0.88f), Offset(w * 0.8f, h * 0.88f))
            }
        }
    }
}
