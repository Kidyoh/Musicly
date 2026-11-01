package com.musicly.musicly.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.Track
import com.musicly.musicly.player.LoopMode
import com.musicly.musicly.player.LyricsStatus
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

private val EaseOutCubicM = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private val Tabular = "tnum"

/**
 * Now Playing in the Minimal style: a record-like disc ringed by dots (drag
 * the ring to seek), and a click-wheel with round buttons for everything else.
 */
@Composable
fun MinimalNowPlaying(startOnLyrics: Boolean = false) {
    val env = LocalEnv.current
    val c = env.player
    val lib = env.library.observe()
    val m = miniPalette()
    val t = c.current
    var lyricsOn by remember { mutableStateOf(startOnLyrics) }
    var spin by remember { mutableFloatStateOf(0f) } // degrees
    var drag by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(t == null) { if (t == null) env.nowPlayingOpen = false }
    val playing = c.isPlaying
    LaunchedEffect(playing) {
        if (playing) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    spin = (spin + (now - last) / 1_000_000f / 28000f * 360f) % 360f
                    last = now
                }
            }
        }
    }
    if (t == null) {
        Box(Modifier.fillMaxSize().background(m.bg))
        return
    }
    val liked = lib.isFavorite(t)

    Column(Modifier.fillMaxSize().background(m.bg).statusBarsPadding().navigationBarsPadding()) {
        // Header: a big quiet title and a way back.
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, top = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (t.isRadio) "Radio" else "Music",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    fontFamily = Inter, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp, color = m.ink,
                ),
            )
            AppIconButton(AppIcons.down, onClick = { env.nowPlayingOpen = false }, tint = m.ink)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val size = min(maxHeight.value - 40f, maxWidth.value - 36f).dp
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    lyricsOn,
                    transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) },
                    label = "minLyrics",
                ) { showLyrics ->
                    if (showLyrics) {
                        Box(Modifier.fillMaxSize().padding(start = 22.dp, top = 10.dp, end = 22.dp, bottom = 6.dp)) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .miniSoft(m, 34.dp)
                                    .background(m.panel, RoundedCornerShape(34.dp))
                                    .clip(RoundedCornerShape(34.dp))
                                    .padding(horizontal = 18.dp),
                            ) { LyricsView() }
                        }
                    } else {
                        Disc(t, m, max(size.value, 150f).dp, spin, drag) { drag = it }
                    }
                }
            }
        }
        // Title and artist.
        Column(
            Modifier.fillMaxWidth().padding(start = 32.dp, top = 6.dp, end = 32.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(t.id, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "minTitle") { _ ->
                Text(
                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = Inter, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-.8).sp, color = m.ink,
                    ),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (t.isRadio) (c.nowOnAir ?: t.artist) else t.artist,
                modifier = if (t.isRadio) Modifier else Modifier.tap { openArtist(env, t.artist) },
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = m.sub),
            )
        }
        Console(t, m, liked, lyricsOn) { lyricsOn = !lyricsOn }
        Spacer(Modifier.height(14.dp))
    }
}

private fun fracAt(x: Float, y: Float, size: Float): Float {
    var a = atan2(y - size / 2, x - size / 2) + PI.toFloat() / 2
    if (a < 0) a += 2 * PI.toFloat()
    return a / (2 * PI.toFloat())
}

@Composable
private fun Disc(t: Track, m: MiniPalette, size: Dp, spin: Float, drag: Float?, setDrag: (Float?) -> Unit) {
    val env = LocalEnv.current
    val c = env.player
    val haptic = LocalHapticFeedback.current
    val pos by rememberPlayerPositionMs(c)
    val total = if (c.durationMs > 0) c.durationMs else (t.duration ?: 0L)
    val live = t.isRadio
    val frac = drag ?: if (live || total == 0L) 0f else (pos.toFloat() / total).coerceIn(0f, 1f)
    val shown = if (drag == null) pos else (total * drag).toLong()
    val art = size * .62f
    // Latest values for the gesture callbacks (they are installed once).
    val curDrag by androidx.compose.runtime.rememberUpdatedState(drag)
    val curTotal by androidx.compose.runtime.rememberUpdatedState(total)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .pointerInput(live) {
                    detectTapGestures(onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        c.togglePlay()
                    })
                }
                .pointerInput(live) {
                    fun move(o: androidx.compose.ui.geometry.Offset) {
                        if (live || curTotal == 0L) return
                        val f = fracAt(o.x, o.y, this.size.width.toFloat())
                        val d = curDrag
                        // Ignore a jump across the top of the ring.
                        if (d != null && kotlin.math.abs(f - d) > .5f) return
                        setDrag(f)
                    }
                    detectDragGestures(
                        onDragStart = { move(it) },
                        onDrag = { change, _ -> move(change.position) },
                        onDragEnd = {
                            val d = curDrag
                            if (d != null && curTotal > 0) c.seek((curTotal * d).toLong())
                            setDrag(null)
                        },
                        onDragCancel = { setDrag(null) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size)) {
                drawDotRing(frac, on = m.ink, off = m.line, marker = m.ink, showMarker = !live)
            }
            // The raised plate the record sits on.
            Box(
                Modifier
                    .size(size * .84f)
                    .miniRaised(m)
                    .background(
                        Brush.linearGradient(
                            if (m.dark) listOf(Color(0xFF26272C), Color(0xFF16171A))
                            else listOf(Color.White, Color(0xFFE3E5E9)),
                        ),
                        CircleShape,
                    ),
            )
            // The cover, turning slowly while the music plays.
            Box(
                Modifier
                    .graphicsLayer { rotationZ = spin }
                    .size(art)
                    .blurShadow(Color.Black.copy(alpha = if (m.dark) .5f else .16f), 18.dp, 0.dp, 8.dp, 1000.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (t.isRadio) StationArt(t, size = art - 10.dp, radius = art) else Artwork(t, size = art - 10.dp, radius = art)
                Box(Modifier.size(art).border(5.dp, m.panel, CircleShape))
            }
            // Spindle hole.
            Box(
                Modifier
                    .size(size * .1f)
                    .background(m.bg, CircleShape)
                    .border(3.dp, m.panel, CircleShape),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(size * .86f)) {
            if (live) {
                Text(
                    "● LIVE", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = Inter, color = m.accent, fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp, letterSpacing = 2.sp,
                    ),
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        fmt(shown),
                        style = TextStyle(
                            fontFamily = Inter, color = m.ink, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular,
                        ),
                    )
                    Text(
                        fmt(if (total == 0L) null else total),
                        style = TextStyle(
                            fontFamily = Inter, color = m.sub, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular,
                        ),
                    )
                }
            }
        }
    }
}

// ---- The control panel ------------------------------------------------------

@Composable
private fun Console(t: Track, m: MiniPalette, liked: Boolean, lyricsOn: Boolean, toggleLyrics: () -> Unit) {
    val env = LocalEnv.current
    val c = env.player
    val l = env.lyrics
    val synced = l.status == LyricsStatus.found && (l.lyrics?.isSynced ?: false)
    val speed = if (c.speed == Math.round(c.speed).toFloat()) c.speed.toInt().toString() else c.speed.toString()
    val soundOn = c.eqEnabled || c.loudnessOn

    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .miniSoft(m, 40.dp)
            .background(m.panel, RoundedCornerShape(40.dp))
            .padding(18.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth.value
            val wheel = (w * .6f).coerceIn(150f, 208f)
            val tile = ((w - wheel - 12f) / 2f).coerceIn(38f, 54f)
            Row(Modifier.fillMaxWidth().height(wheel.dp)) {
                Column(Modifier.width((tile * 2 + 8).dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MiniTile(m, { showSpeed(env) }, "Speed", label = "x$speed", active = c.speed != 1.0f, size = tile.dp)
                        MiniTile(
                            m, { showSound(env) }, "Sound",
                            icon = if (soundOn) AppIcons.soundOn else AppIcons.sound, active = soundOn, size = tile.dp,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MiniTile(
                            m, toggleLyrics, if (synced) "Lyrics (synced)" else "Lyrics",
                            icon = if (lyricsOn) AppIcons.lyricsOn else AppIcons.lyrics, active = lyricsOn, size = tile.dp,
                        )
                        MiniTile(
                            m, { showSleepTimer(env) }, "Sleep timer",
                            icon = if (c.sleepActive) AppIcons.sleepOn else AppIcons.sleep, active = c.sleepActive, size = tile.dp,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MiniTile(m, { c.toggleShuffle() }, "Shuffle", icon = AppIcons.shuffle, active = c.shuffle, size = tile.dp)
                        MiniTile(
                            m, { c.cycleLoop() }, "Repeat",
                            icon = repeatIcon(c.loopMode), active = c.loopMode != LoopMode.off, size = tile.dp,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Wheel(t, m, liked, wheel.dp)
            }
        }
    }
}

/**
 * The click-wheel: queue on top, previous and next at the sides, play and
 * pause at the bottom, and a heart in the middle.
 */
@Composable
private fun Wheel(t: Track, m: MiniPalette, liked: Boolean, size: Dp) {
    val env = LocalEnv.current
    val c = env.player
    val lib = env.library
    val haptic = LocalHapticFeedback.current
    val r = .74f

    Box(Modifier.size(size).miniSoft(m).background(m.tile, CircleShape)) {
        WheelKey(size, m, 0f, -r, AppIcons.queue) { showQueue(env) }
        WheelKey(size, m, -r, 0f, AppIcons.prev) { c.previous() }
        WheelKey(size, m, r, 0f, AppIcons.next) { c.next() }
        WheelKey(size, m, 0f, r, if (c.isPlaying) AppIcons.pause else AppIcons.play) { c.togglePlay() }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(size * .34f)
                .background(m.bg, CircleShape)
                .border(4.dp, m.panel, CircleShape)
                .tap {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    lib.toggleFavorite(t)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (liked) AppIcons.heartOn else AppIcons.heart,
                size = size * .13f, tint = if (liked) m.accent else m.sub,
            )
        }
    }
}

@Composable
private fun BoxScope.WheelKey(size: Dp, m: MiniPalette, bx: Float, by: Float, icon: Ph, onTap: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .align(BiasAlignment(bx, by))
            .size(size * .26f)
            .tap {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onTap()
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, size = size * .12f, tint = m.ink) }
}

/**
 * The mini player in the Minimal style: a light pill with a round cover, and
 * a thin progress ring around the play button.
 */
@Composable
fun MinimalMiniPlayer(onOpen: () -> Unit) {
    val env = LocalEnv.current
    val c = env.player
    val m = miniPalette()
    val t = c.current
    val density = LocalDensity.current.density
    Box(Modifier.fillMaxWidth().animateContentSize(tween(300, easing = EaseOutCubicM))) {
        if (t == null) return@Box
        val pos by rememberPlayerPositionMs(c, 200)
        val total = c.durationMs
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                .miniSoft(m, 34.dp)
                .background(m.panel, RoundedCornerShape(34.dp))
                .clip(RoundedCornerShape(34.dp))
                .tap(onOpen)
                .draggable(rememberDraggableState { }, Orientation.Vertical, onDragStopped = { v ->
                    if (v < -300 * density) onOpen()
                })
                .draggable(rememberDraggableState { }, Orientation.Horizontal, onDragStopped = { v ->
                    if (v < -300 * density) c.next()
                    if (v > 300 * density) c.previous()
                })
                .padding(start = 10.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (t.isRadio) StationArt(t, size = 48.dp, radius = 24.dp) else Artwork(t, size = 48.dp, radius = 24.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = Inter, color = m.ink, fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp, letterSpacing = (-.2).sp,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (t.isRadio) "● LIVE  ${c.nowOnAir ?: t.artist}" else t.artist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Inter, color = m.sub, fontSize = 12.5.sp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(48.dp).tap { c.togglePlay() }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (total <= 0) 0f else (pos.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.size(48.dp),
                    color = m.accent,
                    trackColor = m.line,
                    strokeWidth = 2.5.dp,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                )
                Box(
                    Modifier.size(38.dp).miniSoft(m).background(m.tile, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (c.isPlaying) AppIcons.pause else AppIcons.play, size = 17.dp, tint = m.ink) }
            }
        }
    }
}
