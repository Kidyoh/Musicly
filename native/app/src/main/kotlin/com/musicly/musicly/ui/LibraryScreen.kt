@file:OptIn(androidx.media3.common.util.UnstableApi::class)

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.HomeWidgets
import com.musicly.musicly.data.Library
import com.musicly.musicly.data.PlayerStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val libScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private val minuteFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun localTime(iso: String?): LocalDateTime? =
    iso?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() }

private fun backupSummary(lib: Library): String {
    val at = listOfNotNull(localTime(lib.lastLocalBackup), localTime(lib.lastBackup)).maxOrNull()
        ?: return "Saved to this phone and Telegram automatically"
    val m = Duration.between(at, LocalDateTime.now()).toMinutes()
    return "Last saved " + when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 1440 -> "${m / 60} h ago"
        else -> at.format(dayFmt)
    }
}

private fun restoreFile(env: Env) {
    libScope.launch {
        val text = env.pickBackupFile?.invoke()
        val err = if (text == null) "No file chosen." else env.library.restoreFromText(text)
        env.toast(err ?: "Library restored")
    }
}

private fun newPlaylist(env: Env) {
    promptText(env, "New playlist") { name ->
        if (name != null) openUserPlaylist(env, env.library.createPlaylist(name))
    }
}

private fun scan(env: Env) {
    libScope.launch {
        val lib = env.library
        val ok = lib.scanDevice()
        env.toast(if (ok) "Found ${lib.deviceTracks.size} songs on this phone" else (lib.scanError ?: "Scan failed"))
    }
}

private fun pickFiles(env: Env) {
    libScope.launch {
        val picked = env.pickAudioFiles?.invoke() ?: return@launch
        val n = env.library.addPickedFiles(picked)
        if (n > 0) env.toast("Added $n song${if (n == 1) "" else "s"}")
    }
}

private fun showBackupSheet(env: Env) {
    val lib = env.library
    env.showSheet { dismiss ->
        val p = palette()
        lib.observe()
        fun whenText(iso: String?): String = localTime(iso)?.format(minuteFmt) ?: "Not yet"
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                Text("Backup & restore", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Musicly saves your likes, playlists, history and settings automatically: to " +
                        "Download › Musicly on this phone (kept even if you uninstall) and, once connected, " +
                        "to your Telegram bot chat.",
                    color = p.sub, fontSize = 14.sp, lineHeight = 20.3.sp,
                )
            }
            SheetTile(
                "On this phone", onClick = null, icon = AppIcons.phone, titleWeight = FontWeight.Normal,
                sub = if (env.device.supported) "Last saved: ${whenText(lib.lastLocalBackup)}" else "Android only",
            )
            SheetTile(
                "In Telegram", onClick = null, icon = AppIcons.telegram, titleWeight = FontWeight.Normal,
                sub = if (lib.backupReady) "Last saved: ${whenText(lib.lastBackup)}" else "Connect your channel and press Start in your bot",
            )
            SheetDivider(16.dp)
            SheetTile("Back up now", icon = AppIcons.backup, titleWeight = FontWeight.Normal, onClick = {
                dismiss()
                libScope.launch {
                    val ok = lib.backupNow()
                    env.toast(if (ok) "Library backed up" else "Backup failed. Try again.")
                }
            })
            if (env.device.supported) {
                SheetTile(
                    "Restore from a backup file", icon = AppIcons.folder, titleWeight = FontWeight.Normal,
                    sub = "Download › Musicly › musicly-backup.json",
                    onClick = {
                        dismiss()
                        restoreFile(env)
                    },
                )
            }
            if (lib.backupReady) {
                SheetTile("Restore from Telegram", icon = AppIcons.restore, titleWeight = FontWeight.Normal, onClick = {
                    dismiss()
                    libScope.launch {
                        val ok = lib.restoreFromTelegram()
                        env.toast(if (ok) "Library restored" else "No backup found yet.")
                    }
                })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun showWidgetsSheet(env: Env) {
    val canPin = HomeWidgets.canPin(env.app)
    env.showSheet { dismiss ->
        val p = palette()
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
            Text("Home-screen widgets", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                if (canPin) "Pick one to place on your home screen. They show the cover and control playback without opening the app."
                else "Long-press an empty spot on your home screen, tap Widgets, and find Musicly.",
                color = p.sub, fontSize = 14.sp, lineHeight = 20.3.sp,
            )
            if (canPin) {
                Spacer(Modifier.height(12.dp))
                SheetTile(
                    "Now playing", icon = AppIcons.music, sub = "4×2 · cover, song and controls",
                    titleWeight = FontWeight.SemiBold, horizontalPadding = 0.dp,
                    onClick = {
                        dismiss()
                        HomeWidgets.pin(env.app)
                    },
                )
                SheetTile(
                    "Mini player", icon = AppIcons.disc, sub = "2×2 · cover with a play button",
                    titleWeight = FontWeight.SemiBold, horizontalPadding = 0.dp,
                    onClick = {
                        dismiss()
                        HomeWidgets.pin(env.app, mini = true)
                    },
                )
            }
            SheetDivider(28.dp)
            SheetTile(
                "Player on the lock screen", icon = AppIcons.bell,
                sub = "Not showing? Turn on \"Lock screen\" for Musicly notifications.",
                titleWeight = FontWeight.SemiBold, horizontalPadding = 0.dp,
                onClick = {
                    dismiss()
                    env.device.openNotificationSettings()
                },
            )
        }
    }
}

@Composable
fun LibraryScreen() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val p = palette()
    val phone = env.device.supported

    @Composable
    fun Square(icon: Ph, dark: Boolean = false) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(if (dark) p.ink else p.card),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, size = 24.dp, tint = if (dark) p.onInk else p.ink) }
    }

    @Composable
    fun Tile(leading: @Composable () -> Unit, title: String, sub: String, onTap: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = p.ink)
                Spacer(Modifier.height(3.dp))
                Text(sub, color = p.sub, fontSize = 13.sp)
            }
            Icon(AppIcons.chevron, size = 18.dp, tint = p.sub)
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", Modifier.weight(1f), fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, color = p.ink)
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(p.ink).clickable { newPlaylist(env) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(AppIcons.add, size = 20.dp, tint = p.onInk) }
                    }
                }
            }
            if (lib.showRestoreCard && phone) {
                item {
                    Box(Modifier.padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 12.dp)) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.restore, size = 24.dp, tint = p.ink)
                                Spacer(Modifier.width(10.dp))
                                Text("Reinstalled? Restore your library", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.ink)
                                AppIconButton(AppIcons.close, { lib.dismissRestore() }, tint = p.sub, iconSize = 18.dp)
                            }
                            Text(
                                "Pick musicly-backup.json from Download › Musicly to bring back your likes, playlists and history.",
                                color = p.sub, fontSize = 14.sp, lineHeight = 20.3.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row { PillButton(AppIcons.folder, "Choose backup file", { restoreFile(env) }) }
                        }
                    }
                }
            }
            item {
                Tile({ Square(AppIcons.heartOn, dark = true) }, "Liked songs", count(lib.likedSongs.size, "song")) {
                    openLive(env, "Liked songs", "You", { it.likedSongs })
                }
            }
            item {
                Tile(
                    { Square(AppIcons.telegram) },
                    if (lib.channelConnected) (lib.channelName ?: "Telegram") else "Your Telegram channel",
                    if (lib.channelConnected) {
                        if (lib.channels.size > 1) "${count(lib.channels.size, "channel")} • ${count(lib.channelTracks.size, "song")}"
                        else "${count(lib.channelTracks.size, "song")} • full length"
                    } else "Stream songs from your own channel",
                ) { openPage(env, "telegram") { TelegramScreen() } }
            }
            if (lib.channels.size > 1) {
                items(lib.channels.toList()) { ch ->
                    Tile({ Square(AppIcons.telegram) }, ch.name, "${count(ch.tracks.size, "song")} • Telegram channel") {
                        openPage(env, "telegram:${ch.id}") { TelegramScreen(channelId = ch.id) }
                    }
                }
            }
            item {
                Tile(
                    { Square(AppIcons.radio) }, "Live radio",
                    if (lib.savedStations.isEmpty()) "Stations from around the world" else "${lib.savedStations.size} saved stations",
                ) { openPage(env, "radio") { RadioScreen() } }
            }
            if (lib.localTracks.isNotEmpty()) {
                item {
                    Tile({ Square(AppIcons.phone) }, "On this phone", "${count(lib.localTracks.size, "song")} • full length") {
                        openLive(env, "On this phone", "Your music", { it.localTracks }, removableFiles = true)
                    }
                }
            }
            item {
                Tile({ Square(AppIcons.history) }, "Recently played", count(lib.recent.size, "song")) {
                    openLive(env, "Recently played", "You", { it.recent })
                }
            }
            item {
                Tile(
                    { Square(AppIcons.broadcast) }, "RSS feeds",
                    if (lib.feeds.isEmpty()) "Stream songs from any RSS feed" else count(lib.feeds.size, "feed"),
                ) { openPage(env, "rss") { RssScreen() } }
            }
            item {
                Tile(
                    { Square(AppIcons.disc) }, "Player style",
                    if (lib.playerStyle == PlayerStyle.minimal) "Minimal · a turning disc and click-wheel" else "Classic · cover art and waveform",
                ) { showPlayerStyle(env) }
            }
            item {
                Tile({ Square(AppIcons.backup) }, "Backup & restore", backupSummary(lib)) { showBackupSheet(env) }
            }
            // HomeWidgets.supported is always true on Android.
            item {
                Tile({ Square(AppIcons.sparkle) }, "Home-screen widgets", "Now playing and mini player") { showWidgetsSheet(env) }
            }

            // Playlists
            item { SectionHeader("Your playlists", action = "New", onAction = { newPlaylist(env) }) }
            if (lib.playlists.isEmpty()) {
                item {
                    Text(
                        "Make your first playlist with the + button, or use ⋯ on any song.",
                        Modifier.padding(horizontal = 20.dp), color = p.sub, fontSize = 14.sp, lineHeight = 21.sp,
                    )
                }
            } else {
                items(lib.playlists.toList()) { pl ->
                    Tile({ Mosaic(pl.tracks.toList(), size = 56.dp) }, pl.name, count(pl.tracks.size, "song")) {
                        openUserPlaylist(env, pl)
                    }
                }
            }

            // Artists from your own music
            val artists = lib.artists
            if (artists.isNotEmpty()) {
                item { SectionHeader("Your artists") }
                item {
                    HRow(148.dp) {
                        items(artists.take(20)) { (name, songs) ->
                            ArtistBubble(
                                name = name,
                                cover = songs.firstOrNull { it.artworkUrl != null || it.mediaId != null } ?: songs.first(),
                                subtitle = count(songs.size, "song"),
                                onTap = { openArtist(env, name) },
                            )
                        }
                    }
                }
            }

            // Phone music
            if (phone && lib.deviceTracks.isNotEmpty()) {
                item {
                    SectionHeader(
                        "Albums on this phone",
                        action = if (lib.scanning) null else "Rescan",
                        onAction = { scan(env) },
                    )
                }
                item {
                    HRow(206.dp) {
                        items(lib.deviceAlbums) { (album, artist, tracks) ->
                            CoverCard(
                                title = album,
                                subtitle = artist,
                                art = { Artwork(tracks.first(), size = 148.dp, radius = 16.dp) },
                                onTap = {
                                    openLive(
                                        env, album, artist,
                                        { l -> l.deviceAlbums.firstOrNull { it.first == album && it.second == artist }?.third ?: emptyList() },
                                        kind = "Album", cover = tracks.first(),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (lib.deviceTracks.isEmpty()) {
                item {
                    Box(Modifier.padding(start = 20.dp, top = 28.dp, end = 20.dp)) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.headphones, size = 24.dp, tint = p.ink)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (phone) "Play full songs from your phone" else "Bring your own music",
                                    fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.ink,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (phone) "Musicly can find the songs already on this phone and play them in full, with album art and synced lyrics."
                                else "Add MP3, M4A, FLAC or WAV files. Name them like \"Artist - Title.mp3\" and Musicly will find synced lyrics too.",
                                color = p.sub, fontSize = 14.sp, lineHeight = 21.sp,
                            )
                            Spacer(Modifier.height(14.dp))
                            Row {
                                if (phone) {
                                    PillButton(
                                        AppIcons.phone, if (lib.scanning) "Scanning…" else "Find my music",
                                        if (lib.scanning) null else { { scan(env) } },
                                        Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                PillButton(AppIcons.folder, "Pick files", { pickFiles(env) }, Modifier.weight(1f), filled = !phone)
                            }
                        }
                    }
                }
            }
        }
    }
}
