@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.PlayerStyle

private val Coral = Color(0xFFFF8A5B)
private val Berry = Color(0xFFC2185B)

/**
 * Choose how the player looks. The choice applies to Now Playing and the
 * mini player straight away.
 */
fun showPlayerStyle(env: Env) {
    env.showSheet { _ -> StyleSheet(env) }
}

@Composable
private fun StyleSheet(env: Env) {
    val lib = env.library.observe()
    val p = palette()

    @Composable
    fun RowScopeCard(modifier: Modifier, style: PlayerStyle, name: String, note: String, preview: @Composable () -> Unit) {
        val on = lib.playerStyle == style
        val border by animateColorAsState(if (on) p.ink else Color.Transparent, tween(200), label = "border")
        Column(
            modifier.clip(RoundedCornerShape(24.dp)).background(p.card)
                .border(BorderStroke(2.dp, border), RoundedCornerShape(24.dp))
                .tap {
                    lib.setPlayerStyle(style)
                    env.toast("$name player")
                }
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(.62f).clip(RoundedCornerShape(16.dp))) { preview() }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (on) {
                    Icon(AppIcons.check, size = 16.dp, tint = p.ink)
                    Spacer(Modifier.width(6.dp))
                }
                Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.ink)
            }
            Spacer(Modifier.height(2.dp))
            Text(note, textAlign = TextAlign.Center, color = p.sub, fontSize = 12.5.sp, lineHeight = 16.25.sp)
        }
    }

    Column(Modifier.navigationBarsPadding().padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 20.dp)) {
        Text("Player style", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.ink)
        Spacer(Modifier.height(4.dp))
        Text("How Now Playing and the mini player look. Change it any time.", color = p.sub, fontSize = 14.sp, lineHeight = 19.6.sp)
        Spacer(Modifier.height(16.dp))
        Row {
            RowScopeCard(Modifier.weight(1f), PlayerStyle.classic, "Classic", "Cover art, glow and a waveform") { ClassicPreview() }
            Spacer(Modifier.width(12.dp))
            RowScopeCard(Modifier.weight(1f), PlayerStyle.minimal, "Minimal", "A turning disc and a click-wheel") { MinimalPreview() }
        }
    }
}

@Composable
private fun ClassicPreview() {
    val p = palette()
    val heights = listOf(.3, .6, .9, .5, 1.0, .7, .4, .8, .55, .35, .65, .45)
    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg)) {
        val w = maxWidth
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.verticalGradient(listOf(Coral.copy(alpha = .35f), p.bg), startY = 0f, endY = size.height / 2))
            },
        )
        val cover = w * .72f
        val coverShape = RoundedCornerShape(w * .1f)
        Box(
            Modifier.offset(w * .14f, w * .16f).size(cover)
                .shadow(12.dp, coverShape, ambientColor = Berry.copy(alpha = .35f), spotColor = Berry.copy(alpha = .35f))
                .clip(coverShape)
                .background(Brush.linearGradient(listOf(Coral, Berry), start = Offset.Zero, end = Offset.Infinite)),
        )
        Row(
            Modifier.offset(w * .14f, w * 1.05f).width(w * .72f).height(w * .16f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (h in heights) {
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.width(w * .022f).height(w * .16f * h.toFloat())
                            .clip(RoundedCornerShape(4.dp)).background(if (h > .6) p.ink else p.line),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().offset(0.dp, w * 1.3f), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.size(w * .2f).clip(CircleShape).background(p.ink), contentAlignment = Alignment.Center) {
                Icon(AppIcons.play, size = w * .09f, tint = p.onInk)
            }
        }
    }
}

@Composable
private fun MinimalPreview() {
    val m = miniPalette()
    BoxWithConstraints(Modifier.fillMaxSize().background(m.bg)) {
        val w = maxWidth
        val disc: Dp = w * .9f
        val wheel: Dp = w * .5f
        Box(Modifier.offset(w * .05f, w * .08f).size(disc)) {
            Canvas(Modifier.fillMaxSize()) {
                drawDotRing(frac = .38f, on = m.ink, off = m.line, marker = m.ink, dots = 48, dotRadius = 2.4f, inset = 4f)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(disc * .62f).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Coral, Berry), start = Offset.Zero, end = Offset.Infinite))
                        .border(3.dp, m.panel, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(disc * .09f).clip(CircleShape).background(m.bg))
                }
            }
        }
        Box(
            Modifier.offset((w - wheel) / 2, w * 1.06f).size(wheel).miniSoft(m).clip(CircleShape).background(m.tile),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(wheel * .34f).clip(CircleShape).background(m.bg))
            // Four dots on a circle of radius .72 of the half-size.
            for (i in 0 until 4) {
                val cx = .72f * kotlin.math.cos(i * Math.PI / 2).toFloat()
                val cy = .72f * kotlin.math.sin(i * Math.PI / 2).toFloat()
                val half = wheel / 2
                Box(
                    Modifier.offset(half * cx, half * cy).size(4.dp).clip(CircleShape)
                        .background(if (i == 3) m.accent else m.sub),
                )
            }
        }
    }
}
