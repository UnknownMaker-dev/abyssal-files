package com.abyssal.files

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Logo purple. */
const val DEFAULT_ACCENT = 0xFFA324EC.toInt()

/** Accent picked in Settings; the dark surfaces are lightly tinted with it. */
object Pal {
    var accent by mutableStateOf(Color(DEFAULT_ACCENT))

    /** "RGB" preset: the accent keeps cycling through the hues (driven from MainActivity). */
    var rgb by mutableStateOf(false)

    /** false: every icon uses the accent; true: each file type keeps its own colour (VS Code style). */
    var colorfulIcons by mutableStateOf(false)
}

val Accent: Color get() = Pal.accent
val Bg: Color get() = lerp(Color(0xFF0D0E11), Pal.accent, 0.035f)
val Panel: Color get() = lerp(Color(0xFF17181D), Pal.accent, 0.06f)
val Panel2: Color get() = lerp(Color(0xFF212329), Pal.accent, 0.09f)
val OnAccent: Color get() = if (Pal.accent.luminance() > 0.45f) Color(0xFF111111) else Color.White
val Ink = Color(0xFFE6E8EB)
val Dim = Color(0xFF8B929B)
val Danger = Color(0xFFFF6B6B)
val Gold = Color(0xFFFFC83D)

@Composable
fun FmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            secondary = Accent,
            onSecondary = OnAccent,
            secondaryContainer = lerp(Panel2, Accent, 0.35f),
            onSecondaryContainer = Ink,
            background = Bg,
            surface = Panel,
            onSurface = Ink,
            onBackground = Ink,
            surfaceVariant = Panel2,
            onSurfaceVariant = Dim,
            surfaceContainer = Panel2,
            surfaceContainerHigh = Panel2,
            surfaceContainerHighest = Panel2,
            inverseSurface = lerp(Color(0xFF2C2E35), Pal.accent, 0.15f),
            inverseOnSurface = Ink,
            inversePrimary = Accent,
            outline = Dim,
            outlineVariant = Panel2,
            error = Danger,
        ),
        content = content,
    )
}
