package com.peyvand.app.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peyvand.app.presentation.home.HomeScreen
import com.peyvand.app.presentation.language.LanguageSelectionScreen

@Composable
fun PeyvandApp(
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = uiState,
        contentKey = { state -> state::class },
        transitionSpec = {
            (fadeIn(tween(durationMillis = 220)) +
                slideInVertically(tween(durationMillis = 220)) { height -> height / 24 }) togetherWith
                (fadeOut(tween(durationMillis = 160)) +
                    slideOutVertically(tween(durationMillis = 160)) { height -> -height / 24 })
        },
        label = "peyvand-main-route",
    ) { targetState ->
        when (targetState) {
            is MainUiState.LanguageSelection -> LanguageSelectionScreen(
                isSaving = targetState.isSaving,
                hasError = targetState.hasError,
                onContinue = viewModel::confirmLanguageSelection,
            )

            is MainUiState.Home -> HomeScreen()
        }
    }
}
