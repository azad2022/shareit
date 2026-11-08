package com.peyvand.app.presentation

import android.animation.ValueAnimator
import android.graphics.Color as AndroidColor
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.peyvand.app.R

private const val STARTUP_SPLASH_RESOURCE_NAME = "peyvand_splash"

@Composable
fun StartupLoadingScreen() {
    val context = LocalContext.current
    val splashVideoResourceId = remember(context) {
        context.resources.getIdentifier(
            STARTUP_SPLASH_RESOURCE_NAME,
            "raw",
            context.packageName,
        )
    }
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    var videoFailed by remember { mutableStateOf(false) }
    var videoCompleted by remember { mutableStateOf(false) }

    if (splashVideoResourceId == 0 || !motionEnabled || videoFailed || videoCompleted) {
        StartupStaticLoadingScreen()
    } else {
        StartupVideoLoadingScreen(
            videoResourceId = splashVideoResourceId,
            onCompleted = { videoCompleted = true },
            onFailed = { videoFailed = true },
        )
    }
}

@Composable
private fun StartupVideoLoadingScreen(
    videoResourceId: Int,
    onCompleted: () -> Unit,
    onFailed: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var videoView by remember(videoResourceId) { mutableStateOf<VideoView?>(null) }
    var isPrepared by remember(videoResourceId) { mutableStateOf(false) }
    val shouldPlay by rememberUpdatedState(isPrepared)

    DisposableEffect(lifecycleOwner, videoView) {
        val currentVideoView = videoView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> runCatching { currentVideoView?.pause() }
                Lifecycle.Event.ON_RESUME -> {
                    if (shouldPlay) runCatching { currentVideoView?.start() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && shouldPlay) {
            runCatching { currentVideoView?.start() }
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { currentVideoView?.stopPlayback() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val videoWidth = min(maxWidth, maxHeight * (9f / 16f))
            AndroidView(
                factory = { viewContext ->
                    VideoView(viewContext).apply {
                        setBackgroundColor(AndroidColor.WHITE)
                        setOnPreparedListener { player: MediaPlayer ->
                            player.setVolume(0f, 0f)
                            player.isLooping = false
                            isPrepared = true
                            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                start()
                            }
                        }
                        setOnCompletionListener { onCompleted() }
                        setOnErrorListener { _, _, _ ->
                            onFailed()
                            true
                        }
                        setVideoURI(
                            Uri.parse("android.resource://${viewContext.packageName}/$videoResourceId"),
                        )
                    }.also { videoView = it }
                },
                modifier = Modifier
                    .width(videoWidth)
                    .aspectRatio(9f / 16f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.app_startup_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StartupStaticLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.brand_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.app_startup_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StartupErrorScreen(
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.app_startup_failed_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.app_startup_failed_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
