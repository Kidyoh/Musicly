@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** An artist from your own music: their songs from the channel and phone. */
@Composable
fun ArtistScreen(name: String) {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val c = env.player
    val p = palette()
    val songs = lib.songsBy(name).sortedByDescending { lib.plays(it) }
    val cover = songs.firstOrNull { it.artworkUrl != null || it.mediaId != null } ?: songs.firstOrNull()
    val liked = songs.count { lib.isFavorite(it) }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        PageBar(onBack = { env.nav.pop() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtistAvatar(cover, size = 168.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        name, textAlign = TextAlign.Center, color = p.ink, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(count(songs.size, "song"), if (liked > 0) "$liked liked" else null).joinToString("  •  "),
                        textAlign = TextAlign.Center, color = p.sub, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        PillButton(
                            AppIcons.playCircle, "Play",
                            if (songs.isEmpty()) null else { { c.playQueue(songs, 0) } },
                            Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        PillButton(
                            AppIcons.shuffle, "Shuffle",
                            if (songs.isEmpty()) null else { { c.playShuffled(songs) } },
                            Modifier.weight(1f), filled = false,
                        )
                    }
                }
            }
            if (songs.isEmpty()) {
                item { EmptyState(AppIcons.music, "No songs by this artist yet.") }
            } else {
                item { SectionHeader(if (lib.plays(songs.first()) > 0) "Most played" else "Songs") }
            }
            items(songs.size) { i ->
                NumberedTrackRow(index = i, track = songs[i], onTap = { c.playQueue(songs, i) })
            }
        }
    }
}
