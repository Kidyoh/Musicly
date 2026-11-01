package com.musicly.musicly.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.net.Lyrics
import com.musicly.musicly.player.LyricsStatus
import kotlinx.coroutines.delay
import java.util.Locale

private val EaseOutCubicL = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private val EaseOutL = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * Karaoke-style synced lyrics. The current line is highlighted and kept in
 * view; tapping a line seeks there. Scrolling by hand pauses the follow.
 */
@Composable
fun LyricsView(modifier: Modifier = Modifier) {
    val env = LocalEnv.current
    val l = env.lyrics
    val c = env.player
    val p = palette()

    Box(modifier.fillMaxSize()) {
        if (c.current?.isRadio == true) {
            EmptyState(
                AppIcons.radio,
                if (c.nowOnAir == null) "Lyrics aren't available on live radio."
                else "On air: ${c.nowOnAir}\nLyrics aren't available on live radio.",
            )
            return@Box
        }
        when (l.status) {
            LyricsStatus.loading, LyricsStatus.idle -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.ink)
                }
                return@Box
            }
            LyricsStatus.error -> {
                EmptyState(AppIcons.offline, "Could not load lyrics.", action = {
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, p.line, RoundedCornerShape(50))
                            .clickable { l.retry() }
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Try again", color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                })
                return@Box
            }
            LyricsStatus.notFound -> {
                EmptyState(AppIcons.lyrics, "No lyrics found for this song.", action = {
                    Row(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(50))
                            .background(p.ink)
                            .clickable { showLyricsSearch(env) }
                            .padding(start = 16.dp, end = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.search, size = 18.dp, tint = p.onInk)
                        Spacer(Modifier.width(8.dp))
                        Text("Search lyrics", color = p.onInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                })
                return@Box
            }
            LyricsStatus.found -> {}
        }
        val lyr = l.lyrics
        if (lyr == null) return@Box
        if (lyr.instrumental) {
            EmptyState(AppIcons.headphones, "Instrumental. Enjoy the music.")
            return@Box
        }
        LyricsBody(lyr)
    }
}

@Composable
private fun LyricsBody(lyr: Lyrics) {
    val env = LocalEnv.current
    val l = env.lyrics
    val c = env.player
    val p = palette()
    val live = lyr.isSynced // canSync is always true
    var line by remember { mutableIntStateOf(-1) }
    var userAt by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()

    // Follow the playhead.
    LaunchedEffect(lyr) {
        while (true) {
            val i = l.lineAt(c.positionMs)
            if (i != line) line = i
            delay(100)
        }
    }
    // Keep the current line in view (unless the user scrolled by hand lately).
    LaunchedEffect(line, live) {
        if (!live) return@LaunchedEffect
        if (System.currentTimeMillis() - userAt < 4000) return@LaunchedEffect
        val idx = (if (line < 0) 0 else line) + 1 // +1: top spacer item
        suspend fun align(): Boolean {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == idx } ?: return false
            val vh = info.viewportEndOffset - info.viewportStartOffset
            val target = 0.35f * (vh - item.size)
            val delta = item.offset - target
            if (delta != 0f) listState.animateScrollBy(delta, tween(450, easing = EaseOutCubicL))
            return true
        }
        if (!align()) {
            listState.animateScrollToItem(idx)
            align()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Toolbar
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (live) p.ink else p.card)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    if (live) "Synced" else "Not synced",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (live) p.onInk else p.sub,
                )
            }
            Spacer(Modifier.weight(1f))
            if (live) {
                Nudge(AppIcons.minus) { l.nudge(-500) }
                Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                    Text(
                        (if (l.offset < 0) "" else "+") + String.format(Locale.US, "%.1f", l.offset / 1000.0) + "s",
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontSize = 12.sp, color = p.sub, fontWeight = FontWeight.SemiBold),
                    )
                }
                Nudge(AppIcons.plus) { l.nudge(500) }
                Spacer(Modifier.width(4.dp))
            }
            AppIconButton(AppIcons.findLyrics, onClick = { showLyricsSearch(env) }, tint = p.ink)
        }

        val lines = if (lyr.isSynced) lyr.synced.map { it.text } else (lyr.plain ?: "").split('\n')

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            userAt = System.currentTimeMillis()
                        }
                    }
                }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.08f to Color.Black,
                            0.85f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "top") { Spacer(Modifier.height(60.dp)) }
                itemsIndexed(lines) { i, raw ->
                    val active = live && i == line
                    val passed = live && i < line
                    val text = raw.trim()
                    val size by animateFloatAsState(if (active) 26f else 22f, tween(300, easing = EaseOutL), label = "sz")
                    val target = when {
                        !live -> p.ink.copy(alpha = 0.85f)
                        active -> p.ink
                        passed -> p.sub.copy(alpha = 0.5f)
                        else -> p.sub.copy(alpha = 0.8f)
                    }
                    val color by animateColorAsState(target, tween(300, easing = EaseOutL), label = "col")
                    val inner = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                    val mod = if (!live) Modifier else Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val t = lyr.synced[i].time - l.offset
                            c.seek(if (t < 0) 0 else t)
                            userAt = 0L
                        }
                    Box(mod) {
                        Text(
                            if (text.isEmpty()) "♪" else text,
                            modifier = inner,
                            style = TextStyle(
                                fontFamily = Inter,
                                fontSize = size.sp,
                                lineHeight = (size * 1.3f).sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.4).sp,
                                color = color,
                            ),
                        )
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(60.dp)) }
            }
        }
    }
}

@Composable
private fun Nudge(icon: Ph, onTap: () -> Unit) {
    val p = palette()
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(p.card).clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, size = 14.dp, tint = p.ink) }
}
