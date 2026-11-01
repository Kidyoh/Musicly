@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicly.musicly.data.Library
import com.musicly.musicly.data.TelegramChannel
import com.musicly.musicly.net.AdminChannel
import com.musicly.musicly.net.TelegramBot
import com.musicly.musicly.net.TelegramError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val Red = Color(0xFFE5484D)
private val Green = Color(0xFF30A46C)

/** Outlives any page, so imports and connects carry on after you go back. */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * Your Telegram channels, read straight from Telegram with a bot token.
 * Without [channelId] it shows every channel's songs together (or the only
 * channel); with it, just that channel.
 */
@Composable
fun TelegramScreen(channelId: Long? = null) {
    val env = LocalEnv.current
    val lib = env.library.observe()
    if (!lib.channelConnected) {
        ConnectView(adding = false)
        return
    }
    val one = if (channelId != null) lib.channel(channelId)
    else if (lib.channels.size == 1) lib.channels.first()
    else null
    if (channelId != null && one == null) {
        // The channel was just removed.
        Box(Modifier.fillMaxSize().background(palette().bg))
        return
    }
    if (one != null) {
        val id = one.id
        key("tg-$id-${one.name}") {
            CollectionScreen(
                title = one.name,
                owner = "Your Telegram channel",
                kind = "Channel",
                live = { l -> l.channel(id)?.tracks?.toList() ?: emptyList() },
                onRefresh = { lib.syncTelegram() },
                actions = listOf({ ChannelMenuButton(id) }),
                banner = { ChannelBanner() },
            )
        }
        return
    }
    key("tg-all-${lib.channels.size}") {
        CollectionScreen(
            title = "Telegram",
            owner = "${lib.channels.size} channels",
            kind = "Channels",
            live = { l -> l.channelTracks },
            onRefresh = { lib.syncTelegram() },
            actions = listOf({ ChannelMenuButton(null) }),
            banner = { ChannelsStrip() },
        )
    }
}

/** Opens the guided setup for connecting one more channel. */
fun addTelegramChannel(env: Env) = openPage(env, "telegram:add") { ConnectView(adding = true) }

private fun openBot(env: Env, username: String?) {
    if (username == null) return
    env.openUrl?.invoke("https://t.me/$username?start=musicly")
}

// -- channel chips -------------------------------------------------------------

@Composable
private fun TgChip(icon: Ph, label: String, onClick: () -> Unit) {
    val p = palette()
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(p.card).clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, size = 16.dp, tint = p.ink)
        Spacer(Modifier.width(8.dp))
        Text(label, color = p.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
        Spacer(Modifier.width(4.dp))
    }
}

/** Every connected channel as a chip, plus "Add channel", above the songs. */
@Composable
private fun ChannelsStrip() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (c in lib.channels.toList()) {
                TgChip(AppIcons.telegram, "${c.name} · ${c.tracks.size}") {
                    openPage(env, "telegram:${c.id}") { TelegramScreen(channelId = c.id) }
                }
                Spacer(Modifier.width(8.dp))
            }
            TgChip(AppIcons.add, "Add channel") { addTelegramChannel(env) }
        }
        ChannelBanner()
    }
}

// -- banner --------------------------------------------------------------------

/** Import progress, errors, and how to add older songs. */
@Composable
private fun ChannelBanner() {
    val env = LocalEnv.current
    val lib = env.library.observe()
    val p = palette()

    @Composable
    fun Card(content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp),
        ) { content() }
    }

    val bot = lib.botUsername
    when {
        lib.downloading -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lib.downloadTotal == 0) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = p.ink, strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(
                        { lib.downloadDone.toFloat() / lib.downloadTotal }, Modifier.size(18.dp),
                        color = p.ink, strokeWidth = 2.dp, trackColor = Color.Transparent,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Saving songs to this phone… ${lib.downloadDone} of ${lib.downloadTotal}",
                    color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
            }
        }
        lib.downloadError != null -> Card {
            Text(lib.downloadError!!, color = p.sub, fontSize = 14.sp, lineHeight = 20.sp)
        }
        lib.importing -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = p.ink, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Importing older posts… checked ${lib.importScanned}, found ${lib.importFound} songs",
                    color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                TextBtn("Stop") { lib.cancelImport() }
            }
        }
        lib.backupNote != null -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.check, size = 18.dp, tint = p.ink)
                Spacer(Modifier.width(10.dp))
                Text(lib.backupNote!!, color = p.sub, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            }
        }
        !lib.backupReady -> Card {
            Column(Modifier.fillMaxWidth()) {
                Text("Keep your library safe", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open @$bot in Telegram and press Start. Musicly then keeps a backup of your likes, " +
                        "playlists and songs there, and brings it all back if you reinstall.",
                    color = p.sub, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    Box(
                        Modifier.height(40.dp).clip(RoundedCornerShape(50)).background(p.sub)
                            .clickable { openBot(env, bot) }.padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Open @$bot", color = p.onInk, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    val scope = rememberCoroutineScope()
                    TextBtn("I pressed Start") { scope.launch { lib.syncTelegram() } }
                }
            }
        }
        lib.channelError != null -> Card {
            Text(lib.channelError!!, color = Red, fontSize = 14.sp)
        }
        lib.channelTracks.isEmpty() -> Card {
            Column(Modifier.fillMaxWidth()) {
                Text("Connected! Waiting for songs", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "New audio posts appear here automatically. To add songs posted earlier, " +
                        "open the ⋯ menu and choose \"Import older songs\", or forward them to @$bot.",
                    color = p.sub, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
        }
        lib.tooBigSkipped > 0 -> Card {
            val n = lib.tooBigSkipped
            Text(
                "$n file${if (n == 1) " is" else "s are"} over 20 MB, " +
                    "which Telegram doesn't let bots stream, so ${if (n == 1) "it was" else "they were"} skipped.",
                color = p.sub, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun TextBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = palette()
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = p.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

// -- channel menu -----------------------------------------------------------------

@Composable
private fun ChannelMenuButton(channelId: Long?) {
    val env = LocalEnv.current
    AppIconButton(AppIcons.telegram, onClick = { showChannelMenu(env, channelId) })
}

private fun showChannelMenu(env: Env, channelId: Long?) {
    val libRaw = env.library
    env.showSheet { dismiss ->
        val p = palette()
        val lib = libRaw.observe()
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            SheetTile(
                if (channelId != null) (lib.channel(channelId)?.name ?: "Telegram") else (lib.channelName ?: "Telegram"),
                onClick = null,
                leading = { Icon(AppIcons.telegramOn, size = 24.dp, tint = p.ink) },
                sub = "Read by @${lib.botUsername ?: "your bot"}",
                titleWeight = FontWeight.Bold,
            )
            SheetDivider()
            if (lib.canDownload) {
                SheetTile(
                    "Save songs to this phone",
                    onClick = { lib.setAutoDownload(!lib.autoDownload) },
                    icon = AppIcons.download,
                    sub = if (lib.notDownloaded == 0) "All songs saved in Music/Musicly"
                    else "${lib.notDownloaded} not saved yet · plays offline once saved",
                    trailing = {
                        Switch(
                            lib.autoDownload, { lib.setAutoDownload(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = p.onInk, checkedTrackColor = p.ink,
                                uncheckedThumbColor = p.sub, uncheckedTrackColor = p.card, uncheckedBorderColor = p.line,
                            ),
                        )
                    },
                )
            }
            SheetTile("Check for new songs", icon = AppIcons.refresh, onClick = {
                dismiss()
                appScope.launch { lib.syncTelegram() }
            })
            SheetTile(
                "Import older songs", icon = AppIcons.history,
                sub = if (lib.canImport) "Reads the channel's earlier posts"
                else "First open @${lib.botUsername} in Telegram and press Start",
                onClick = {
                    dismiss()
                    appScope.launch {
                        if (!lib.canImport) lib.syncTelegram() // maybe they just pressed Start
                        if (lib.canImport) {
                            if (channelId != null) {
                                lib.channel(channelId)?.let { c -> appScope.launch { lib.importTelegramHistory(c) } }
                            } else if (lib.channels.size == 1) {
                                val c = lib.channels.first()
                                appScope.launch { lib.importTelegramHistory(c) }
                            } else {
                                pickChannel(env, lib) { c -> appScope.launch { lib.importTelegramHistory(c) } }
                            }
                        } else {
                            openBot(env, lib.botUsername)
                            env.toast("Press Start in the bot chat, then come back and import.")
                        }
                    }
                },
            )
            SheetTile(
                "Add another channel", icon = AppIcons.add, sub = "The same bot can read all your channels",
                onClick = {
                    dismiss()
                    addTelegramChannel(env)
                },
            )
            SheetTile(
                "Back up now", icon = AppIcons.backup, sub = backupLabel(lib),
                modifier = if (lib.backupReady) Modifier else Modifier.alpha(0.38f),
                onClick = if (!lib.backupReady) null else {
                    {
                        dismiss()
                        appScope.launch {
                            val ok = lib.backupNow()
                            env.toast(if (ok) "Library backed up to Telegram" else "Backup failed. Try again.")
                        }
                    }
                },
            )
            SheetTile(
                "Restore from backup", icon = AppIcons.restore,
                sub = "Replaces this phone's library with the saved one",
                modifier = if (lib.backupReady) Modifier else Modifier.alpha(0.38f),
                onClick = if (!lib.backupReady) null else {
                    {
                        dismiss()
                        appScope.launch {
                            val ok = lib.restoreFromTelegram()
                            env.toast(if (ok) "Library restored" else "No backup found yet.")
                        }
                    }
                },
            )
            if (channelId != null && lib.channels.size > 1) {
                SheetTile(
                    "Remove this channel", icon = AppIcons.trash, sub = "Songs saved on this phone stay",
                    onClick = {
                        dismiss()
                        env.nav.pop()
                        lib.removeChannel(channelId)
                    },
                )
            }
            SheetTile(
                if (lib.channels.size > 1) "Disconnect all channels" else "Disconnect channel",
                icon = AppIcons.close,
                onClick = {
                    dismiss()
                    lib.disconnectTelegram()
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun pickChannel(env: Env, lib: Library, onPick: (TelegramChannel) -> Unit) {
    env.showSheet { dismiss ->
        val p = palette()
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            SheetTile("Import older songs from…", onClick = null, titleWeight = FontWeight.Bold)
            for (c in lib.channels.toList()) {
                SheetTile(
                    c.name, icon = AppIcons.telegram, sub = count(c.tracks.size, "song"),
                    onClick = {
                        dismiss()
                        onPick(c)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun backupLabel(lib: Library): String {
    if (!lib.backupReady) return "Press Start in your bot first"
    if (lib.backingUp) return "Backing up…"
    val at = lib.lastBackup?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "Not backed up yet"
    val mins = Duration.between(at, Instant.now()).toMinutes()
    if (mins < 1) return "Last backup: just now"
    if (mins < 60) return "Last backup: $mins min ago"
    if (mins < 60 * 24) return "Last backup: ${mins / 60} h ago"
    return "Last backup: ${at.atZone(ZoneId.systemDefault()).toLocalDate()}"
}

// -- guided setup ----------------------------------------------------------------------

private val tokenShape = Regex("^\\d+:[\\w-]{20,}$")
private val tokenFind = Regex("\\d+:[\\w-]{20,}")

/**
 * Guided setup for people who have never made a bot: open BotFather, paste
 * the token, tap one link to add the bot to a channel, and the channel
 * shows up here by itself.
 *
 * [adding]: connecting one more channel with the bot that's already set up.
 */
@Composable
private fun ConnectView(adding: Boolean) {
    val env = LocalEnv.current
    val lib = env.library
    val p = palette()
    val clipboard = LocalClipboardManager.current

    var token by remember { mutableStateOf(lib.bot?.token ?: "") }
    var channelText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var typeChannel by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var botName by remember { mutableStateOf<String?>(null) }
    var checkedToken by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<AdminChannel>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val clean = token.trim().replace(Regex("\\s"), "")

    // Checks the token once typing pauses, then watches for the bot being added to a channel.
    LaunchedEffect(clean) {
        if (clean == checkedToken) return@LaunchedEffect
        botName = null
        channels = emptyList()
        tokenError = null
        if (!tokenShape.matches(clean)) return@LaunchedEffect
        delay(400)
        checking = true
        var name: String? = null
        var err: String? = null
        try {
            name = TelegramBot(clean).getMe()["username"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TelegramError) {
            err = if (e.code == 401 || e.code == 404) "That token isn't right. Copy it again from BotFather." else e.description
        } catch (_: Exception) {
            err = "Could not reach Telegram. Check your connection."
        }
        checking = false
        checkedToken = clean
        botName = name
        tokenError = err
        if (name != null) {
            while (true) {
                try {
                    val found = TelegramBot(clean).adminChannels().filter { lib.channel(it.id) == null }
                    if (found.map { it.id } != channels.map { it.id }) channels = found
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                delay(3000)
            }
        }
    }

    fun connect(channel: String) {
        if (botName == null) {
            error = "Paste your bot token first."
            return
        }
        if (channel.trim().isEmpty()) {
            error = "Enter your channel."
            return
        }
        busy = true
        error = null
        val tk = checkedToken
        appScope.launch {
            val err = lib.connectTelegram(tk, channel)
            busy = false
            error = err
            if (err == null) {
                env.toast("Connected to ${lib.channels.last().name}")
                if (adding) env.nav.pop()
            }
        }
    }

    val botReady = botName != null

    @Composable
    fun Step(n: Int, title: String, text: String, ok: Boolean = false) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(if (ok) Green else p.ink),
                contentAlignment = Alignment.Center,
            ) {
                if (ok) Icon(AppIcons.check, size = 15.dp, tint = Color.White)
                else Text("$n", color = p.onInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(text, color = p.sub, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }

    @Composable
    fun Indent(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxWidth().padding(start = 38.dp, bottom = 24.dp)) { content() }
    }

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
            AppIconButton(AppIcons.back, onClick = { env.nav.pop() })
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp).navigationBarsPadding(),
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(p.ink),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.telegramOn, size = 36.dp, tint = p.onInk) }
            Spacer(Modifier.height(18.dp))
            Text(
                if (adding) "Add another channel" else "Your Telegram channel",
                color = p.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (adding) "Your bot is ready. Add it to another channel you run and pick it below."
                else "Play the full songs from any channel you run. Takes about a minute — just follow the three steps.",
                color = p.sub, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(28.dp))

            // 1. Make a bot
            Step(
                1, "Make your music bot",
                "Tap the button, press Start, send /newbot, then pick any name and a username ending in \"bot\". BotFather replies with a long token — copy it.",
                ok = botReady,
            )
            Indent {
                PillButton(
                    AppIcons.external, "Open BotFather", { env.openUrl?.invoke("https://t.me/BotFather") },
                    filled = !botReady,
                )
            }

            // 2. Paste the token
            Step(2, "Paste the token", "Come back here and paste what BotFather sent.", ok = botReady)
            Indent {
                Column(Modifier.fillMaxWidth()) {
                    BasicTextField(
                        token, { token = it }, Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = TextStyle(color = p.ink, fontSize = 16.sp, fontFamily = Inter),
                        cursorBrush = SolidColor(p.ink),
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false, imeAction = ImeAction.Done,
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        decorationBox = { inner ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                                    .background(p.card).padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(AppIcons.key, size = 20.dp, tint = p.sub)
                                Spacer(Modifier.width(12.dp))
                                Box(Modifier.weight(1f)) {
                                    if (token.isEmpty()) Text("123456:ABC-…", color = p.sub, fontSize = 16.sp)
                                    inner()
                                }
                                AppIconButton(
                                    if (showToken) AppIcons.eyeOff else AppIcons.eye,
                                    onClick = { showToken = !showToken }, iconSize = 18.dp, tint = p.sub,
                                )
                                TextBtn("Paste") {
                                    val text = clipboard.getText()?.text ?: ""
                                    token = tokenFind.find(text)?.value ?: text.trim()
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (checking) {
                        Text("Checking…", color = p.sub, fontSize = 14.sp)
                    } else if (tokenError != null) {
                        Text(tokenError!!, color = Red, fontSize = 14.sp)
                    } else if (botReady) {
                        Text("Bot ready: @$botName", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }

            // 3. Add it to the channel
            Step(
                3, "Add it to your channel",
                "Tap the button, choose your channel and confirm. Your channel appears below by itself.",
                ok = channels.isNotEmpty(),
            )
            Indent {
                Column(Modifier.fillMaxWidth()) {
                    PillButton(
                        AppIcons.add, "Add bot to my channel",
                        if (botReady) {
                            {
                                env.openUrl?.invoke(
                                    "https://t.me/$botName?startchannel&admin=post_messages+edit_messages+delete_messages",
                                )
                            }
                        } else null,
                        Modifier.fillMaxWidth(),
                    )
                    if (botReady && channels.isEmpty() && !typeChannel) {
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = p.ink, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Waiting for your channel…", color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    for (c in channels) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(p.card)
                                .clickable(enabled = !busy) { connect("${c.id}") }
                                .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.telegram, size = 24.dp, tint = p.ink)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(if (busy) "Connecting…" else "Tap to connect", color = p.sub, fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(16.dp))
                            Icon(AppIcons.chevron, size = 18.dp, tint = p.ink)
                        }
                    }
                    if (botReady) {
                        Box(Modifier.align(Alignment.Start)) {
                            TextBtn(if (typeChannel) "Hide" else "Already added it? Type the channel instead") {
                                typeChannel = !typeChannel
                            }
                        }
                    }
                    if (typeChannel) {
                        AppTextField(
                            channelText, { channelText = it }, "Channel: @name or t.me link",
                            leading = { Icon(AppIcons.link, size = 20.dp, tint = p.sub) },
                            onSubmit = { connect(channelText) },
                        )
                        Spacer(Modifier.height(12.dp))
                        PillButton(
                            AppIcons.telegram, if (busy) "Connecting…" else "Connect channel",
                            if (busy) null else { { connect(channelText) } },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    if (error != null) {
                        Text(error!!, color = Red, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp)) {
                Text(
                    "Reinstalled Musicly? Paste the same token and pick the same channel. " +
                        "Your likes, playlists and channel songs come back automatically.",
                    color = p.sub, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Bots can play songs up to 20 MB, which covers normal MP3 and M4A files. Your token stays on this phone.",
                color = p.sub, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}
