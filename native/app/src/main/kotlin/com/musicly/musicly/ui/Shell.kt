@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicly.musicly.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.PlayerStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lets screens switch tab (Home's "On repeat > See all" opens the Hotlist). */
object ShellTabs {
    var select: ((Int) -> Unit)? = null
    fun open(i: Int) {
        select?.invoke(i)
    }
}

private val TabItems = listOf(
    Triple(AppIcons.home, AppIcons.homeOn, "Home"),
    Triple(AppIcons.search, AppIcons.searchOn, "Search"),
    Triple(AppIcons.library, AppIcons.libraryOn, "Library"),
    Triple(AppIcons.hot, AppIcons.hotOn, "Hotlist"),
)

/** Lays out all children but only measures and places the one at [visible] (keeps the others' state). */
@Composable
private fun IndexedStack(visible: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, c ->
        layout(c.maxWidth, c.maxHeight) {
            measurables.getOrNull(visible)?.measure(c)?.place(0, 0)
        }
    }
}

@Composable
fun Shell() {
    val env = LocalEnv.current
    val p = palette()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val tabStacks = remember { Array(4) { emptyList<Page>() } }

    fun select(i: Int) {
        if (i == tab) {
            env.nav.popToRoot()
        } else {
            tabStacks[tab] = env.nav.stack.toList()
            env.nav.stack.clear()
            env.nav.stack.addAll(tabStacks[i])
            tab = i
        }
    }
    LaunchedEffect(Unit) { ShellTabs.select = { select(it) } }

    // Playback errors show as a toast.
    val playError = env.player.playError
    LaunchedEffect(playError) {
        if (playError != null) {
            env.toast(playError)
            env.player.playError = null
        }
    }

    BackHandler(
        enabled = tab != 0 && env.nav.stack.isEmpty() && env.sheet == null && env.dialog == null && !env.nowPlayingOpen,
    ) { select(0) }

    val density = LocalDensity.current
    var barHeightPx by remember { mutableIntStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imePad = with(density) { (imeBottom - barHeightPx).coerceAtLeast(0).toDp() }

    CompositionLocalProvider(LocalContentColor provides p.ink) {
        Box(Modifier.fillMaxSize().background(p.bg)) {
            Column(Modifier.fillMaxSize()) {
                // Page area: tabs, with opened pages on top.
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(bottom = imePad),
                ) {
                    IndexedStack(tab, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(p.bg)) { HomeScreen() }
                        Box(Modifier.fillMaxSize().background(p.bg)) { SearchScreen() }
                        Box(Modifier.fillMaxSize().background(p.bg)) { LibraryScreen() }
                        Box(Modifier.fillMaxSize().background(p.bg)) { HotlistScreen() }
                    }
                    val stack = env.nav.stack
                    IndexedStack(stack.size - 1, Modifier.fillMaxSize()) {
                        for (page in stack.toList()) {
                            key(page) { PageHost(page) }
                        }
                    }
                }
                // Mini player + tab bar.
                Column(Modifier.fillMaxWidth().background(p.bg).onSizeChanged { barHeightPx = it.height }) {
                    MiniPlayer()
                    NavBar(tab) { select(it) }
                }
            }

            // Now Playing slides over everything.
            AnimatedVisibility(
                visible = env.nowPlayingOpen,
                enter = slideInVertically(tween(300, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f))) { it },
                exit = slideOutVertically(tween(250)) { it },
            ) {
                Box(Modifier.fillMaxSize().background(p.bg).pointerInput(Unit) { detectTapGestures { } }) {
                    NowPlayingScreen(startOnLyrics = env.nowPlayingOnLyrics)
                }
            }

            ToastHost(bottomOffsetPx = if (env.nowPlayingOpen) 0 else barHeightPx)
        }

        // Bottom sheet host.
        val sheet = env.sheet
        if (sheet != null) {
            key(sheet) {
                val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val close = {
                    scope.launch { state.hide() }.invokeOnCompletion {
                        if (env.sheet === sheet) env.closeSheet()
                    }
                    Unit
                }
                ModalBottomSheet(
                    onDismissRequest = { if (env.sheet === sheet) env.closeSheet() },
                    sheetState = state,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    containerColor = p.bg,
                    contentColor = p.ink,
                    tonalElevation = 0.dp,
                    dragHandle = { BottomSheetDefaults.DragHandle(color = p.line) },
                ) {
                    sheet.content(close)
                }
            }
        }

        // Dialog host (the content emits its own AlertDialog).
        val dialog = env.dialog
        if (dialog != null) {
            key(dialog) {
                dialog.content { if (env.dialog === dialog) env.dialog = null }
            }
        }
    }
}

@Composable
private fun PageHost(page: Page) {
    val p = palette()
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(300, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f))) }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = enter.value
                translationX = (1f - enter.value) * size.width * 0.08f
            }
            .background(p.bg)
            .pointerInput(Unit) { detectTapGestures { } },
    ) { page.content() }
}

@Composable
private fun NavBar(tab: Int, onSelect: (Int) -> Unit) {
    val p = palette()
    Box(
        Modifier.fillMaxWidth()
            .shadow(
                10.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.05f),
            )
            .background(p.bg, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            TabItems.forEachIndexed { i, (off, on, label) ->
                val selected = i == tab
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 36.dp),
                            onClick = { onSelect(i) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement_Center,
                ) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                        label = "tabicon",
                    ) { on1 ->
                        Icon(if (on1) on else off, size = 24.dp, tint = if (selected) p.ink else p.sub)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label, fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) p.ink else p.sub,
                    )
                }
            }
        }
    }
}

private val Arrangement_Center = androidx.compose.foundation.layout.Arrangement.Center

@Composable
private fun MiniPlayer() {
    val env = LocalEnv.current
    val c = env.player
    val lib = env.library.observe()
    val p = palette()
    if (lib.playerStyle == PlayerStyle.minimal) {
        MinimalMiniPlayer(onOpen = { env.openNowPlaying() })
        return
    }
    val t = c.current
    Box(
        Modifier.fillMaxWidth().animateContentSize(tween(300, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f))),
    ) {
        if (t == null) return@Box
        val density = LocalDensity.current
        val fling = 300f * density.density
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(p.dock)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { env.openNowPlaying() }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { },
                    onDragStopped = { v -> if (v < -fling) env.openNowPlaying() },
                )
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { },
                    onDragStopped = { v ->
                        if (v < -fling) c.next()
                        if (v > fling) c.previous()
                    },
                ),
        ) {
            var pos by remember { mutableLongStateOf(0L) }
            LaunchedEffect(t.id) {
                while (true) {
                    pos = c.positionMs
                    delay(250)
                }
            }
            val total = c.durationMs
            val frac = if (total <= 0) 0f else (pos.toFloat() / total).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(p.onDock))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (t.isRadio) StationArt(t, size = 46.dp, radius = 23.dp) else Artwork(t, size = 46.dp, radius = 23.dp)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = t,
                        contentKey = { it.id },
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                        label = "minititle",
                    ) { tr ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                tr.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = p.onDock, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (tr.isRadio) "● LIVE  ${c.nowOnAir ?: tr.artist}" else tr.artist,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                            )
                        }
                    }
                }
                val fav = lib.isFavorite(t)
                AppIconButton(
                    if (fav) AppIcons.heartOn else AppIcons.heart,
                    onClick = { lib.toggleFavorite(t) },
                    tint = if (fav) Color(0xFFE5484D) else p.onDock,
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { c.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (c.isPlaying) AppIcons.pause else AppIcons.play, size = 20.dp, tint = Color(0xFF1C1D22))
                }
            }
        }
    }
}

/** Floating dark pill, like the Flutter SnackBar. Hides itself after ~2 s. */
@Composable
private fun ToastHost(bottomOffsetPx: Int) {
    val env = LocalEnv.current
    val p = palette()
    val msg = env.toastMessage
    var last by remember { mutableStateOf("") }
    if (msg != null) last = msg
    LaunchedEffect(msg) {
        if (msg != null) {
            delay(2000)
            if (env.toastMessage == msg) env.toastMessage = null
        }
    }
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize()
            .then(if (bottomOffsetPx == 0) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
            .padding(bottom = with(density) { bottomOffsetPx.toDp() } + 10.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 2 },
            exit = fadeOut(tween(150)),
        ) {
            Box(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.ink)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(last, color = p.onInk, fontSize = 14.sp)
            }
        }
    }
}
