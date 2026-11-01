@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.musicly.musicly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.musicly.musicly.data.Library
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.data.UserPlaylist
import kotlinx.coroutines.launch

// -- shared page chrome (also used by ArtistScreen and RssScreen) -------------------------------------

/**
 * Flutter AppBar: 56dp, back button, optional centred [center] title or a [field] in the
 * title slot, and [actions] on the right.
 */
@Composable
internal fun PageBar(
    onBack: () -> Unit,
    center: String? = null,
    centerInset: Dp = 56.dp,
    field: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = palette()
    Box(Modifier.fillMaxWidth().background(p.bg).statusBarsPadding().height(56.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                AppIconButton(AppIcons.back, onBack)
            }
            if (field != null) field() else Spacer(Modifier.weight(1f))
            actions()
        }
        if (center != null) {
            Box(Modifier.align(Alignment.Center).padding(horizontal = centerInset)) {
                Text(center, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Confirmation dialog: title, body, Cancel and a filled action button. */
internal fun showConfirmDialog(env: Env, title: String, body: String, action: String, onConfirm: () -> Unit) {
    env.showDialog { dismiss ->
        val p = palette()
        AlertDialog(
            onDismissRequest = dismiss,
            containerColor = p.bg,
            title = { Text(title, color = p.ink, fontSize = 24.sp) },
            text = { Text(body, color = p.sub, fontSize = 14.sp, lineHeight = 20.sp) },
            dismissButton = { TextButton(dismiss) { Text("Cancel", color = p.ink) } },
            confirmButton = {
                Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(p.ink).clickable {
                        dismiss()
                        onConfirm()
                    }.padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(action, color = p.onInk, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
            },
        )
    }
}

// -- Collection ------------------------------------------------------------------------------------------

private enum class Sort { original, title, artist, duration }

@Composable
fun CollectionScreen(
    title: String,
    owner: String,
    ownerArtistId: String? = null,
    kind: String = "Playlist",
    year: Int? = null,
    cover: Track? = null,
    load: (suspend () -> List<Track>)? = null,
    live: ((Library) -> List<Track>)? = null,
    removableFiles: Boolean = false,
    actions: List<@Composable () -> Unit> = emptyList(),
    onRefresh: (suspend () -> Unit)? = null,
    banner: (@Composable () -> Unit)? = null,
) {
    val env = LocalEnv.current
    val lib = env.library.observe()
    var loaded by remember { mutableStateOf<List<Track>?>(null) }
    var failed by remember { mutableStateOf(false) }
    if (live == null) {
        LaunchedEffect(Unit) {
            try {
                loaded = load?.invoke() ?: emptyList()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    val all = if (live != null) live(lib) else loaded ?: emptyList()
    val loading = live == null && loaded == null && !failed
    CollectionBody(
        title = title, owner = owner, ownerArtistId = ownerArtistId, kind = kind, year = year, cover = cover,
        all = all, loading = loading, error = if (failed) "Could not load songs." else null, user = null,
        removableFiles = removableFiles, actions = actions, onRefresh = onRefresh, banner = banner,
    )
}

/** A playlist the user made: editable, reorderable (Dart `CollectionScreen.user`). */
@Composable
fun CollectionScreen(playlistId: String) {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val pl = lib.playlist(playlistId)
    if (pl == null) {
        Box(Modifier.fillMaxSize().background(palette().bg)) {
            EmptyState(AppIcons.playNext, "Playlist deleted.")
        }
        return
    }
    CollectionBody(
        title = "", owner = "You", ownerArtistId = null, kind = "Your playlist", year = null, cover = null,
        all = pl.tracks.toList(), loading = false, error = null, user = pl,
        removableFiles = false, actions = emptyList(), onRefresh = null, banner = null,
    )
}

@Composable
private fun CollectionBody(
    title: String,
    owner: String,
    ownerArtistId: String?,
    kind: String,
    year: Int?,
    cover: Track?,
    all: List<Track>,
    loading: Boolean,
    error: String?,
    user: UserPlaylist?,
    removableFiles: Boolean,
    actions: List<@Composable () -> Unit>,
    onRefresh: (suspend () -> Unit)?,
    banner: (@Composable () -> Unit)?,
) {
    val env = LocalEnv.current
    val c = env.player
    val lib = env.library
    val p = palette()
    val scope = rememberCoroutineScope()

    var searching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.original) }
    var refreshing by remember { mutableStateOf(false) }

    val ql = q.lowercase()
    val tracks = all
        .filter { ql.isEmpty() || it.title.lowercase().contains(ql) || it.artist.lowercase().contains(ql) }
        .let { l ->
            when (sort) {
                Sort.title -> l.sortedBy { it.title.lowercase() }
                Sort.artist -> l.sortedBy { it.artist.lowercase() }
                Sort.duration -> l.sortedBy { it.duration ?: 0L }
                Sort.original -> l
            }
        }
    val shownTitle = user?.name ?: title
    val meta = listOfNotNull(
        kind,
        if (!loading) count(all.size, "song") else null,
        year?.toString(),
    ).joinToString(" • ")
    val canReorder = user != null && editing && q.isEmpty() && sort == Sort.original &&
        tracks.map { it.id }.toSet().size == tracks.size

    val listState = rememberLazyListState()
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val tracksNow by rememberUpdatedState(tracks)

    fun maybeSwap(key: String) {
        val u = user ?: return
        val list = tracksNow
        val i = list.indexOfFirst { it.id == key }
        if (i < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
        val info = visible.firstOrNull { it.key == key } ?: return
        val center = info.offset + info.size / 2f + dragOffset
        if (i < list.lastIndex) {
            val n = visible.firstOrNull { it.key == list[i + 1].id }
            if (n != null && center > n.offset + n.size / 2f) {
                lib.reorderPlaylist(u, i, i + 1)
                dragOffset -= n.size
                return
            }
        }
        if (i > 0) {
            val n = visible.firstOrNull { it.key == list[i - 1].id }
            if (n != null && center < n.offset + n.size / 2f) {
                lib.reorderPlaylist(u, i, i - 1)
                dragOffset += n.size
            }
        }
    }

    var menu by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        PageBar(
            onBack = { env.nav.pop() },
            field = {
                if (searching) {
                    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                    BasicTextField(
                        q, { q = it },
                        Modifier.weight(1f).focusRequester(searchFocus),
                        singleLine = true,
                        textStyle = TextStyle(color = p.ink, fontSize = 16.sp, fontFamily = Inter),
                        cursorBrush = SolidColor(p.ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onAny = {}),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                if (q.isEmpty()) Text("Find in list", color = p.sub, fontSize = 16.sp)
                                inner()
                            }
                        },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            },
            actions = {
                AppIconButton(if (searching) AppIcons.close else AppIcons.search, {
                    searching = !searching
                    q = ""
                })
                actions.forEach { it() }
            },
        )

        val content: @Composable () -> Unit = {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(0.dp)) {
                item(key = "header") {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp)) {
                            if (user != null) Mosaic(user.tracks.toList(), size = 112.dp, radius = 14.dp)
                            else Artwork(cover ?: all.firstOrNull(), size = 112.dp, radius = 14.dp, shadow = true)
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(meta, color = p.sub, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    shownTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = p.ink,
                                    fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, lineHeight = 29.9.sp,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (user != null) "You" else owner,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.sub, fontSize = 14.sp,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = if (ownerArtistId == null) Modifier else Modifier.tap { openArtist(env, owner) },
                                )
                                Spacer(Modifier.height(10.dp))
                                Row {
                                    OutlineIcon(AppIcons.addQueue, onTap = if (tracks.isEmpty()) null else {
                                        {
                                            c.addToQueue(tracks)
                                            env.toast("Added ${tracks.size} songs to queue")
                                        }
                                    })
                                    Spacer(Modifier.width(10.dp))
                                    OutlineIcon(
                                        if (user != null) (if (editing) AppIcons.check else AppIcons.edit) else AppIcons.addPlaylist,
                                        onTap = if (user != null) {
                                            { editing = !editing }
                                        } else if (tracks.isEmpty()) null else {
                                            { showAddToPlaylist(env, tracks, suggestedName = shownTitle) }
                                        },
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Box {
                                        OutlineIcon(AppIcons.more, wide = true, onTap = { menu = true })
                                        DropdownMenu(menu, { menu = false }) {
                                            MenuItem("Like all songs") {
                                                menu = false
                                                lib.favoriteAll(tracks)
                                                env.toast("Added to Liked songs")
                                            }
                                            if (user != null) {
                                                MenuItem("Rename playlist") {
                                                    menu = false
                                                    promptText(env, "Rename playlist", initial = user.name, action = "Save") { name ->
                                                        if (name != null) lib.renamePlaylist(user, name)
                                                    }
                                                }
                                                MenuItem("Delete playlist") {
                                                    menu = false
                                                    showConfirmDialog(env, "Delete \"${user.name}\"?", "The songs stay in your library.", "Delete") {
                                                        lib.deletePlaylist(user)
                                                        env.nav.pop()
                                                    }
                                                }
                                            }
                                            HorizontalDivider(Modifier.padding(vertical = 7.5.dp), color = p.line)
                                            for (s in Sort.entries) {
                                                MenuItem(
                                                    when (s) {
                                                        Sort.original -> "Default order"
                                                        Sort.title -> "Sort by title"
                                                        Sort.artist -> "Sort by artist"
                                                        Sort.duration -> "Sort by length"
                                                    },
                                                    checked = sort == s,
                                                    showCheck = true,
                                                ) {
                                                    menu = false
                                                    sort = s
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            PillButton(
                                AppIcons.playCircle, "Play",
                                if (tracks.isEmpty()) null else { { c.playQueue(tracks, 0) } },
                                Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            PillButton(
                                AppIcons.shuffle, "Shuffle",
                                if (tracks.isEmpty()) null else { { c.playShuffled(tracks) } },
                                Modifier.weight(1f), filled = false,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (banner != null) item(key = "banner") { banner() }
                when {
                    loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = p.ink)
                        }
                    }
                    error != null -> item(key = "error") { EmptyState(AppIcons.offline, error) }
                    tracks.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            AppIcons.music,
                            if (q.isNotEmpty()) "No songs match \"$q\"."
                            else if (user != null) "This playlist is empty.\nUse ⋯ on any song and choose \"Add to playlist\"."
                            else "Nothing here yet.",
                        )
                    }
                    else -> {
                        val rowCount = tracks.size
                        items(
                            rowCount,
                            key = if (canReorder) { i -> tracks[i].id } else null,
                        ) { i ->
                            val t = tracks[i]
                            val dragging = canReorder && dragKey == t.id
                            val rowMod = if (canReorder) {
                                if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }
                                else Modifier
                            } else Modifier
                            Box(rowMod.let { if (dragging) it.background(p.bg) else it }) {
                                NumberedTrackRow(
                                    index = i,
                                    track = t,
                                    onTap = { c.playQueue(tracks, i) },
                                    onRemove = if (user != null) {
                                        { lib.removeFromPlaylist(user, t) }
                                    } else if (removableFiles && t.source == TrackSource.file) {
                                        { lib.removeFile(t) }
                                    } else null,
                                    removeLabel = if (user != null) "Remove from this playlist" else null,
                                    trailing = if (canReorder) {
                                        {
                                            Box(
                                                Modifier.padding(8.dp).size(24.dp).pointerInput(t.id) {
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            dragKey = t.id
                                                            dragOffset = 0f
                                                        },
                                                        onDragEnd = {
                                                            dragKey = null
                                                            dragOffset = 0f
                                                        },
                                                        onDragCancel = {
                                                            dragKey = null
                                                            dragOffset = 0f
                                                        },
                                                    ) { change, amount ->
                                                        change.consume()
                                                        dragOffset += amount.y
                                                        maybeSwap(t.id)
                                                    }
                                                },
                                                contentAlignment = Alignment.Center,
                                            ) { Icon(AppIcons.drag, size = 24.dp, tint = p.sub) }
                                        }
                                    } else null,
                                )
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }

        if (onRefresh == null) content()
        else PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    try {
                        onRefresh()
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { content() }
    }
}

@Composable
private fun MenuItem(label: String, checked: Boolean = false, showCheck: Boolean = false, onClick: () -> Unit) {
    val p = palette()
    DropdownMenuItem(
        text = { Text(label, color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
        onClick = onClick,
        leadingIcon = if (showCheck) {
            { Icon(AppIcons.check, size = 18.dp, tint = if (checked) p.ink else Color.Transparent) }
        } else null,
    )
}

/** Small outlined button from the collection header. */
@Composable
private fun OutlineIcon(icon: Ph, onTap: (() -> Unit)? = null, wide: Boolean = false) {
    val p = palette()
    val shape = RoundedCornerShape(if (wide) 13.dp else 8.dp)
    Box(
        Modifier.width(if (wide) 40.dp else 30.dp).height(26.dp).clip(RoundedCornerShape(8.dp))
            .let { if (onTap != null) it.clickable(onClick = onTap) else it }
            .border(1.4.dp, p.ink, shape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, size = 15.dp, tint = p.ink) }
}
