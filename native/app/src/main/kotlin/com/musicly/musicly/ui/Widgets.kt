@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// -- formatting -------------------------------------------------------------

/** "3:07", or "--:--" when unknown. [ms] is milliseconds. */
fun fmt(ms: Long?): String {
    if (ms == null) return "--:--"
    val m = ms / 60000
    val s = ((ms / 1000) % 60).toString().padStart(2, '0')
    return "$m:$s"
}

/** "1 song", "3 songs". */
fun count(n: Int, word: String, plural: String? = null): String =
    "$n ${if (n == 1) word else plural ?: "${word}s"}"

fun compact(n: Int): String {
    if (n >= 1000000) return String.format(Locale.US, "%.${if (n >= 10000000) 0 else 1}fM", n / 1000000.0)
    if (n >= 1000) return String.format(Locale.US, "%.${if (n >= 10000) 0 else 1}fK", n / 1000.0)
    return "$n"
}

private val LiveRed = Color(0xFFE5484D)

// -- small building blocks --------------------------------------------------

/** Flutter-style IconButton: 48dp tap target, [icon] drawn at [iconSize]. */
@Composable
fun AppIconButton(
    icon: Ph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = palette().ink,
    iconSize: Dp = 24.dp,
    size: Dp = 48.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, size = iconSize, tint = tint) }
}

/** Click without a ripple (Flutter GestureDetector). */
fun Modifier.tap(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

// -- Artwork ------------------------------------------------------------------

@Composable
fun Artwork(
    track: Track?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    radius: Dp = 10.dp,
    shadow: Boolean = false,
    icon: Ph? = null,
) {
    val p = palette()
    val shape = RoundedCornerShape(radius)
    val bmp = rememberTrackArt(track)
    val base = modifier.size(size).let {
        if (shadow) it.shadow(18.dp, shape, ambientColor = Color.Transparent, spotColor = Color.Black.copy(alpha = 0.45f)) else it
    }
    Box(base.clip(shape)) {
        if (bmp != null) {
            androidx.compose.foundation.Image(bmp, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.size(size).background(
                    Brush.linearGradient(
                        if (p.dark) listOf(p.line, p.card) else listOf(Color(0xFF3A3B42), p.ink),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon ?: AppIcons.music,
                    size = size * 0.38f,
                    tint = if (p.dark) p.sub else Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** 2x2 grid of covers for playlists the user made. */
@Composable
fun Mosaic(tracks: List<Track>, modifier: Modifier = Modifier, size: Dp = 56.dp, radius: Dp = 12.dp) {
    val seen = HashSet<String>()
    val unique = tracks.filter { it.artworkUrl != null || it.mediaId != null }
        .filter { seen.add(it.artworkUrl ?: "${it.mediaId}") }
        .take(4)
    if (unique.size < 4) {
        Artwork(unique.firstOrNull(), modifier, size = size, radius = radius, icon = AppIcons.playNext)
        return
    }
    val half = size / 2
    Column(modifier.size(size).clip(RoundedCornerShape(radius))) {
        Row {
            Artwork(unique[0], size = half, radius = 0.dp)
            Artwork(unique[1], size = half, radius = 0.dp)
        }
        Row {
            Artwork(unique[2], size = half, radius = 0.dp)
            Artwork(unique[3], size = half, radius = 0.dp)
        }
    }
}

/** Round artist picture: the cover of one of their songs. */
@Composable
fun ArtistAvatar(cover: Track?, modifier: Modifier = Modifier, size: Dp = 96.dp) =
    Artwork(cover, modifier, size = size, radius = size / 2, icon = AppIcons.artist)

// -- Equalizer bars -------------------------------------------------------------

/** Four bouncing bars shown next to the song that's playing. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color? = null, size: Dp = 16.dp) {
    val c = color ?: palette().ink
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            val start = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                androidx.compose.runtime.withFrameNanos { now -> t = (((now - start) / 1_000_000L) % 900L) / 900f }
            }
        }
    }
    Canvas(modifier.size(size)) {
        val w = this.size.width / 6
        val gap = (this.size.width - 4 * w) / 3
        for (i in 0 until 4) {
            val phase = t * 2 * Math.PI + i * 1.7
            val h = if (playing) 0.3 + 0.7 * abs(0.5 + 0.5 * sin(phase)) else 0.3
            val bh = (this.size.height * h).toFloat()
            drawRoundRect(
                c,
                topLeft = Offset(i * (w + gap), this.size.height - bh),
                size = Size(w, bh),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

// -- Waveform seek bar ----------------------------------------------------------

private fun waveBars(id: String): List<Double> {
    val r = java.util.Random(id.hashCode().toLong())
    val n = 72
    val raw = List(n) { i ->
        val env = 0.45 + 0.55 * sin(Math.PI * i / n)
        (0.15 + r.nextDouble() * 0.85) * env
    }
    return List(n) { i ->
        val a = raw[max(0, i - 1)]; val b = raw[i]; val c = raw[min(n - 1, i + 1)]
        (a + 2 * b + c) / 4
    }
}

/** Waveform scrubber. Bars are generated from the song id so each song has its own shape. */
@Composable
fun WaveformSeekBar(trackId: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val env = LocalEnv.current
    val c = env.player
    val p = palette()
    val bars = remember(trackId) { waveBars(trackId) }
    var pos by remember { mutableLongStateOf(c.positionMs) }
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            pos = c.positionMs
            delay(100)
        }
    }
    val total = c.durationMs
    val frac = drag ?: if (total <= 0) 0f else (pos.toFloat() / total).coerceIn(0f, 1f)
    val active = p.ink
    val inactive = p.line
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(44.dp).pointerInput(total) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val wpx = size.width.toFloat().coerceAtLeast(1f)
                    drag = (down.position.x / wpx).coerceIn(0f, 1f)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) {
                            ch.consume()
                            break
                        }
                        drag = (ch.position.x / wpx).coerceIn(0f, 1f)
                        ch.consume()
                    }
                    val d = drag
                    if (d != null && total > 0) {
                        val ms = (total * d).toLong()
                        c.seek(ms)
                        pos = ms
                    }
                    drag = null
                }
            },
        ) {
            val step = size.width / bars.size
            val w = max(1.5.dp.toPx(), step * 0.45f)
            val mid = size.height / 2
            for (i in bars.indices) {
                val x = step * i + step / 2
                val h = max(w, (bars[i] * size.height).toFloat())
                val col = if ((i + 0.5f) / bars.size <= frac) active else inactive
                drawLine(col, Offset(x, mid - h / 2), Offset(x, mid + h / 2), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                fmt(if (drag != null) (total * drag!!).toLong() else pos),
                fontSize = 12.sp, color = p.ink, fontWeight = FontWeight.Medium,
            )
            Text(fmt(total), fontSize = 12.sp, color = p.sub)
        }
    }
}

// -- Track rows ---------------------------------------------------------------------

/** Row used in collection lists: "01  Title / Artist - 4:21  more". */
@Composable
fun NumberedTrackRow(
    index: Int,
    track: Track,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    removeLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalEnv.current.player
    val p = palette()
    val isCurrent = c.current?.id == track.id
    val bg by animateColorAsState(if (isCurrent) p.card else Color.Transparent, tween(250), label = "row")
    Box(modifier.clickable(onClick = onTap)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) EqualizerBars(playing = c.isPlaying)
                else Text((index + 1).toString().padStart(2, '0'), fontWeight = FontWeight.SemiBold, color = p.ink, fontSize = 14.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.ink)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (track.duration == null || track.duration == 0L) track.artist else "${track.artist}  •  ${fmt(track.duration)}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.sub, fontSize = 13.sp,
                )
            }
            trailing?.invoke()
            MoreButton(track, onRemove = onRemove, removeLabel = removeLabel)
        }
    }
}

/** Row with artwork, used in search, home and the charts. */
@Composable
fun ArtTrackRow(
    track: Track,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LocalEnv.current.player
    val p = palette()
    val isCurrent = c.current?.id == track.id
    Row(
        modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Box(contentAlignment = Alignment.Center) {
            if (track.isRadio) StationArt(track, size = 52.dp, radius = 12.dp) else Artwork(track, size = 52.dp, radius = 12.dp)
            if (isCurrent) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { EqualizerBars(playing = c.isPlaying, color = Color.White) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.ink)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.sub, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                SourceBadge(track)
            }
        }
        MoreButton(track)
    }
}

@Composable
fun MoreButton(
    track: Track,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    removeLabel: String? = null,
) {
    val env = LocalEnv.current
    AppIconButton(AppIcons.more, { showTrackOptions(env, track, onRemove, removeLabel) }, modifier, tint = palette().sub, iconSize = 20.dp)
}

// -- Section header, empty state, buttons ----------------------------------------------

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val p = palette()
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, top = 28.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle, color = p.sub, fontSize = 13.sp)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = p.ink)
        }
        if (action != null) {
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(20.dp)).clickable(enabled = onAction != null) { onAction?.invoke() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(action, color = p.sub, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
}

@Composable
fun EmptyState(
    icon: Ph,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val p = palette()
    BoxWithConstraints(modifier) {
        val m = if (constraints.hasBoundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        Box(m, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(p.card), contentAlignment = Alignment.Center) {
                    Icon(icon, size = 30.dp, tint = p.sub)
                }
                Spacer(Modifier.height(16.dp))
                Text(text, textAlign = TextAlign.Center, color = p.sub, fontSize = 14.sp, lineHeight = 21.sp)
                if (action != null) {
                    Spacer(Modifier.height(20.dp))
                    action()
                }
            }
        }
    }
}

/** Dark filled ("Play") or soft grey ("Shuffle") button from the design. */
@Composable
fun PillButton(
    icon: Ph,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val p = palette()
    val bg = if (filled) p.ink else p.card
    val fg = if (filled) p.onInk else p.ink
    val enabled = onClick != null
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) bg else bg.copy(alpha = 0.12f))
            .clickable(enabled = enabled) { onClick?.invoke() }.padding(start = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, size = 20.dp, tint = if (enabled) fg else fg.copy(alpha = 0.38f))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (enabled) fg else fg.copy(alpha = 0.38f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }
}

// -- Cards ---------------------------------------------------------------------------------

/** Square cover with title and subtitle, for albums and playlists. */
@Composable
fun CoverCard(
    title: String,
    subtitle: String,
    art: @Composable () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp,
) {
    val p = palette()
    Column(modifier.width(width).tap(onTap)) {
        art()
        Spacer(Modifier.height(10.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.ink)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.sub, fontSize = 12.sp)
    }
}

/** Round artist picture with name (and song count) underneath. */
@Composable
fun ArtistBubble(
    name: String,
    cover: Track?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    size: Dp = 96.dp,
) {
    val p = palette()
    Column(modifier.width(size).tap(onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtistAvatar(cover, size = size)
        Spacer(Modifier.height(8.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = p.ink, modifier = Modifier.fillMaxWidth())
        if (subtitle != null) {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.sub, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Horizontal scrolling row; fill it with `items(...)` / `item { }`. */
@Composable
fun HRow(
    height: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = 14.dp,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier.fillMaxWidth().height(height),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Same as the Dart `HRow(height:, children:)`. */
@Composable
fun HRow(
    height: Dp,
    children: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    spacing: Dp = 14.dp,
) = HRow(height, modifier, spacing) { items(children.size) { children[it]() } }

/** Small tag after the artist: LIVE for radio, a tick for channel songs saved on the phone. */
@Composable
fun SourceBadge(track: Track, modifier: Modifier = Modifier) {
    val env = LocalEnv.current
    if (track.source == TrackSource.telegram) {
        val saved = env.library.observe().isDownloaded(track)
        if (saved) Icon(AppIcons.saved, modifier, size = 13.dp, tint = palette().sub)
        return
    }
    if (!track.isRadio) return
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(LiveRed).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text("LIVE", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, color = Color.White)
    }
}

/**
 * Radio logos are usually small favicons, so they sit centred on a soft card
 * instead of being stretched edge to edge.
 */
@Composable
fun StationArt(station: Track, modifier: Modifier = Modifier, size: Dp = 120.dp, radius: Dp = 18.dp) {
    val p = palette()
    val icon: @Composable () -> Unit = { Icon(AppIcons.radio, size = size * 0.36f, tint = p.sub) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius)).background(p.card).padding(size * 0.18f),
        contentAlignment = Alignment.Center,
    ) {
        if (station.artworkUrl == null) icon()
        else Box(Modifier.clip(RoundedCornerShape(radius * 0.4f)), contentAlignment = Alignment.Center) {
            RemoteImage(station.artworkUrl, contentScale = ContentScale.Fit, placeholder = { Box(contentAlignment = Alignment.Center) { icon() } })
        }
    }
}

/** Square station tile with a LIVE tag, for horizontal rows and grids. */
@Composable
fun StationCard(station: Track, onTap: () -> Unit, modifier: Modifier = Modifier, width: Dp = 132.dp) {
    val c = LocalEnv.current.player
    val p = palette()
    val playing = c.current?.id == station.id
    Column(modifier.width(width).tap(onTap)) {
        Box {
            StationArt(station, size = width)
            SourceBadge(station, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp))
            if (playing) {
                EqualizerBars(c.isPlaying, Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp), size = 14.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(station.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.ink)
        Text(station.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.sub, fontSize = 12.sp)
    }
}

/** Replaces the scrubber on live radio: a pulsing dot and what's on air. */
@Composable
fun LiveBar(modifier: Modifier = Modifier, onAir: String? = null) {
    val p = palette()
    val a by rememberInfiniteTransition(label = "live").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "dot",
    )
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(p.card).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).alpha(a).clip(CircleShape).background(LiveRed))
        Spacer(Modifier.width(10.dp))
        Text("LIVE", fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = p.ink, fontSize = 14.sp)
        Spacer(Modifier.width(14.dp))
        Text(
            if (onAir == null) "Streaming now" else "On air: $onAir",
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = p.sub, fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
    }
}
