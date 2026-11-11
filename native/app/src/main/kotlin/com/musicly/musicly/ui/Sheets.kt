@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.musicly.musicly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.net.Lyrics
import com.musicly.musicly.player.EqPreset
import com.musicly.musicly.player.LoopMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

private val sheetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

fun toast(env: Env, msg: String) = env.toast(msg)

/** Icon for the repeat button on Now Playing. */
fun repeatIcon(m: LoopMode): Ph = if (m == LoopMode.one) AppIcons.repeatOne else AppIcons.repeat

// -- shared sheet pieces ------------------------------------------------------------

/** Flutter ListTile (24dp side padding by default). */
@Composable
fun SheetTile(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: Ph? = null,
    sub: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    titleWeight: FontWeight = FontWeight.Medium,
    maxLines: Int = Int.MAX_VALUE,
    horizontalPadding: Dp = 24.dp,
) {
    val p = palette()
    Row(
        modifier.fillMaxWidth().heightIn(min = if (sub == null) 56.dp else 72.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.defaultMinSize(minWidth = 40.dp), contentAlignment = Alignment.CenterStart) { leading() }
            Spacer(Modifier.width(16.dp))
        } else if (icon != null) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) { Icon(icon, size = 22.dp, tint = p.ink) }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = titleWeight, fontSize = 16.sp, color = p.ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 14.sp, color = p.sub, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

@Composable
fun SheetTitle(title: String, sub: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val p = palette()
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink)
            if (sub != null) Text(sub, color = Color(0xFF8A8FA3), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        trailing?.invoke()
    }
}

@Composable
fun SheetDivider(height: Dp = 1.dp) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette().line))
    }
}

/** Rounded pill used for ChoiceChip. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = palette()
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (selected) p.ink else p.card).clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 14.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) p.onInk else p.ink,
        )
    }
}

@Composable
private fun SwitchTile(
    icon: Ph, title: String, checked: Boolean, onChange: ((Boolean) -> Unit)?, sub: String? = null,
) {
    SheetTile(
        title, onClick = onChange?.let { { it(!checked) } }, icon = icon, sub = sub, titleWeight = FontWeight.SemiBold,
        trailing = { Switch(checked, onChange, enabled = onChange != null) },
    )
}

@Composable
private fun AppSlider(
    value: Float, min: Float, max: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, divisions: Int = 0,
) {
    val p = palette()
    Slider(
        value.coerceIn(min, max), onChange, modifier, valueRange = min..max,
        steps = if (divisions > 1) divisions - 1 else 0,
        colors = SliderDefaults.colors(
            thumbColor = p.ink, activeTrackColor = p.ink, inactiveTrackColor = p.line,
            activeTickColor = p.onInk.copy(alpha = 0.4f), inactiveTickColor = p.sub.copy(alpha = 0.4f),
        ),
    )
}

/** Rounded filled text field matching the app's InputDecorationTheme. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null,
    capitalize: Boolean = false,
    singleLine: Boolean = true,
) {
    val p = palette()
    BasicTextField(
        value, onValueChange, modifier,
        singleLine = singleLine,
        textStyle = TextStyle(color = p.ink, fontSize = 16.sp, fontFamily = Inter),
        cursorBrush = SolidColor(p.ink),
        keyboardOptions = KeyboardOptions(
            imeAction = imeAction,
            capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        keyboardActions = KeyboardActions(onAny = { onSubmit?.invoke() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(12.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, color = p.sub, fontSize = 16.sp)
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}

// -- dialog -----------------------------------------------------------------------------------

/**
 * Text prompt dialog (shown through `env.showDialog`; the content emits its own
 * AlertDialog). [onResult] gets the text, or null when cancelled.
 */
fun promptText(
    env: Env,
    title: String,
    initial: String = "",
    action: String = "Create",
    hint: String = "Playlist name",
    onResult: (String?) -> Unit,
) {
    env.showDialog { dismiss ->
        val p = palette()
        var text by remember { mutableStateOf(initial) }
        val focus = remember { FocusRequester() }
        fun finish(v: String?) {
            dismiss()
            onResult(v)
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        AlertDialog(
            onDismissRequest = { finish(null) },
            containerColor = p.bg,
            title = { Text(title, color = p.ink, fontSize = 24.sp) },
            text = {
                AppTextField(
                    text, { text = it }, hint, Modifier.focusRequester(focus),
                    onSubmit = { finish(text) }, capitalize = true,
                )
            },
            dismissButton = { TextButton({ finish(null) }) { Text("Cancel", color = p.ink) } },
            confirmButton = {
                Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(p.ink).clickable { finish(text) }.padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(action, color = p.onInk, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
            },
        )
    }
}

/** Suspending wrapper: the entered text, or null when cancelled. */
suspend fun Env.promptText(
    title: String,
    initial: String = "",
    action: String = "Create",
    hint: String = "Playlist name",
): String? = suspendCancellableCoroutine { cont ->
    promptText(this, title, initial, action, hint) { if (cont.isActive) cont.resume(it) }
}

// -- track options -------------------------------------------------------------------------------

fun showTrackOptions(env: Env, t: Track, onRemove: (() -> Unit)? = null, removeLabel: String? = null) {
    val c = env.player
    val lib = env.library
    env.showSheet { dismiss ->
        val p = palette()
        val fav = remember { lib.isFavorite(t) }
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            SheetTile(
                t.title, onClick = null, leading = { Artwork(t, size = 52.dp) },
                sub = if (t.album == null) t.artist else "${t.artist} • ${t.album}",
                titleWeight = FontWeight.Bold, maxLines = 1,
            )
            SheetDivider()
            SheetTile(if (fav) "Remove from Liked songs" else "Like", icon = if (fav) AppIcons.heartOn else AppIcons.heart, onClick = {
                lib.toggleFavorite(t)
                dismiss()
            })
            if (t.isPersistable) {
                SheetTile("Add to playlist", icon = AppIcons.addPlaylist, onClick = {
                    dismiss()
                    showAddToPlaylist(env, listOf(t))
                })
            }
            SheetTile("Play next", icon = AppIcons.playNext, onClick = {
                c.playNext(t)
                dismiss()
                env.toast("Playing next")
            })
            SheetTile("Add to queue", icon = AppIcons.addQueue, onClick = {
                c.addToQueue(listOf(t))
                dismiss()
                env.toast("Added to queue")
            })
            if (!t.isRadio && t.artist != "Unknown artist") {
                SheetTile("Go to artist", icon = AppIcons.artist, onClick = {
                    dismiss()
                    openArtist(env, t.artist)
                })
            }
            if (t.source == TrackSource.device && t.albumId != null) {
                SheetTile("Go to album", icon = AppIcons.disc, onClick = {
                    dismiss()
                    openAlbumOf(env, t)
                })
            }
            if (t.source == TrackSource.telegram && t.id.startsWith("tg:") && !lib.isDownloaded(t) && lib.canDownload) {
                SheetTile("Save to this phone", icon = AppIcons.download, onClick = {
                    dismiss()
                    sheetScope.launch { lib.downloadSongs(listOf(t)) }
                    env.toast("Saving to Music/Musicly")
                })
            }
            if (t.isRadio && t.link != null) {
                SheetTile("Open station website", icon = AppIcons.external, onClick = {
                    dismiss()
                    env.openUrl?.invoke(t.link)
                })
            }
            if (onRemove != null) {
                SheetTile(removeLabel ?: "Remove from library", icon = AppIcons.trash, onClick = {
                    onRemove()
                    dismiss()
                })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

fun showAddToPlaylist(env: Env, tracks: List<Track>, suggestedName: String? = null) {
    val lib = env.library
    fun done(n: Int, name: String) = env.toast(
        if (n == 0) "Already in $name" else "Added ${if (n == 1) "1 song" else "$n songs"} to $name",
    )
    env.showSheet { dismiss ->
        val p = palette()
        lib.observe()
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
        Column(Modifier.navigationBarsPadding().heightIn(max = maxH)) {
            SheetTitle("Add to playlist")
            SheetTile(
                "New playlist", titleWeight = FontWeight.SemiBold,
                leading = {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(p.ink), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.add, size = 24.dp, tint = p.onInk)
                    }
                },
                onClick = {
                    promptText(env, "New playlist", initial = suggestedName ?: "") { name ->
                        if (name != null) {
                            val pl = lib.createPlaylist(name, tracks.filter { it.isPersistable })
                            dismiss()
                            done(pl.tracks.size, pl.name)
                        }
                    }
                },
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                for (pl in lib.playlists.toList()) {
                    SheetTile(
                        pl.name, titleWeight = FontWeight.SemiBold, sub = count(pl.tracks.size, "song"),
                        leading = { Mosaic(pl.tracks, size = 52.dp) },
                        onClick = {
                            val n = lib.addToPlaylist(pl, tracks)
                            dismiss()
                            done(n, pl.name)
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

fun showSleepTimer(env: Env) {
    val c = env.player
    env.showSheet { dismiss ->
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            SheetTitle("Sleep timer", sub = "Music fades out gently when time is up.")
            for (m in listOf(5, 10, 15, 30, 45, 60)) {
                SheetTile("$m minutes", icon = AppIcons.sleep, onClick = {
                    c.setSleepTimer(m * 60_000L)
                    dismiss()
                })
            }
            SheetTile("End of this song", icon = AppIcons.music, onClick = {
                c.setSleepTimer(null, atTrackEnd = true)
                dismiss()
            })
            if (c.sleepActive) {
                SheetTile("Turn off timer", icon = AppIcons.close, onClick = {
                    c.setSleepTimer(null)
                    dismiss()
                })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

fun showSpeed(env: Env) {
    val c = env.player
    env.showSheet { _ ->
        Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SheetTitle("Playback speed", trailing = {
                Text(String.format(Locale.US, "%.2fx", c.speed), fontWeight = FontWeight.Bold, color = palette().ink)
            })
            AppSlider(c.speed, 0.5f, 2.0f, { c.setSpeed(it) }, Modifier.padding(horizontal = 12.dp).fillMaxWidth(), divisions = 15)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)) {
                    ChoiceChip("${s}x", c.speed == s, { c.setSpeed(s) })
                }
            }
        }
    }
}

/** Equalizer, loudness and smooth fades. */
fun showSound(env: Env) {
    val c = env.player
    val labels = listOf("60", "230", "910", "3.6k", "14k")
    env.showSheet { _ ->
        val p = palette()
        val curve = c.activeCurve
        val eqOn = c.eqEnabled && c.effectsSupported
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle("Sound")
            if (!c.effectsSupported) {
                Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(14.dp)) {
                    Text("Equalizer and loudness boost need Android. Smooth fades work everywhere.", color = p.sub, fontSize = 14.sp)
                }
            }
            SwitchTile(AppIcons.eq, "Equalizer", c.eqEnabled, if (c.effectsSupported) { v -> c.setEq(enabled = v) } else null)
            Column(Modifier.alpha(if (eqOn) 1f else 0.4f)) {
                Row(Modifier.height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (pr in EqPreset.entries.filter { it != EqPreset.custom }) {
                        ChoiceChip(pr.label, c.eqPreset == pr, { if (eqOn) c.setEq(preset = pr) }, Modifier.padding(end = 8.dp))
                    }
                    ChoiceChip("Custom", c.eqPreset == EqPreset.custom, { if (eqOn) c.setEq(preset = EqPreset.custom) })
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (i in 0 until 5) {
                        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                (if (curve[i] > 0) "+" else "") + String.format(Locale.US, "%.0f", curve[i]),
                                fontSize = 11.sp, color = p.sub, fontWeight = FontWeight.SemiBold,
                            )
                            Box(Modifier.weight(1f).defaultMinSize(minWidth = 48.dp)) {
                                VerticalSlider(curve[i].toFloat(), -10f, 10f, enabled = eqOn) { v ->
                                    val g = curve.toMutableList()
                                    g[i] = v.toDouble()
                                    c.setEq(custom = g)
                                }
                            }
                            Text(labels[i], fontSize = 11.sp, color = p.sub)
                        }
                    }
                }
            }
            SheetDivider(32.dp)
            SwitchTile(
                AppIcons.loud, "Loudness boost", c.loudnessOn,
                if (c.effectsSupported) { v -> c.setLoudness(on = v) } else null,
                sub = "+${String.format(Locale.US, "%.0f", c.loudnessGain)} dB for quiet songs and speakers",
            )
            if (c.loudnessOn) {
                AppSlider(c.loudnessGain.toFloat(), 1f, 10f, { c.setLoudness(gain = it.toDouble()) }, Modifier.padding(horizontal = 12.dp).fillMaxWidth(), divisions = 9)
            }
            SwitchTile(
                AppIcons.fade, "Smooth fades", c.smoothFade, { v -> c.setFade(on = v) },
                sub = "Fade songs out and in over ${String.format(Locale.US, "%.0f", c.fadeSeconds)}s",
            )
            if (c.smoothFade) {
                AppSlider(c.fadeSeconds.toFloat(), 1f, 8f, { c.setFade(seconds = it.toDouble()) }, Modifier.padding(horizontal = 12.dp).fillMaxWidth(), divisions = 7)
            }
        }
    }
}

/** A slider that runs bottom (min) to top (max), like Flutter's RotatedBox(quarterTurns: 3). */
@Composable
private fun VerticalSlider(value: Float, min: Float, max: Float, enabled: Boolean = true, onChange: (Float) -> Unit) {
    val p = palette()
    Slider(
        value.coerceIn(min, max), onChange,
        Modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { m, constraints ->
                val pl = m.measure(Constraints(constraints.minHeight, constraints.maxHeight, constraints.minWidth, constraints.maxWidth))
                layout(pl.height, pl.width) { pl.place(-pl.width, 0) }
            },
        enabled = enabled, valueRange = min..max,
        colors = SliderDefaults.colors(thumbColor = p.ink, activeTrackColor = p.ink, inactiveTrackColor = p.line, disabledThumbColor = p.ink, disabledActiveTrackColor = p.ink, disabledInactiveTrackColor = p.line),
    )
}

// -- queue --------------------------------------------------------------------------------------

private fun queueKeys(queue: List<Track>): List<String> {
    val seen = HashMap<String, Int>()
    return queue.map { t ->
        val k = (seen[t.id] ?: 0)
        seen[t.id] = k + 1
        "${t.id}#$k"
    }
}

fun showQueue(env: Env) {
    val c = env.player
    env.showSheet { _ ->
        val p = palette()
        val rowH = 72.dp
        var dragKey by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val keys = queueKeys(c.queue)
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
            SheetTitle("Up next", trailing = { Text(count(c.queue.size, "song"), color = p.sub, fontSize = 14.sp) })
            LazyColumn(Modifier.weight(1f).navigationBarsPadding()) {
                itemsIndexed(c.queue, key = { i, _ -> keys[i] }) { i, t ->
                    val key = keys[i]
                    val active = i == c.currentIndex
                    val dragging = dragKey == key
                    Row(
                        Modifier.fillMaxWidth().height(rowH)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                            .background(if (active) p.card else Color.Transparent)
                            .clickable { c.jumpTo(i) }
                            .padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(t, size = 44.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = p.ink)
                            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = p.sub)
                        }
                        if (active) {
                            Box(Modifier.padding(12.dp)) { EqualizerBars(playing = c.isPlaying) }
                        } else {
                            AppIconButton(AppIcons.close, { c.removeFromQueue(i) }, tint = p.sub, iconSize = 18.dp)
                        }
                        Box(
                            Modifier.padding(8.dp).pointerInput(key) {
                                val itemPx = rowH.toPx()
                                detectDragGestures(
                                    onDragStart = {
                                        dragKey = key
                                        dragOffset = 0f
                                    },
                                    onDragEnd = { dragKey = null; dragOffset = 0f },
                                    onDragCancel = { dragKey = null; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val cur = queueKeys(c.queue).indexOf(key)
                                    if (cur < 0) return@detectDragGestures
                                    if (dragOffset > itemPx / 2 && cur < c.queue.lastIndex) {
                                        c.moveInQueue(cur, cur + 1)
                                        dragOffset -= itemPx
                                    } else if (dragOffset < -itemPx / 2 && cur > 0) {
                                        c.moveInQueue(cur, cur - 1)
                                        dragOffset += itemPx
                                    }
                                }
                            },
                        ) { Icon(AppIcons.drag, tint = p.sub) }
                    }
                }
            }
        }
    }
}

// -- lyrics search ---------------------------------------------------------------------------------------

/** Lets the user search LRCLIB and pick the right lyrics for the current song. */
fun showLyricsSearch(env: Env) {
    val t = env.player.current ?: return
    val initial = if (t.artist == "Unknown artist") t.title else "${t.title} ${t.artist}"
    env.showSheet { dismiss ->
        val p = palette()
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf(initial) }
        var results by remember { mutableStateOf<List<Lyrics>?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun search() {
            val q = query.trim()
            if (q.isEmpty()) return
            loading = true
            error = null
            scope.launch {
                try {
                    val r = env.lyrics.api.search(q)
                    results = r.sortedByDescending { if (it.isSynced) 1 else 0 }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "Search failed. Check your connection."
                }
                loading = false
            }
        }
        LaunchedEffect(Unit) { search() }

        val h = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(Modifier.imePadding().fillMaxWidth().height(h)) {
            SheetTitle("Search lyrics")
            AppTextField(
                query, { query = it }, "Song title and artist", Modifier.padding(horizontal = 20.dp),
                leading = { Icon(AppIcons.search, size = 20.dp, tint = p.ink) },
                trailing = { Box(Modifier.clickable { search() }) { Icon(AppIcons.chevron, tint = p.ink) } },
                imeAction = ImeAction.Search, onSubmit = { search() },
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val r = results
                when {
                    loading -> Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = p.ink)
                    }
                    error != null -> EmptyState(AppIcons.offline, error!!)
                    r != null && r.isEmpty() -> EmptyState(AppIcons.lyrics, "No lyrics found. Try another search.")
                    else -> LazyColumn(Modifier.navigationBarsPadding()) {
                        itemsIndexed(r ?: emptyList()) { _, l ->
                            SheetTile(
                                l.trackName, titleWeight = FontWeight.SemiBold,
                                sub = "${l.artistName}  •  ${fmt(l.duration)}",
                                trailing = {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(20.dp)).background(if (l.isSynced) p.ink else p.card)
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            if (l.isSynced) "Synced" else if (l.instrumental) "Instrumental" else "Plain",
                                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                            color = if (l.isSynced) p.onInk else p.sub,
                                        )
                                    }
                                },
                                onClick = {
                                    env.lyrics.choose(l)
                                    dismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
