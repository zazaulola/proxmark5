package io.github.zazaulola.proxmark5.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.zazaulola.proxmark5.parse.AnsiColor

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BC0BE),
    secondary = Color(0xFF6FFFE9),
    background = Color(0xFF0B132B),
    surface = Color(0xFF1C2541),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6F6B),
    secondary = Color(0xFF3A506B),
)

@Composable
fun Pm3Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Maps the client's 16 ANSI colors to readable on-screen colors (tuned for dark). */
fun AnsiColor.toColor(): Color = when (this) {
    AnsiColor.BLACK -> Color(0xFF3B3B3B)
    AnsiColor.RED -> Color(0xFFFF6B6B)
    AnsiColor.GREEN -> Color(0xFF6BE675)
    AnsiColor.YELLOW -> Color(0xFFFFD166)
    AnsiColor.BLUE -> Color(0xFF6CA0FF)
    AnsiColor.MAGENTA -> Color(0xFFE39BFF)
    AnsiColor.CYAN -> Color(0xFF5BC0BE)
    AnsiColor.WHITE -> Color(0xFFE6E6E6)
    AnsiColor.BRIGHT_BLACK -> Color(0xFF8A8A8A)
    AnsiColor.BRIGHT_RED -> Color(0xFFFF8787)
    AnsiColor.BRIGHT_GREEN -> Color(0xFF95F985)
    AnsiColor.BRIGHT_YELLOW -> Color(0xFFFFE08A)
    AnsiColor.BRIGHT_BLUE -> Color(0xFF8FB8FF)
    AnsiColor.BRIGHT_MAGENTA -> Color(0xFFEFB3FF)
    AnsiColor.BRIGHT_CYAN -> Color(0xFF8CE0DF)
    AnsiColor.BRIGHT_WHITE -> Color(0xFFFFFFFF)
}
