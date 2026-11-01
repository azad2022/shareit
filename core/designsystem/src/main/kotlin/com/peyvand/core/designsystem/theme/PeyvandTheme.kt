package com.peyvand.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PeyvandLightColorScheme = lightColorScheme(
    primary = PeyvandColors.PrimaryBlue,
    onPrimary = PeyvandColors.White,
    primaryContainer = PeyvandColors.PrimaryBlueContainer,
    onPrimaryContainer = PeyvandColors.OnPrimaryBlueContainer,
    secondary = PeyvandColors.PrimaryBlueDark,
    onSecondary = PeyvandColors.White,
    background = PeyvandColors.Background,
    onBackground = PeyvandColors.OnSurface,
    surface = PeyvandColors.Surface,
    onSurface = PeyvandColors.OnSurface,
    surfaceVariant = PeyvandColors.SurfaceVariant,
    onSurfaceVariant = PeyvandColors.OnSurfaceVariant,
    outline = PeyvandColors.Outline,
    error = PeyvandColors.Error,
    onError = PeyvandColors.White,
)

@Composable
fun PeyvandTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PeyvandLightColorScheme,
        content = content,
    )
}
