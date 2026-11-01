@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicly.musicly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.Library
import com.musicly.musicly.data.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalTime

private fun greeting(): String {
    val h = LocalTime.now().hour
    return when {
        h < 5 -> "Up late?"
        h < 12 -> "Good morning"
        h < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}

@Composable
fun HomeScreen() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val c = env.player
    val p = palette()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val songs = lib.allSongs
    val most = lib.mostPlayed.take(5)
    val recent = lib.recent.toList()
    val stations = lib.stations
    val artists = lib.artists
    val channels = lib.channels.toList()
    val local = lib.localTracks

    @Composable
    fun SongRows(list: List<Track>, max: Int = 5) {
        Column {
            for (i in 0 until list.size.coerceAtMost(max)) {
                ArtTrackRow(track = list[i], onTap = { c.playQueue(list, i) })
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    coroutineScope {
                        val a = async { runCatching { lib.loadHome() } }
                        val b = async { runCatching { lib.syncTelegram() } }
                        a.await(); b.await()
                    }
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), color = p.sub, fontSize = 14.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("Musicly", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, color = p.ink)
                    }
                    if (env.jam.supported) {
                        AppIconButton(
                            if (env.jam.active) AppIcons.jamOn else AppIcons.jam,
                            onClick = { openPage(env, "jam") { JamScreen() } },
                        )
                    }
                    AppIconButton(AppIcons.sound, onClick = { showSound(env) })
                    AppIconButton(if (p.dark) AppIcons.sun else AppIcons.moon, onClick = { lib.toggleTheme() })
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
            item { JamBanner() }
            item { ForYouCard(lib) }
            if (songs.isEmpty()) item { GetStarted() }
            if (recent.isNotEmpty()) {
                item { SectionHeader("Jump back in") }
                item {
                    val list = recent
                    HRow(height = 196.dp) {
                        items(list.size.coerceAtMost(12)) { i ->
                            val t = list[i]
                            CoverCard(
                                title = t.title, subtitle = t.artist,
                                art = {
                                    if (t.isRadio) StationArt(t, size = 148.dp, radius = 16.dp)
                                    else Artwork(t, size = 148.dp, radius = 16.dp)
                                },
                                onTap = { c.playQueue(list, i) },
                            )
                        }
                    }
                }
            }
            for (ch in channels) {
                if (ch.tracks.isEmpty()) continue
                val tracks = ch.tracks.toList()
                item {
                    SectionHeader(
                        ch.name, subtitle = "New in your Telegram channel", action = "See all",
                        onAction = { openPage(env, "telegram:${ch.id}") { TelegramScreen(channelId = ch.id) } },
                    )
                }
                item { SongRows(tracks) }
            }
            if (stations.isNotEmpty()) {
                item {
                    SectionHeader(
                        "Live radio", subtitle = "Real stations, streaming now", action = "See all",
                        onAction = { openPage(env, "radio") { RadioScreen() } },
                    )
                }
                item {
                    HRow(height = 178.dp) {
                        items(stations.size) { i ->
                            StationCard(station = stations[i], onTap = { c.playQueue(stations, i) })
                        }
                    }
                }
            }
            if (artists.isNotEmpty()) {
                item { SectionHeader("Your artists") }
                item {
                    val list = artists.take(15)
                    HRow(height = 148.dp) {
                        items(list.size) { i ->
                            val (name, tracks) = list[i]
                            ArtistBubble(
                                name = name,
                                cover = tracks.firstOrNull { it.artworkUrl != null || it.mediaId != null } ?: tracks.first(),
                                subtitle = count(tracks.size, "song"),
                                onTap = { openArtist(env, name) },
                            )
                        }
                    }
                }
            }
            if (most.isNotEmpty()) {
                item {
                    SectionHeader("On repeat", subtitle = "Your most played", action = "See all", onAction = { ShellTabs.open(3) })
                }
                item { SongRows(most) }
            }
            if (local.isNotEmpty()) {
                item {
                    SectionHeader(
                        "On this phone", action = "See all",
                        onAction = { openLive(env, "On this phone", "Your music", { l -> l.localTracks }, removableFiles = true) },
                    )
                }
                item { SongRows(local) }
            }
        }
    }
}

/** Shown until there's music: connect Telegram or add phone songs. */
@Composable
private fun GetStarted() {
    val env = LocalEnv.current
    val p = palette()
    Box(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(20.dp),
        ) {
            Text("Bring your music", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect your Telegram channel or add the songs on this phone. " +
                    "Your mix, artists and hotlist are built from them.",
                color = p.sub, lineHeight = 24.sp, fontSize = 16.sp,
            )
            Spacer(Modifier.height(14.dp))
            PillButton(
                icon = AppIcons.telegram, label = "Connect Telegram",
                onClick = { openPage(env, "telegram") { TelegramScreen() } },
            )
        }
    }
}

/** Big dark card for the personalised mix. */
@Composable
private fun ForYouCard(lib: Library) {
    val env = LocalEnv.current
    val c = env.player
    val p = palette()
    val mix = lib.forYou
    val covers = mutableListOf<Track>()
    val seen = mutableSetOf<String>()
    for (t in mix) {
        val key = t.artworkUrl ?: t.mediaId?.let { "ms:$it" }
        if (key != null && seen.add(key)) covers.add(t)
        if (covers.size == 3) break
    }
    Box(Modifier.padding(horizontal = 20.dp)) {
        Box(
            Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1C1D22))
                .clickable(enabled = mix.isNotEmpty()) {
                    openPage(env, "mix") {
                        CollectionScreen(
                            title = "Your mix", owner = lib.forYouReason ?: "Musicly", kind = "Made for you",
                            cover = covers.firstOrNull(), live = { l -> l.forYou },
                        )
                    }
                },
        ) {
            // Fanned covers on the right.
            for (i in covers.indices.reversed()) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = -(18 + i * 34).dp, y = (26 + i * 8).dp)
                        .graphicsLayer {
                            rotationZ = Math.toDegrees(((i - 1) * 0.08).toDouble()).toFloat()
                            alpha = 1f - i * 0.22f
                        },
                ) {
                    Artwork(covers[i], size = (112 - i * 10).dp, radius = 14.dp, shadow = true)
                }
            }
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.sparkle, size = 16.dp, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "MADE FOR YOU", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Your mix", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    lib.forYouReason ?: "Connect Telegram or add phone music to get your mix",
                    modifier = Modifier.width(170.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .clickable(enabled = mix.isNotEmpty()) { c.playShuffled(mix) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.play, size = 14.dp, tint = p.dock)
                    Spacer(Modifier.width(6.dp))
                    Text("Play mix", color = p.dock, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Shown on Home while you're in a Jam, so it's one tap away. */
@Composable
private fun JamBanner() {
    val env = LocalEnv.current
    val jam = env.jam
    if (!jam.active) return
    val p = palette()
    val people = if (jam.role == com.musicly.musicly.jam.JamRole.host) jam.guests.size + 1 else jam.state.people.size
    Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card)
                .clickable { openPage(env, "jam") { JamScreen() } }
                .padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(p.ink), contentAlignment = Alignment.Center) {
                Icon(AppIcons.jamOn, size = 20.dp, tint = p.onInk)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(jam.jamName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.ink, maxLines = 1)
                Text(
                    "${if (jam.role == com.musicly.musicly.jam.JamRole.host) "You're hosting" else "You're in"} \u00B7 ${count(people, "person", "people")}",
                    fontSize = 14.sp, color = p.sub,
                )
            }
            Icon(AppIcons.chevron, size = 18.dp, tint = p.ink)
        }
    }
}
