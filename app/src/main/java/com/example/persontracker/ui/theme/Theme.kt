package com.example.persontracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00232F),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF121B2E),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1B2740),
    onSurfaceVariant = Color(0xFF9FB0CC),
    error = Color(0xFFFF6B6B),
)

/** ثيم داكن دائمًا: مناسب لشاشات المراقبة ويوفّر بطارية شاشات OLED. */
@Composable
fun PersonTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
