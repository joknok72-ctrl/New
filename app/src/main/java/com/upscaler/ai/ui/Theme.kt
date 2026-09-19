package com.upscaler.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0B0F1A)
val Surface1 = Color(0xFF131A2B)
val Surface2 = Color(0xFF1B2438)
val Accent = Color(0xFF22D3EE)
val Accent2 = Color(0xFF818CF8)
val Pink = Color(0xFFF472B6)
val Green = Color(0xFF34D399)
val Amber = Color(0xFFFBBF24)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    secondary = Accent2,
    background = Bg,
    surface = Surface1,
    surfaceVariant = Surface2,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = Color(0xFFF87171),
)

@Composable
fun UpscalerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
