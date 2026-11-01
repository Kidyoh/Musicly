@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.Track
import com.musicly.musicly.net.RadioApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface RadioResult {
    data object Loading : RadioResult
    data object Failed : RadioResult
    data class Done(val list: List<Track>) : RadioResult
}

/** Live radio from around the world: near you, top stations, by genre, or search. */
@Composable
fun RadioScreen(initialFilter: String = "near") {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val c = env.player
    val p = palette()
    var filter by remember { mutableStateOf(initialFilter) }
    var q by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<RadioResult>(RadioResult.Loading) }

    LaunchedEffect(q) {
        if (q == applied) return@LaunchedEffect
        delay(450)
        applied = q
    }

    LaunchedEffect(filter, applied, tick) {
        result = RadioResult.Loading
        val api = lib.radio
        result = try {
            val s = applied.trim()
            val list = when {
                s.isNotEmpty() -> api.search(s, limit = 40)
                filter == "near" -> lib.countryCode?.let { api.byCountry(it) } ?: api.top()
                filter == "top" -> api.top()
                filter == "saved" -> lib.savedStations
                else -> api.byTag(filter)
            }
            RadioResult.Done(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RadioResult.Failed
        }
    }

    val filters = listOf("near" to "Near you", "top" to "Top", "saved" to "Saved") + RadioApi.tags

    Column(Modifier.fillMaxSize()) {
        // AppBar
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            AppIconButton(
                AppIcons.back, onClick = { env.nav.pop() },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
            )
            Text(
                "Live radio", modifier = Modifier.align(Alignment.Center),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.ink,
            )
        }
        Box(Modifier.padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 12.dp)) {
            AppTextField(
                value = q, onValueChange = { q = it }, hint = "Search stations",
                leading = { Icon(AppIcons.search, size = 20.dp, tint = p.sub) },
            )
        }
        LazyRow(
            Modifier.fillMaxWidth().height(40.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(filters.size) { i ->
                val (id, label) = filters[i]
                ChoiceChip(label, selected = filter == id && q.isEmpty(), onClick = {
                    filter = id
                    tick++
                })
            }
        }
        Box(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val r = result) {
                RadioResult.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.ink)
                }
                RadioResult.Failed -> EmptyState(
                    AppIcons.offline, "Could not reach the radio directory.",
                    action = { OutlinedRetryButton("Retry") { tick++ } },
                )
                is RadioResult.Done -> {
                    val list = r.list
                    if (list.isEmpty()) {
                        EmptyState(
                            AppIcons.radio,
                            if (filter == "saved" && q.isEmpty()) "Tap the heart while a station plays to save it here."
                            else "No stations found.",
                        )
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val inner = maxWidth - 40.dp
                            val cols = kotlin.math.ceil(inner.value / 180f).toInt().coerceAtLeast(1)
                            val cell = (inner - 14.dp * (cols - 1)) / cols
                            LazyVerticalGrid(
                                GridCells.Fixed(cols),
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(list.size) { i ->
                                    Box(Modifier.fillMaxWidth().aspectRatio(0.74f)) {
                                        StationCard(station = list[i], width = cell, onTap = { c.playQueue(list, i) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
            Text("Stations from radio-browser.info", color = p.sub, fontSize = 11.sp)
        }
    }
}
