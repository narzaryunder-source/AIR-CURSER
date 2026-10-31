package com.brightmax.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFC107)
val AmberDeep = Color(0xFFFF8F00)
val HotRed = Color(0xFFFF5252)
val CoolGreen = Color(0xFF69F0AE)
val Background = Color(0xFF0B0E13)
val SurfaceCard = Color(0xFF161B22)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1300),
    secondary = AmberDeep,
    background = Background,
    onBackground = Color(0xFFE6E8EB),
    surface = SurfaceCard,
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1F2630),
    onSurfaceVariant = Color(0xFFB0B8C4),
    error = HotRed,
)

/** BrightMax is dark-only. */
@Composable
fun BrightMaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
