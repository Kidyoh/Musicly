@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.Track
import com.musicly.musicly.net.RadioApi
import kotlinx.coroutines.delay

/** Searches your own music (channel + phone), artists, playlists and radio. */
@Composable
fun SearchScreen() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val c = env.player
    val p = palette()
    var raw by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(raw) {
        delay(450)
        val q = raw.trim()
        if (q.isEmpty()) return@LaunchedEffect
        found = try {
            lib.radio.search(q, limit = 12)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    val q = raw.trim().lowercase()
    fun hit(t: Track) = t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) ||
        (t.album?.lowercase()?.contains(q) ?: false)
    val songs = if (q.isEmpty()) emptyList() else lib.allSongs.filter { hit(it) }
    val artists = if (q.isEmpty()) emptyList() else lib.artists.filter { it.first.lowercase().contains(q) }
    val playlists = if (q.isEmpty()) emptyList() else lib.playlists.filter { it.name.lowercase().contains(q) }
    val stations = if (q.isEmpty()) emptyList() else found
    val nothing = songs.isEmpty() && artists.isEmpty() && playlists.isEmpty() && stations.isEmpty()

    Column(Modifier.fillMaxSize()) {
        Text(
            "Search",
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 12.dp),
            fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, color = p.ink,
        )
        Box(Modifier.padding(horizontal = 20.dp)) {
            AppTextField(
                value = raw, onValueChange = { raw = it }, hint = "Your songs, artists, playlists, radio",
                leading = { Icon(AppIcons.search, size = 20.dp, tint = p.sub) },
                trailing = if (raw.isEmpty()) null else ({
                    Box(Modifier.size(24.dp).clickable { raw = "" }, contentAlignment = Alignment.Center) {
                        Icon(AppIcons.close, size = 18.dp, tint = p.sub)
                    }
                }),
                imeAction = ImeAction.Search,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                q.isEmpty() -> Browse()
                nothing -> EmptyState(AppIcons.search, "Nothing matches \"$raw\"")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (artists.isNotEmpty()) {
                        item { SectionHeader("Artists") }
                        item {
                            HRow(height = 148.dp) {
                                items(artists.size) { i ->
                                    val (name, list) = artists[i]
                                    ArtistBubble(
                                        name = name,
                                        cover = list.firstOrNull { it.artworkUrl != null || it.mediaId != null } ?: list.first(),
                                        subtitle = count(list.size, "song"),
                                        onTap = { openArtist(env, name) },
                                    )
                                }
                            }
                        }
                    }
                    if (songs.isNotEmpty()) {
                        item { SectionHeader("Songs", subtitle = count(songs.size, "match")) }
                        items(songs.size.coerceAtMost(30)) { i ->
                            ArtTrackRow(track = songs[i], onTap = { c.playQueue(songs, i) })
                        }
                    }
                    if (playlists.isNotEmpty()) {
                        item { SectionHeader("Your playlists") }
                        items(playlists.size) { i ->
                            val pl = playlists[i]
                            Row(
                                Modifier.fillMaxWidth().clickable { openUserPlaylist(env, pl) }
                                    .heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Mosaic(pl.tracks, size = 52.dp)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pl.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = p.ink, maxLines = 1)
                                    Text(count(pl.tracks.size, "song"), color = p.sub, fontSize = 14.sp)
                                }
                                Icon(AppIcons.chevron, size = 18.dp, tint = p.sub)
                            }
                        }
                    }
                    if (stations.isNotEmpty()) {
                        item { SectionHeader("Radio stations") }
                        item {
                            HRow(height = 178.dp) {
                                items(stations.size) { i ->
                                    StationCard(station = stations[i], onTap = { c.playQueue(stations, i) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Before typing: live radio and radio genres. */
@Composable
private fun Browse() {
    val env = LocalEnv.current
    val p = palette()
    val shades = listOf(1.0f, 0.88f, 0.76f, 0.64f)
    val tags = RadioApi.tags
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)).background(p.ink)
                    .clickable { openPage(env, "radio") { RadioScreen() } }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.radio, size = 28.dp, tint = p.onInk)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live radio", color = p.onInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        "Full songs, streaming now from stations worldwide",
                        color = p.onInk.copy(alpha = 0.65f), fontSize = 12.sp,
                    )
                }
                Icon(AppIcons.chevron, size = 24.dp, tint = p.onInk)
            }
        }
        item {
            Text(
                "Radio by genre",
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 12.dp),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink,
            )
        }
        val rows = tags.chunked(2)
        items(rows.size) { r ->
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = if (r == rows.lastIndex) 24.dp else 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                for (k in 0 until 2) {
                    val i = r * 2 + k
                    if (i >= tags.size) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val (tag, label) = tags[i]
                    Box(
                        Modifier.weight(1f).aspectRatio(1.9f).clip(RoundedCornerShape(18.dp))
                            .background(p.ink.copy(alpha = shades[i % shades.size]))
                            .clickable { openPage(env, "radio:$tag") { RadioScreen(initialFilter = tag) } },
                    ) {
                        Box(Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 12.dp)) {
                            Icon(AppIcons.radio, size = 64.dp, tint = p.onInk.copy(alpha = 0.12f))
                        }
                        Text(
                            label, modifier = Modifier.padding(14.dp), color = p.onInk,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}
