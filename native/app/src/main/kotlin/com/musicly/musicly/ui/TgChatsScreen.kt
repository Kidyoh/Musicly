@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.tg.TgAuth
import com.musicly.musicly.tg.TgChat
import com.musicly.musicly.tg.TgChatKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Red = Color(0xFFE5484D)

/**
 * Channels and groups of the user's own Telegram account. Each switch adds one
 * to what Musicly reads; picked chats are scanned gently in the background.
 */
@Composable
fun TgChatsScreen() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val user = lib.tgUser
    val tg = env.tgb
    val p = palette()
    val auth by tg.auth.collectAsState()
    val scope = rememberCoroutineScope()

    var chats by remember { mutableStateOf<List<TgChat>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val ready = auth is TgAuth.Ready

    LaunchedEffect(reload, ready) {
        if (!ready) return@LaunchedEffect
        loadError = null
        try {
            val list = tg.chats()
            chats = list
            user.syncNames(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Couldn't load your chats. ${e.message ?: "Check your connection."}"
        }
    }

    fun showMenu() {
        env.showSheet { dismiss ->
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                SheetTile(
                    "Your Telegram account", onClick = null, titleWeight = FontWeight.Bold,
                    leading = { Icon(AppIcons.telegramOn, size = 24.dp, tint = palette().ink) },
                    sub = user.accountName?.let { "Logged in as $it" },
                )
                SheetDivider()
                SheetTile("Refresh chats", icon = AppIcons.refresh, sub = "Also looks for new songs in the chats you picked", onClick = {
                    dismiss()
                    reload++
                    user.refreshAll()
                })
                SheetTile("Log out", icon = AppIcons.leave, sub = "Forgets your login and the chats you picked on this phone", onClick = {
                    dismiss()
                    showConfirmDialog(
                        env, "Log out of Telegram?",
                        "Musicly forgets your login and the channels you picked. Your Telegram account and its chats aren't touched.",
                        "Log out",
                    ) {
                        scope.launch {
                            user.logOut()
                            env.nav.pop()
                        }
                    }
                })
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    fun showChatMenu(c: TgChat) {
        env.showSheet { dismiss ->
            val l = env.library.observe()
            val u = l.tgUser
            val have = u.chat(c.id)?.tracks?.size ?: 0
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                SheetTile(
                    c.title, onClick = null, titleWeight = FontWeight.Bold,
                    leading = { Avatar(c, 40.dp) },
                    sub = "${count(have, "song")} found",
                )
                SheetDivider()
                SheetTile(
                    "Load older songs",
                    icon = AppIcons.history,
                    sub = if (u.isDone(c.id)) "Every song in this chat is already here" else "Reads up to 300 more, one at a time",
                    modifier = if (u.isDone(c.id)) Modifier.alpha(0.38f) else Modifier,
                    onClick = if (u.isDone(c.id)) null else {
                        {
                            dismiss()
                            u.loadOlder(c.id)
                        }
                    },
                )
                SheetTile("Look for new songs", icon = AppIcons.refresh, onClick = {
                    dismiss()
                    u.refreshAll()
                })
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        PageBar(onBack = { env.nav.pop() }, center = "Your Telegram account") {
            if (ready) AppIconButton(AppIcons.more, onClick = ::showMenu)
        }
        when {
            !ready -> EmptyState(
                AppIcons.telegram, "You're not logged in to Telegram.",
                Modifier.weight(1f),
                action = {
                    PillButton(AppIcons.telegramOn, "Log in", {
                        env.nav.pop()
                        openPage(env, "tg-login") { TgLoginScreen() }
                    })
                },
            )
            chats == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (loadError != null) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(loadError!!, color = Red, fontSize = 14.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(16.dp))
                        PillButton(AppIcons.refresh, "Try again", { reload++ })
                    }
                } else CircularProgressIndicator(Modifier.size(24.dp), color = p.ink, strokeWidth = 2.dp)
            }
            else -> {
                val all = chats!!
                val shown = if (query.isBlank()) all else all.filter { it.title.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
                            Text(
                                "Channels and groups", color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Switch on the ones with music. Musicly reads their songs so you can play them here. " +
                                    "It never posts, joins or leaves anything.",
                                color = p.sub, fontSize = 14.sp, lineHeight = 20.sp,
                            )
                        }
                    }
                    if (user.error != null) {
                        item {
                            Text(
                                user.error!!, color = Red, fontSize = 14.sp, lineHeight = 20.sp,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                            )
                        }
                    }
                    if (all.size > 12) {
                        item {
                            Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                                AppTextField(
                                    query, { query = it }, "Search your chats",
                                    leading = { Icon(AppIcons.search, size = 20.dp, tint = p.sub) },
                                )
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                if (all.isEmpty()) "No channels or groups found in this account." else "No chats match \"${query.trim()}\".",
                                color = p.sub, fontSize = 14.sp, modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    items(shown, key = { it.id }) { c ->
                        ChatRow(c, onMore = { showChatMenu(c) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(c: TgChat, onMore: () -> Unit) {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val user = lib.tgUser
    val p = palette()
    val picked = user.isPicked(c.id)
    val songs = user.chat(c.id)?.tracks?.size ?: 0
    val kind = if (c.kind == TgChatKind.Channel) "Channel" else "Group"
    val members = c.members
    val sub = if (members != null) kind + " \u00b7 " + compact(members) + (if (members == 1) " member" else " members") else kind

    val status: String? = when {
        !picked -> null
        user.reading == c.id -> "Reading songs… ${user.readingCount.coerceAtLeast(songs)}"
        c.id in user.queued -> "Waiting…"
        songs == 0 && user.isDone(c.id) -> "No songs found"
        else -> count(songs, "song")
    }

    Row(
        Modifier.fillMaxWidth().clickable { user.setPicked(c, !picked) }.heightIn(min = 72.dp)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(sub, color = p.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (user.reading == c.id) {
                        CircularProgressIndicator(Modifier.size(11.dp), color = p.ink, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(status, color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        if (picked) {
            AppIconButton(AppIcons.more, onClick = onMore, tint = p.sub, iconSize = 20.dp, size = 40.dp)
        }
        Switch(
            picked, { user.setPicked(c, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onInk, checkedTrackColor = p.ink,
                uncheckedThumbColor = p.sub, uncheckedTrackColor = p.card, uncheckedBorderColor = p.line,
            ),
        )
        Spacer(Modifier.width(8.dp))
    }
}

/** The chat's round picture, or its first letter on a soft circle. */
@Composable
private fun Avatar(c: TgChat, size: androidx.compose.ui.unit.Dp) {
    val env = LocalEnv.current
    val p = palette()
    val bmp by produceState<ImageBitmap?>(null, c.id, c.photoPath) {
        value = try {
            val path = c.photoPath ?: env.tgb.chatPhoto(c.id)
            if (path == null) null else withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    Box(Modifier.size(size).clip(CircleShape).background(p.card), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                c.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                color = p.sub, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp,
            )
        }
    }
}
