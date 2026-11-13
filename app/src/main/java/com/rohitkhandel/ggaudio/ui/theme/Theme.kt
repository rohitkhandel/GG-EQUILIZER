package com.rohitkhandel.ggaudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GgColors = darkColorScheme(
    primary = Accent,
    onPrimary = Background,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextMuted,
    outline = Outline
)

@Composable
fun GgAudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GgColors,
        content = content
    )
}
