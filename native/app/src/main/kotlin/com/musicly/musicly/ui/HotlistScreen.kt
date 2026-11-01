@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.Track

/** Retry button used on empty states (Flutter OutlinedButton). */
@Composable
internal fun OutlinedRetryButton(label: String, onClick: () -> Unit) {
    val p = palette()
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).border(BorderStroke(1.dp, p.line), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = p.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
}

@Composable
fun HotlistScreen() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val c = env.player
    val p = palette()
    var tab by remember { mutableIntStateOf(0) } // 0 songs, 1 stations
    var attempt by remember { mutableIntStateOf(0) }
    var stations by remember { mutableStateOf<List<Track>?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(tab, attempt) {
        if (tab == 1 && (stations == null || attempt > 0)) {
            loading = true
            stations = try {
                lib.radio.top(limit = 50)
            } catch (e: Exception) {
                emptyList()
            }
            loading = false
        }
    }

    @Composable
    fun Rank(i: Int) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Text(
                "${i + 1}", textAlign = TextAlign.Center, fontSize = if (i < 3) 20.sp else 16.sp,
                fontWeight = FontWeight.ExtraBold, color = if (i < 3) p.ink else p.sub,
            )
        }
    }

    @Composable
    fun TrackList(list: List<Track>) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(list) { i, t ->
                ArtTrackRow(track = t, onTap = { c.playQueue(list, i) }, leading = { Rank(i) })
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Hotlist", modifier = Modifier.weight(1f), fontSize = 30.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp, color = p.ink,
            )
            // Segmented button: Your top | Radio
            val shape = RoundedCornerShape(50)
            Row(Modifier.height(40.dp).clip(shape).border(1.dp, p.line, shape)) {
                listOf("Your top", "Radio").forEachIndexed { i, label ->
                    val sel = tab == i
                    Box(
                        Modifier.fillMaxHeight().background(if (sel) p.ink else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { tab = i }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (sel) p.onInk else p.ink)
                    }
                    if (i == 0) Box(Modifier.width(1.dp).fillMaxHeight().background(p.line))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                val top = lib.mostPlayed.take(50)
                if (top.isEmpty()) {
                    EmptyState(
                        AppIcons.hot,
                        "Your most played songs show up here.\nStart listening to your channel or phone music.",
                    )
                } else TrackList(top)
            } else {
                val list = stations
                when {
                    loading || list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = p.ink)
                    }
                    list.isEmpty() -> EmptyState(
                        AppIcons.offline, "Could not load stations.",
                        action = { OutlinedRetryButton("Retry") { attempt++ } },
                    )
                    else -> TrackList(list)
                }
            }
        }
    }
}
