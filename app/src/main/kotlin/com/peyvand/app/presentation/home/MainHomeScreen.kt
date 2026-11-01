package com.peyvand.app.presentation.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peyvand.app.R

@Composable
fun MainHomeScreen(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onFiles: () -> Unit,
    onActivity: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.home_brand_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.4).sp,
                )
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NotificationButton(onClick = onActivity)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeActionButton(
                    title = stringResource(R.string.home_send),
                    subtitle = stringResource(R.string.home_send_subtitle),
                    glyph = "↗",
                    onClick = onSend,
                )
                HomeActionButton(
                    title = stringResource(R.string.home_receive),
                    subtitle = stringResource(R.string.home_receive_subtitle),
                    glyph = "↓",
                    onClick = onReceive,
                )
                HomeActionButton(
                    title = stringResource(R.string.home_files),
                    subtitle = stringResource(R.string.home_files_subtitle),
                    glyph = "▤",
                    onClick = onFiles,
                )
            }
        }

        Text(
            text = stringResource(R.string.home_local_transfer_hint),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeActionButton(
    title: String,
    subtitle: String,
    glyph: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = glyph,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f),
                )
            }
        }
    }
}

@Composable
private fun NotificationButton(
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.home_activity)
    val bellTint = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(25.dp)) {
            val bell = Path().apply {
                moveTo(size.width * 0.5f, size.height * 0.12f)
                cubicTo(
                    size.width * 0.28f, size.height * 0.12f,
                    size.width * 0.25f, size.height * 0.34f,
                    size.width * 0.25f, size.height * 0.55f,
                )
                lineTo(size.width * 0.16f, size.height * 0.76f)
                quadraticTo(
                    size.width * 0.5f, size.height * 0.88f,
                    size.width * 0.84f, size.height * 0.76f,
                )
                lineTo(size.width * 0.75f, size.height * 0.55f)
                cubicTo(
                    size.width * 0.75f, size.height * 0.34f,
                    size.width * 0.72f, size.height * 0.12f,
                    size.width * 0.5f, size.height * 0.12f,
                )
                close()
            }
            drawPath(bell, color = bellTint)
            drawCircle(
                color = bellTint,
                radius = size.minDimension * 0.055f,
                center = Offset(size.width * 0.5f, size.height * 0.91f),
            )
        }
    }
}
