package com.scamkill.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ScamKillColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = TextPrimary,
    primaryContainer = SurfaceVariant,
    secondary = SafeGreen,
    onSecondary = Background,
    error = ScamRed,
    onError = TextPrimary,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextDim,
    outline = Border,
)

@Composable
fun ScamKillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ScamKillColorScheme,
        typography = Typography,
        content = content
    )
}
