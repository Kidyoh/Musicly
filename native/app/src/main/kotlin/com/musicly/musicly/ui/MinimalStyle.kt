package com.musicly.musicly.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.player.PlayerController
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The "Minimal" player style: soft greys, one small orange accent, round
 * shapes, and surfaces that look gently raised. Light and dark versions.
 */
data class MiniPalette(
    val dark: Boolean,
    val bg: Color, // screen
    val panel: Color, // the control panel
    val tile: Color, // round buttons
    val ink: Color, // text and the progress marker
    val sub: Color, // quiet text
    val line: Color, // inactive dots and hairlines
    val accent: Color, // the one splash of colour
    val hi: Color, // highlight of a raised surface
    val lo: Color, // shade of a raised surface
) {
    companion object {
        val light = MiniPalette(
            dark = false,
            bg = Color(0xFFECEDEF),
            panel = Color(0xFFF7F7F8),
            tile = Color(0xFFFFFFFF),
            ink = Color(0xFF0F1013),
            sub = Color(0xFF9DA1AC),
            line = Color(0xFFD5D8DE),
            accent = Color(0xFFF26A1B),
            hi = Color(0xFFFFFFFF),
            lo = Color(0xFFD0D3DA),
        )

        val darkTheme = MiniPalette(
            dark = true,
            bg = Color(0xFF111214),
            panel = Color(0xFF17181B),
            tile = Color(0xFF222327),
            ink = Color(0xFFF4F5F7),
            sub = Color(0xFF727681),
            line = Color(0xFF2E3036),
            accent = Color(0xFFFF7A2E),
            hi = Color(0xFF26272C),
            lo = Color(0xFF070708),
        )

        fun of(dark: Boolean): MiniPalette = if (dark) darkTheme else light
    }
}

/** The Minimal palette matching the current light/dark theme. */
@Composable
fun miniPalette(): MiniPalette = MiniPalette.of(palette().dark)

/**
 * A blurred box shadow like Flutter's BoxShadow. [blur] is Flutter's blurRadius
 * (sigma = blur / 2). [corner] is the corner radius; pass a huge value for a circle.
 */
fun Modifier.blurShadow(
    color: Color,
    blur: Dp,
    dx: Dp = 0.dp,
    dy: Dp = 0.dp,
    corner: Dp = 0.dp,
): Modifier = drawBehind {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = color.toArgb()
        val sigma = blur.toPx() / 2f
        if (sigma > 0.5f) {
            paint.maskFilter = android.graphics.BlurMaskFilter(
                max((sigma - 0.5f) / 0.57735f, 0.1f),
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
        val l = dx.toPx()
        val t = dy.toPx()
        val r = min(corner.toPx(), min(size.width, size.height) / 2f)
        canvas.nativeCanvas.drawRoundRect(l, t, l + size.width, t + size.height, r, r, paint)
    }
}

/** A surface that looks pressed out of the background. */
fun Modifier.miniRaised(m: MiniPalette, corner: Dp = 1000.dp): Modifier =
    this
        .blurShadow(m.hi, 14.dp, (-6).dp, (-6).dp, corner)
        .blurShadow(m.lo, 16.dp, 7.dp, 7.dp, corner)

/** A gentle drop shadow for panels and round buttons. */
fun Modifier.miniSoft(m: MiniPalette, corner: Dp = 1000.dp): Modifier =
    blurShadow(Color.Black.copy(alpha = if (m.dark) 0.45f else 0.07f), 22.dp, 0.dp, 8.dp, corner)

/**
 * A ring of dots that fills as the song plays, with a short tick at the
 * current position (the "playhead"). [dotRadius] and [inset] are in dp.
 */
fun DrawScope.drawDotRing(
    frac: Float,
    on: Color,
    off: Color,
    marker: Color,
    dots: Int = 72,
    showMarker: Boolean = true,
    dotRadius: Float = 2.2f,
    inset: Float = 8f,
) {
    val d = density
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.width / 2 - inset * d
    val scale = size.width / d / 340f
    val sc = max(scale, .5f)
    for (i in 0 until dots) {
        val a = -Math.PI / 2 + 2 * Math.PI * i / dots
        val lit = (i + .5f) / dots <= frac
        drawCircle(
            if (lit) on else off,
            radius = (if (lit) dotRadius * 1.2f else dotRadius) * sc * d,
            center = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * r,
        )
    }
    if (showMarker) {
        val a = -Math.PI / 2 + 2 * Math.PI * frac
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
        drawLine(
            marker,
            c + dir * (r - 24 * scale * d),
            c + dir * (r - 6 * scale * d),
            strokeWidth = 5 * sc * d,
            cap = StrokeCap.Round,
        )
    }
}

/** Polls the player position every [intervalMs] (it is a plain getter, not state). */
@Composable
fun rememberPlayerPositionMs(c: PlayerController, intervalMs: Long = 100): State<Long> {
    val s = remember { mutableLongStateOf(c.positionMs) }
    LaunchedEffect(c) {
        while (true) {
            s.longValue = c.positionMs
            delay(intervalMs)
        }
    }
    return object : State<Long> {
        override val value: Long get() = s.longValue
    }
}

/**
 * A round button on the control panel, with a tiny dot that turns orange
 * when the feature is on.
 */
@Composable
fun MiniTile(
    palette: MiniPalette,
    onTap: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
    icon: Ph? = null,
    label: String? = null,
    active: Boolean = false,
    size: Dp = 52.dp,
) {
    val m = palette
    val dot by animateColorAsState(if (active) m.accent else m.line, tween(250), label = "dot")
    Box(
        modifier
            .size(size)
            .miniSoft(m)
            .background(m.tile, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, size = size * .4f, tint = if (active) m.ink else m.sub)
        if (label != null) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = Inter,
                    fontSize = (size.value * .27f).sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) m.ink else m.sub,
                ),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(size * .2f, size * .2f)
                .size(6.dp)
                .background(dot, CircleShape),
        )
    }
}
