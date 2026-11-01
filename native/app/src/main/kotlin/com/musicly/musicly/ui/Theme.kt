package com.musicly.musicly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.musicly.musicly.R

/**
 * Monochrome palette taken from the design: near-black ink on white, with
 * blue-grey secondary text and soft grey surfaces.
 */
data class Palette(
    val dark: Boolean,
    val bg: Color, // screen background
    val card: Color, // soft grey buttons & chips
    val ink: Color, // primary text, filled buttons
    val onInk: Color, // content on ink
    val sub: Color, // secondary text
    val line: Color, // dividers, inactive waveform
    val dock: Color, // mini player
    val onDock: Color,
) {
    companion object {
        val light = Palette(false, Color(0xFFFFFFFF), Color(0xFFF1F2F5), Color(0xFF1C1D22), Color(0xFFFFFFFF), Color(0xFF8A8FA3), Color(0xFFDCDEE5), Color(0xFF1C1D22), Color(0xFFFFFFFF))
        val darkPalette = Palette(true, Color(0xFF0E0F12), Color(0xFF1C1D22), Color(0xFFF4F5F7), Color(0xFF0E0F12), Color(0xFF8A8FA3), Color(0xFF2C2E35), Color(0xFF24252B), Color(0xFFFFFFFF))
    }
}

val LocalPalette = staticCompositionLocalOf { Palette.light }

/** Shorthand used by every screen: `val p = palette()`. */
@Composable
fun palette(): Palette = LocalPalette.current

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

@Composable
fun MusiclyTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) Palette.darkPalette else Palette.light
    val scheme: ColorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.ink, onPrimary = p.onInk, secondary = p.sub, onSecondary = p.onInk,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
        surfaceVariant = p.card, onSurfaceVariant = p.sub, surfaceContainer = p.card,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.card,
        outline = p.line, outlineVariant = p.line, error = Color(0xFFD64545),
    )
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = Inter)
    val typography = Typography(
        displayLarge = base.displayLarge.inter(), displayMedium = base.displayMedium.inter(), displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(), headlineMedium = base.headlineMedium.inter(), headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(), titleMedium = base.titleMedium.inter(), titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter(), bodyMedium = base.bodyMedium.inter(), bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(), labelMedium = base.labelMedium.inter(), labelSmall = base.labelSmall.inter(),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
