package com.musicly.musicly.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.PlayerStyle
import com.musicly.musicly.player.LoopMode
import com.musicly.musicly.player.LyricsStatus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val EaseOutCubicN = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private val EaseOutN = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EaseOutBackN = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)
private val LikedRed = Color(0xFFE5484D)

/**
 * Full-screen player. Classic style here; Minimal is [MinimalNowPlaying].
 * Closing sets `env.nowPlayingOpen = false`.
 */
@Composable
fun NowPlayingScreen(startOnLyrics: Boolean = false) {
    val env = LocalEnv.current
    val c = env.player
    val lib = env.library.observe()
    val p = palette()
    val haptic = LocalHapticFeedback.current
    val t = c.current

    var lyricsOn by remember { mutableStateOf(startOnLyrics) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var accent by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(t == null) { if (t == null) env.nowPlayingOpen = false }
    LaunchedEffect(t?.id) {
        if (t != null) accent = try { artColor(env, t) } catch (_: Exception) { null }
    }
    if (t == null) {
        Box(Modifier.fillMaxSize().background(p.bg))
        return
    }
    if (lib.playerStyle == PlayerStyle.minimal) {
        MinimalNowPlaying(startOnLyrics)
        return
    }
    val liked = lib.isFavorite(t)
    val density = LocalDensity.current.density

    val top by animateColorAsState(
        lerp(p.bg, accent ?: p.bg, if (p.dark) 0.42f else 0.22f),
        tween(700, easing = EaseOutN), label = "glow",
    )

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.roundToInt()) }
            .background(Brush.verticalGradient(0f to top, 0.62f to p.bg, 1f to p.bg))
            .draggable(
                rememberDraggableState { d -> dragY = (dragY + d).coerceIn(0f, 400f * density) },
                Orientation.Vertical,
                onDragStopped = { v ->
                    if (dragY > 120f * density || v > 900f * density) env.nowPlayingOpen = false
                    else dragY = 0f
                },
            ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // App bar
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                Box(Modifier.align(Alignment.CenterStart).size(56.dp), contentAlignment = Alignment.Center) {
                    AppIconButton(AppIcons.down, onClick = { env.nowPlayingOpen = false }, tint = p.ink)
                }
                Column(
                    Modifier.align(Alignment.TopCenter).padding(start = 56.dp, end = 60.dp).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (lyricsOn) "Lyrics" else if (t.isRadio) "Live radio" else "Now Playing",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Inter, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.ink),
                    )
                    if (t.album != null) {
                        Text(
                            t.album, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = p.sub, fontWeight = FontWeight.Medium),
                        )
                    }
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                    AppIconButton(AppIcons.queue, onClick = { showQueue(env) }, tint = p.ink)
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                val art = (maxWidth.value - 110f).coerceIn(180f, 340f).dp
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(
                            lyricsOn,
                            transitionSpec = {
                                (fadeIn(tween(350, easing = EaseOutCubicN)) + scaleIn(tween(350, easing = EaseOutCubicN), 0.96f)) togetherWith
                                    (fadeOut(tween(350)) + scaleOut(tween(350), 0.96f))
                            },
                            label = "npSwitch",
                        ) { showLyrics ->
                            if (showLyrics) {
                                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) { LyricsView() }
                            } else {
                                val s by animateFloatAsState(if (c.isPlaying) 1f else 0.92f, tween(400, easing = EaseOutBackN), label = "artScale")
                                Box(
                                    Modifier.fillMaxSize().tap { lyricsOn = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier
                                            .graphicsLayer { scaleX = s; scaleY = s }
                                            .blurShadow(
                                                (accent ?: Color.Black).copy(alpha = if (p.dark) 0.45f else 0.35f),
                                                48.dp, 0.dp, 22.dp, 26.dp,
                                            ),
                                    ) {
                                        if (t.isRadio) StationArt(t, size = art, radius = 26.dp)
                                        else Artwork(t, size = art, radius = 26.dp)
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    lib.toggleFavorite(t)
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    liked,
                                    transitionSpec = { scaleIn(tween(250)) togetherWith scaleOut(tween(250)) },
                                    label = "heart",
                                ) { on ->
                                    Icon(if (on) AppIcons.heartOn else AppIcons.heart, tint = if (on) LikedRed else p.sub)
                                }
                            }
                            Text(
                                t.title,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    fontFamily = Inter, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.6).sp, color = p.ink,
                                ),
                            )
                            AppIconButton(AppIcons.more, onClick = { showTrackOptions(env, t) }, tint = p.sub)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Text(
                                if (t.isRadio) (c.nowOnAir ?: t.artist) else t.artist,
                                modifier = if (t.isRadio) Modifier.weight(1f, fill = false) else Modifier.weight(1f, fill = false).tap { openArtist(env, t.artist) },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Inter, color = p.sub, fontSize = 15.sp),
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        if (t.isRadio) LiveBar(onAir = c.nowOnAir) else WaveformSeekBar(t.id)
                        Spacer(Modifier.height(12.dp))
                        Controls()
                        Spacer(Modifier.height(16.dp))
                        ActionBar(lyricsOn) { lyricsOn = !lyricsOn }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls() {
    val env = LocalEnv.current
    val c = env.player
    val p = palette()
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcons.shuffle, onClick = { c.toggleShuffle() }, tint = if (c.shuffle) p.ink else p.sub)
        AppIconButton(AppIcons.prev, onClick = { c.previous() }, tint = p.ink, iconSize = 30.dp)
        val playing = c.isPlaying
        val busy = c.buffering
        Box(
            Modifier
                .size(70.dp)
                .blurShadow(p.ink.copy(alpha = 0.25f), 20.dp, 0.dp, 8.dp, 1000.dp)
                .background(p.ink, CircleShape)
                .clip(CircleShape)
                .tap {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    c.togglePlay()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(66.dp), color = p.onInk, strokeWidth = 2.dp,
                    trackColor = Color.Transparent,
                )
            }
            AnimatedContent(
                playing,
                transitionSpec = { scaleIn(tween(200)) togetherWith scaleOut(tween(200)) },
                label = "playpause",
            ) { on ->
                Icon(if (on) AppIcons.pause else AppIcons.play, tint = p.onInk, size = 28.dp)
            }
        }
        AppIconButton(AppIcons.next, onClick = { c.next() }, tint = p.ink, iconSize = 30.dp)
        AppIconButton(
            repeatIcon(c.loopMode), onClick = { c.cycleLoop() },
            tint = if (c.loopMode == LoopMode.off) p.sub else p.ink,
        )
    }
}

@Composable
private fun ActionBar(lyrics: Boolean, onLyrics: () -> Unit) {
    val env = LocalEnv.current
    val c = env.player
    val l = env.lyrics
    val p = palette()
    val synced = l.status == LyricsStatus.found && (l.lyrics?.isSynced ?: false)
    val soundOn = c.eqEnabled || c.loudnessOn

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.sleepEndsAt) {
        while (c.sleepEndsAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val ends = c.sleepEndsAt
    val sleepLabel = when {
        c.sleepAtTrackEnd -> "End of song"
        ends != null -> "${(ends - now) / 60000 + 1} min"
        else -> "Sleep"
    }

    @Composable
    fun RowScope.Item(icon: Ph, label: String, on: Boolean, onTap: () -> Unit) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onTap)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, size = 22.dp, tint = if (on) p.ink else p.sub)
            Spacer(Modifier.height(4.dp))
            Text(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = Inter, fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) p.ink else p.sub,
                ),
            )
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.card.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Item(if (lyrics) AppIcons.lyricsOn else AppIcons.lyrics, if (synced) "Lyrics • Live" else "Lyrics", lyrics, onLyrics)
        Item(if (soundOn) AppIcons.soundOn else AppIcons.sound, "Sound", soundOn) { showSound(env) }
        Item(if (c.sleepActive) AppIcons.sleepOn else AppIcons.sleep, sleepLabel, c.sleepActive) { showSleepTimer(env) }
        Item(AppIcons.speed, "${c.speed}x", c.speed != 1.0f) { showSpeed(env) }
    }
}
