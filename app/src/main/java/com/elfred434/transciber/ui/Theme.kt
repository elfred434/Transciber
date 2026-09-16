package com.elfred434.transciber.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF112822)
private val Forest = Color(0xFF1B6E58)
private val Mint = Color(0xFFB9F2D5)
private val Canvas = Color(0xFFF7F8F5)
private val DarkCanvas = Color(0xFF0F1714)

private val LightScheme = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    secondary = Color(0xFFDA7556),
    onSecondary = Color.White,
    background = Canvas,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6EFE9),
    onSurfaceVariant = Color(0xFF50635A)
)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    secondary = Color(0xFFFFA98D),
    onSecondary = Ink,
    background = DarkCanvas,
    surface = Color(0xFF17231E),
    onBackground = Color(0xFFE7F1EB),
    onSurface = Color(0xFFE7F1EB),
    surfaceVariant = Color(0xFF25362E),
    onSurfaceVariant = Color(0xFFB7C8BD)
)

@Composable
fun TransciberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
