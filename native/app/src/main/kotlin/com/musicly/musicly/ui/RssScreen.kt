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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.RssFeed
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.net.RssSuggestion
import com.musicly.musicly.net.rssCatalog
import kotlinx.coroutines.launch

private val ErrorRed = Color(0xFFD64545)

private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }

/** Material FilledButton / OutlinedButton look: 40dp pill, 14sp medium label. */
@Composable
private fun PillAction(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: Ph? = null,
    outlined: Boolean = false,
    minWidth: androidx.compose.ui.unit.Dp = 0.dp,
    minHeight: androidx.compose.ui.unit.Dp = 40.dp,
    content: (@Composable () -> Unit)? = null,
) {
    val p = palette()
    val enabled = onClick != null
    val fg = if (outlined) p.ink else p.onInk
    val shape = RoundedCornerShape(50)
    val bg = if (outlined) Color.Transparent else if (enabled) p.ink else p.ink.copy(alpha = 0.12f)
    Row(
        modifier.defaultMinSize(minWidth = minWidth, minHeight = minHeight).clip(shape).background(bg)
            .let { if (outlined) it.border(BorderStroke(1.dp, p.line), shape) else it }
            .clickable(enabled = enabled) { onClick?.invoke() }
            .padding(start = if (icon != null) 16.dp else 24.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, size = 18.dp, tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        if (content != null) content()
        else Text(label, color = if (enabled) fg else p.ink.copy(alpha = 0.38f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** RSS feeds: paste a link to a podcast or music feed and stream its songs. */
@Composable
fun RssScreen() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val p = palette()

    fun browse() = env.nav.push("rss:catalog") { CatalogScreen() }
    fun add() = showAddFeedDialog(env)
    fun open(f: RssFeed) {
        openPage(env, "rss:feed:${f.url}") {
            CollectionScreen(
                title = f.title,
                owner = hostOf(f.url) ?: "RSS feed",
                kind = "RSS feed",
                cover = f.imageUrl?.let {
                    Track(id = "rssfeed:${f.url}", title = f.title, artist = "", source = TrackSource.feed, artworkUrl = it)
                },
                load = { env.library.rss.load(f.url).tracks },
            )
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        PageBar(
            onBack = { env.nav.pop() }, center = "RSS feeds", centerInset = 104.dp,
            actions = {
                AppIconButton(AppIcons.queue, { browse() })
                AppIconButton(AppIcons.add, { add() })
            },
        )
        if (lib.feeds.isEmpty()) {
            EmptyState(
                AppIcons.broadcast,
                "Add the link of an RSS feed with audio in it, such as a podcast or a music mix, and stream it here.",
                Modifier.fillMaxSize(),
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PillAction("Browse suggested feeds", { browse() }, icon = AppIcons.queue)
                        Spacer(Modifier.height(10.dp))
                        PillAction("Paste a link", { add() }, icon = AppIcons.add, outlined = true)
                    }
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { browse() }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.queue, size = 20.dp, tint = p.sub)
                        Spacer(Modifier.width(12.dp))
                        Text("Browse suggested feeds", Modifier.weight(1f), color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Icon(AppIcons.chevron, size = 18.dp, tint = p.sub)
                    }
                }
                items(lib.feeds.toList().size) { i ->
                    val f = lib.feeds[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { open(f) }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(p.card), contentAlignment = Alignment.Center) {
                            if (f.imageUrl == null) Icon(AppIcons.broadcast, size = 24.dp, tint = p.sub)
                            else RemoteImage(
                                f.imageUrl, Modifier.size(56.dp), ContentScale.Crop,
                                placeholder = { Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Icon(AppIcons.broadcast, size = 24.dp, tint = p.sub) } },
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(hostOf(f.url) ?: f.url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.sub, fontSize = 13.sp)
                        }
                        AppIconButton(AppIcons.trash, {
                            showConfirmDialog(
                                env, "Remove ${f.title}?",
                                "Its songs stop showing here. You can add it again any time.", "Remove",
                            ) { env.library.removeFeed(f) }
                        }, tint = p.sub, iconSize = 20.dp)
                    }
                }
            }
        }
    }
}

private fun showAddFeedDialog(env: Env) {
    env.showDialog { dismiss ->
        val p = palette()
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        fun go() {
            if (text.trim().isEmpty() || busy) return
            busy = true
            error = null
            scope.launch {
                try {
                    val feed = env.library.addFeed(text)
                    dismiss()
                    env.toast("Added ${feed.title}")
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    busy = false
                    error = (e.message ?: "Something went wrong.").removePrefix("Exception: ")
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!busy) dismiss() },
            containerColor = p.bg,
            title = { Text("Add an RSS feed", color = p.ink, fontSize = 24.sp) },
            text = {
                Column {
                    AppTextField(
                        text, { text = it }, "Paste the feed link", Modifier.focusRequester(focus),
                        onSubmit = { go() }, imeAction = ImeAction.Done,
                    )
                    if (error != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(error!!, color = ErrorRed, fontSize = 14.sp)
                    }
                }
            },
            dismissButton = { TextButton({ dismiss() }, enabled = !busy) { Text("Cancel", color = p.ink) } },
            confirmButton = {
                PillAction("Add", if (busy) null else { { go() } }) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = p.onInk, strokeWidth = 2.dp)
                    else Text("Add", color = p.onInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            },
        )
    }
}

/** Ready-made feeds to pick from; tap Add to keep one. */
@Composable
private fun CatalogScreen() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val p = palette()
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateListOf<String>() }
    val groups = rssCatalog.groupBy { it.group }

    fun add(s: RssSuggestion) {
        busy.add(s.url)
        scope.launch {
            try {
                lib.addFeed(s.url)
                env.toast("Added ${s.title}")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                env.toast((e.message ?: "Something went wrong.").removePrefix("Exception: "))
            }
            busy.remove(s.url)
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        PageBar(onBack = { env.nav.pop() }, center = "Suggested feeds")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            for ((group, list) in groups) {
                item(key = "g:$group") {
                    Text(
                        group, Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 6.dp),
                        color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                }
                items(list.size, key = { "s:${list[it].url}" }) { i ->
                    val s = list[i]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(p.card), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.broadcast, size = 24.dp, tint = p.sub)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(s.note, color = p.sub, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        if (lib.feeds.any { it.url == s.url }) {
                            Icon(AppIcons.check, size = 24.dp, tint = p.sub)
                        } else if (s.url in busy) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = p.ink, strokeWidth = 2.dp)
                            }
                        } else {
                            PillAction("Add", { add(s) }, minWidth = 64.dp, minHeight = 38.dp)
                        }
                    }
                }
            }
        }
    }
}
