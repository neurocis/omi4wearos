package com.omi4wos.wear.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val OmiColorPalette = Colors(
    primary = Color(0xFF6C63FF),
    primaryVariant = Color(0xFF4A42DB),
    secondary = Color(0xFF03DAC5),
    secondaryVariant = Color(0xFF018786),
    error = Color(0xFFCF6679),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onError = Color.Black,
    surface = Color(0xFF1E1E2E),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFCAC4D0),
    background = Color.Black,
    onBackground = Color.White
)

@Composable
fun Omi4wosTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = OmiColorPalette,
        content = content
    )
}
