@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.musicly.musicly.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.musicly.musicly.data.Track
import com.musicly.musicly.jam.JamTrack
import com.musicly.musicly.jam.JamRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Red = Color(0xFFE5484D)

/** Outlives the page, so joining or hosting isn't cancelled when the screen switches view. */
private val jamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

// -- small building blocks (Flutter Text / Chip / buttons look) ------------------------------

@Composable
private fun T(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = palette().ink,
    size: Int = 14,
    weight: FontWeight = FontWeight.Normal,
    height: Float? = null,
    spacing: Float? = null,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    Text(
        text, modifier, color = color, fontSize = size.sp, fontWeight = weight,
        lineHeight = if (height != null) (size * height).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        letterSpacing = if (spacing != null) spacing.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align,
    )
}

@Composable
private fun Title(text: String) = T(text, size = 28, weight = FontWeight.Bold, spacing = -0.6f)

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette().card).padding(16.dp)) { content() }
}

@Composable
private fun Spinner(size: Dp, stroke: Dp = 2.dp) {
    CircularProgressIndicator(Modifier.size(size), color = palette().ink, strokeWidth = stroke)
}

/** Flutter FilledButton. */
@Composable
private fun FilledBtn(label: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = palette()
    val on = onClick != null
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) p.ink else p.ink.copy(alpha = 0.12f))
            .clickable(enabled = on) { onClick?.invoke() }.padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) { T(label, color = if (on) p.onInk else p.onInk.copy(alpha = 0.38f), weight = FontWeight.Medium, maxLines = 1) }
}

/** Flutter TextButton (optionally with an icon). */
@Composable
private fun TextBtn(label: String, onClick: () -> Unit, icon: Ph? = null) {
    val p = palette()
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
            .padding(start = if (icon != null) 12.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, size = 16.dp, tint = p.ink)
            Spacer(Modifier.width(8.dp))
        }
        T(label, weight = FontWeight.Medium, maxLines = 1)
    }
}

/** Flutter OutlinedButton.icon, full width and 48 tall. */
@Composable
private fun OutlinedBtn(icon: Ph, label: String, onClick: () -> Unit, color: Color? = null) {
    val p = palette()
    val fg = color ?: p.ink
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, color ?: p.line), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, size = 18.dp, tint = fg)
        Spacer(Modifier.width(8.dp))
        T(label, color = fg, weight = FontWeight.Medium, maxLines = 1)
    }
}

/** Flutter Chip / InputChip. */
@Composable
private fun PersonChip(label: String, crown: Boolean = false, onDelete: (() -> Unit)? = null) {
    val p = palette()
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(p.card)
            .padding(start = if (crown) 8.dp else 8.dp, end = if (onDelete != null) 8.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (crown) {
            Icon(AppIcons.crown, size = 16.dp, tint = p.ink)
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
        T(label, weight = FontWeight.Medium, maxLines = 1)
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                Icon(AppIcons.close, size = 18.dp, tint = p.ink)
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** Flutter SwitchListTile (no side padding). */
@Composable
private fun SwitchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit, secondary: Ph? = null) {
    val p = palette()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { onChange(!value) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secondary != null) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) { Icon(secondary, size = 24.dp, tint = p.ink) }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            T(title, size = 16)
            T(sub, color = p.sub)
        }
        Spacer(Modifier.width(16.dp))
        Switch(value, onChange)
    }
}

@Composable
private fun EventPill(event: String?) {
    if (event == null) return
    val p = palette()
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.sparkle, size = 16.dp, tint = p.sub)
        Spacer(Modifier.width(8.dp))
        T(event, Modifier.weight(1f), color = p.sub, maxLines = 1)
    }
}

/** A progress or problem line, with "Try again" when something failed. */
@Composable
private fun Status(text: String, failed: Boolean = false, onRetry: (() -> Unit)? = null) {
    val p = palette()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (failed) Icon(AppIcons.close, size = 18.dp, tint = Red) else Spinner(16.dp)
            Spacer(Modifier.width(12.dp))
            T(text, Modifier.weight(1f), color = if (failed) Red else p.sub, height = 1.35f)
            if (failed && onRetry != null) TextBtn("Try again", onRetry)
        }
    }
}

// -- name -------------------------------------------------------------------------------------

private suspend fun askName(env: Env, current: String): String? =
    env.promptText("Your name in the Jam", current, action = "Save", hint = "e.g. Abel")

/** Makes sure friends will see a name; false if the user backed out. */
private suspend fun ensureName(env: Env): Boolean {
    val jam = env.jam
    if (jam.hasName) return true
    val name = askName(env, "")
    if (name == null || name.trim().isEmpty()) return false
    jam.setName(name)
    return true
}

// -- screen -----------------------------------------------------------------------------------

/** Listen together: start a Jam (in this room or online), or join one. */
@Composable
fun JamScreen() {
    val env = LocalEnv.current
    val jam = env.jam
    val p = palette()
    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
            AppIconButton(AppIcons.back, onClick = { env.nav.pop() })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (jam.role) {
                JamRole.none -> StartView()
                JamRole.host -> HostingView()
                JamRole.guest -> JoinedView()
            }
        }
    }
}

@Composable
private fun JamPage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp).navigationBarsPadding(),
    ) { content() }
}

// ---- Not in a Jam ----------------------------------------------------------------------------

/** "In this room" or "Online"; nothing happens if the sheet is dismissed. */
private fun pickMode(env: Env, onPick: (online: Boolean) -> Unit) {
    env.showSheet { dismiss ->
        val p = palette()
        Column(Modifier.navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
            SheetTile(
                "Where are your friends?", null, titleWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), horizontalPadding = 16.dp,
            )
            SheetTile(
                "In this room", onClick = { dismiss(); onPick(false) }, icon = AppIcons.wifi,
                sub = "Same Wi-Fi or your hotspot. Music plays from your phone; no internet needed.",
                titleWeight = FontWeight.SemiBold, horizontalPadding = 16.dp,
            )
            SheetTile(
                "Online — anywhere", onClick = { dismiss(); onPick(true) }, icon = AppIcons.broadcast,
                sub = "Friends hear your songs on their own phones, in step. Uses internet data.",
                titleWeight = FontWeight.SemiBold, horizontalPadding = 16.dp,
            )
        }
    }
}

@Composable
private fun StartView() {
    val env = LocalEnv.current
    val jam = env.jam
    val p = palette()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        jam.startLooking()
        onDispose { jam.stopLooking() }
    }
    LaunchedEffect(Unit) {
        val err = jam.error
        if (err != null) {
            env.toast(err)
            jam.error = null
        }
    }

    fun run(action: suspend () -> String?) {
        jamScope.launch {
            if (!ensureName(env)) return@launch
            busy = true
            val err = action()
            busy = false
            if (err != null) env.toast(err)
        }
    }

    JamPage {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(p.ink), contentAlignment = Alignment.Center) {
            Icon(AppIcons.jamOn, size = 36.dp, tint = p.onInk)
        }
        Spacer(Modifier.height(18.dp))
        Title("Jam")
        Spacer(Modifier.height(6.dp))
        T(
            "Listen together — in the same room or from anywhere. Everyone adds songs, and the music stays in step.",
            color = p.sub, height = 1.45f,
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(
                if (jam.hasName) "Friends see you as ${jam.myName}" else "Pick the name friends will see",
                Modifier.weight(1f), color = p.sub,
            )
            TextBtn(if (jam.hasName) "Change" else "Set name", {
                jamScope.launch {
                    val n = askName(env, jam.myName)
                    if (n != null && n.trim().isNotEmpty()) jam.setName(n)
                }
            })
        }
        Spacer(Modifier.height(8.dp))
        PillButton(
            AppIcons.broadcast, if (busy) "Starting…" else "Start a Jam",
            if (busy) null else {
                {
                    pickMode(env) { online ->
                        run {
                            val ok = jam.startHosting(online = online)
                            if (ok) null else jam.error
                        }
                    }
                }
            },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        T("Join a Jam nearby", size = 20, weight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        T("Be on the same Wi-Fi as the host, or on their hotspot.", color = p.sub, height = 1.4f)
        Spacer(Modifier.height(12.dp))
        val found = jam.nearby.values.toList()
        if (found.isEmpty()) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(16.dp)
                    Spacer(Modifier.width(12.dp))
                    T(
                        if (jam.lookingFailed) "Can't look for Jams right now. Use a code instead." else "Looking for Jams…",
                        Modifier.weight(1f), color = p.sub,
                    )
                }
            }
        }
        for (f in found) {
            Row(
                Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card)
                    .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(p.ink), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.jam, size = 20.dp, tint = p.onInk)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    T(f.name, size = 16, weight = FontWeight.Bold, maxLines = 1)
                    T("${f.host} · ${count(f.people, "person", "people")}", color = p.sub, maxLines = 1)
                }
                Spacer(Modifier.width(16.dp))
                FilledBtn("Join", if (busy) null else { { run { jam.join(f.address, f.port) } } })
            }
        }
        Spacer(Modifier.height(16.dp))
        T("Have a code? Online Jams work from anywhere.", color = p.sub)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                code, { code = it }, "Jam code", Modifier.weight(1f),
                leading = { Icon(AppIcons.link, size = 20.dp, tint = p.sub) },
                capitalize = true, onSubmit = { run { jam.joinWithCode(code) } },
            )
            Spacer(Modifier.width(10.dp))
            FilledBtn("Join", if (busy) null else { { run { jam.joinWithCode(code) } } })
        }
    }
}

// ---- Hosting ---------------------------------------------------------------------------------

/** The songs after the current one, in play order (follows shuffle). */
private fun upcomingTracks(env: Env): List<Track> {
    val pc = env.player
    val queue = pc.queue
    val tl = pc.player.currentTimeline
    var i = pc.currentIndex
    if (i !in queue.indices || tl.isEmpty || i >= tl.windowCount) return emptyList()
    val out = ArrayList<Track>()
    while (out.size < 30) {
        i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, pc.player.shuffleModeEnabled)
        if (i == C.INDEX_UNSET) break
        if (i < queue.size) out.add(queue[i])
    }
    return out
}

@Composable
private fun SongRow(track: Track, by: String?, big: Boolean = false) {
    val p = palette()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track, size = if (big) 64.dp else 46.dp, radius = if (big) 14.dp else 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(track.title, weight = FontWeight.SemiBold, size = if (big) 17 else 15, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            T(if (by == null) track.artist else "${track.artist} · Added by $by", color = p.sub, size = 13, maxLines = 1)
        }
    }
}

@Composable
private fun HostingView() {
    val env = LocalEnv.current
    val jam = env.jam
    val player = env.player
    val p = palette()
    val now = player.current
    val upcoming = upcomingTracks(env)
    val people = jam.guests.size + 1
    val code = jam.joinCode

    JamPage {
        Title(jam.jamName)
        Spacer(Modifier.height(4.dp))
        T("You're hosting · ${count(people, "person", "people")}", color = p.sub)
        EventPill(jam.event)
        Spacer(Modifier.height(18.dp))
        Card {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (jam.online) AppIcons.broadcast else AppIcons.wifi, size = 18.dp, tint = p.sub)
                    Spacer(Modifier.width(8.dp))
                    T(if (jam.online) "Online Jam" else "Invite friends", Modifier.weight(1f), color = p.sub)
                    if (jam.online && code != null) {
                        TextBtn("Copy invite", {
                            env.copyText?.invoke("Join my Jam on Musicly: open Jam, tap \"Have a code?\" and enter $code")
                            env.toast("Invite copied — paste it in a chat")
                        }, icon = AppIcons.link)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (jam.online && code != null) {
                    SelectionContainer { T(code, size = 26, weight = FontWeight.ExtraBold, spacing = 1.5f) }
                    Spacer(Modifier.height(6.dp))
                    T(
                        "Friends anywhere open Jam in Musicly and enter this code. They hear your songs on their own phones, in step with you.",
                        color = p.sub, height = 1.4f,
                    )
                } else if (code != null) {
                    SelectionContainer { T(code, size = 30, weight = FontWeight.ExtraBold, spacing = 2f) }
                    Spacer(Modifier.height(6.dp))
                    T(
                        "Friends on your Wi-Fi or hotspot open Jam in Musicly and tap Join — or type this code.",
                        color = p.sub, height = 1.4f,
                    )
                } else {
                    T("Turn on Wi-Fi or your hotspot so friends can join.", color = p.sub, height = 1.4f)
                }
            }
        }
        val shareError = jam.shareError
        if (jam.online && shareError != null) {
            Spacer(Modifier.height(10.dp))
            Status(shareError, failed = true, onRetry = { jam.retryShare() })
        }
        if (jam.online) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sharing = jam.sharingTitle
                if (sharing != null) {
                    Spinner(14.dp)
                    Spacer(Modifier.width(10.dp))
                }
                T(
                    if (sharing != null) "Sharing $sharing with friends…"
                    else "Songs are shared as private links that delete themselves within 12 hours.",
                    Modifier.weight(1f), color = p.sub, size = 12, height = 1.4f,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonChip(if (jam.hasName) "${jam.myName} (you)" else "You", crown = true)
            for (g in jam.guests) PersonChip(g.name, onDelete = { jam.removeGuest(g.id) })
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            "Friends can control playback", "Play, pause, skip and seek",
            jam.guestsControl, { jam.setGuestsControl(it) },
        )
        Spacer(Modifier.height(12.dp))
        if (now == null) {
            Card {
                T(
                    "Play something — it plays here for everyone, and friends' songs join the queue.",
                    color = p.sub, height = 1.45f,
                )
            }
        } else {
            T("Now playing", size = 18, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            SongRow(now, jam.addedBy(now), big = true)
        }
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            T("Up next", size = 18, weight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            for (t in upcoming) SongRow(t, jam.addedBy(t))
        }
        Spacer(Modifier.height(24.dp))
        OutlinedBtn(AppIcons.close, "End Jam", { jamScope.launch { jam.leave() } }, color = Red)
    }
}

// ---- Joined ----------------------------------------------------------------------------------

@Composable
private fun JoinedView() {
    val env = LocalEnv.current
    val jam = env.jam
    val p = palette()

    // Moves the progress bar between the host's updates.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }

    val s = jam.state
    val now = s.now
    @Suppress("UNUSED_EXPRESSION") tick
    val pos = jam.position
    val dur = now?.durationMs

    JamPage {
        Title(jam.jamName)
        Spacer(Modifier.height(4.dp))
        T(
            "${if (jam.online) "Online · " else ""}Hosted by ${jam.hostName} · ${count(s.people.size, "person", "people")}",
            color = p.sub,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.people.forEachIndexed { i, name -> PersonChip(name, crown = i == 0) }
        }
        EventPill(jam.event)
        Spacer(Modifier.height(22.dp))
        if (now == null) {
            Card { T("Nothing is playing yet. Add a song to get things going!", color = p.sub, height = 1.45f) }
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Artwork(jam.viewTrack(now), size = 240.dp, radius = 24.dp, shadow = true)
            }
            Spacer(Modifier.height(18.dp))
            T(now.title, Modifier.fillMaxWidth(), size = 22, weight = FontWeight.Bold, maxLines = 2, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            T(
                if (now.by == null) now.artist else "${now.artist} · Added by ${now.by}",
                Modifier.fillMaxWidth(), color = p.sub, maxLines = 1, align = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (dur != null && dur > 0) {
                val frac = (pos.toFloat() / dur).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(p.card)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxSize().background(p.ink))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    T(fmt(pos), color = p.sub, size = 12)
                    Spacer(Modifier.weight(1f))
                    T(fmt(dur), color = p.sub, size = 12)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (s.guestsControl) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    AppIconButton(AppIcons.prev, { jam.control("previous") }, iconSize = 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(p.ink).clickable { jam.control("toggle") },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (s.playing) AppIcons.pause else AppIcons.play, size = 34.dp, tint = p.onInk) }
                    Spacer(Modifier.width(12.dp))
                    AppIconButton(AppIcons.next, { jam.control("next") }, iconSize = 30.dp)
                }
            } else if (!jam.online) {
                T(
                    if (s.playing) "Playing on ${jam.hostName}'s phone" else "Paused on ${jam.hostName}'s phone",
                    Modifier.fillMaxWidth(), color = p.sub, size = 13, align = TextAlign.Center,
                )
            }
            if (jam.waitingForSong) {
                T(
                    "${jam.hostName} is sharing this song…",
                    Modifier.fillMaxWidth().padding(top = 6.dp), color = p.sub, size = 13, align = TextAlign.Center,
                )
            }
        }
        val follow = jam.followStatus
        if (jam.online && jam.listenHere && follow != null) {
            Box(Modifier.padding(top = 10.dp)) { Status(follow, failed = jam.followFailed, onRetry = { jam.retryFollow() }) }
        }
        if (jam.online) {
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                "Listen on this phone", "In step with the host", jam.listenHere, { jam.setListenHere(it) },
                secondary = AppIcons.headphones,
            )
        }
        Spacer(Modifier.height(22.dp))
        PillButton(AppIcons.add, "Add songs", { showAddSongs(env) }, Modifier.fillMaxWidth())
        if (s.queue.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            T("Up next", size = 18, weight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            for (t in s.queue) SongRow(jam.viewTrack(t), t.by)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedBtn(AppIcons.leave, "Leave Jam", { jamScope.launch { jam.leave() } })
    }
}

// ---- Add songs ------------------------------------------------------------------------------

/** Pick songs for the Jam: from the host's library, or send your own. */
private fun showAddSongs(env: Env) {
    env.showSheet { dismiss -> AddSongsSheet(dismiss) }
}

@Composable
private fun AddSongsSheet(dismiss: () -> Unit) {
    val env = LocalEnv.current
    val jam = env.jam
    val lib = env.library.observe()
    val p = palette()
    var mine by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    var hostSongs by remember { mutableStateOf<List<JamTrack>?>(null) }
    val height = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    // The Jam ended while this was open.
    LaunchedEffect(jam.active) { if (!jam.active) dismiss() }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(300)
        applied = query
    }
    LaunchedEffect(mine, applied) {
        if (!mine) {
            hostSongs = null
            hostSongs = try {
                jam.searchHost(applied)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    Column(Modifier.fillMaxWidth().height(height)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, p.line, RoundedCornerShape(20.dp))) {
                for ((value, label) in listOf(false to "${jam.hostName}'s songs", true to "My songs")) {
                    val sel = mine == value
                    Box(
                        Modifier.weight(1f).fillMaxSize().background(if (sel) p.card else Color.Transparent)
                            .clickable { mine = value },
                        contentAlignment = Alignment.Center,
                    ) { T(label, weight = FontWeight.Medium, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(12.dp))
            AppTextField(
                query, { query = it }, "Search songs or artists",
                leading = { Icon(AppIcons.search, size = 20.dp, tint = p.sub) },
            )
            if (mine) {
                Box(Modifier.padding(top = 8.dp)) {
                    T(
                        if (jam.online) "Your song is shared as a private link and added to the queue."
                        else "Your song is sent to ${jam.hostName}'s phone and added to the queue.",
                        color = p.sub, size = 12,
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!mine) {
                val songs = hostSongs
                if (songs == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = p.ink)
                    }
                } else if (songs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("No songs found", color = p.sub) }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(songs, key = { it.id }) { t ->
                            PickRow(jam.viewTrack(t), null) { next ->
                                jam.addFromHost(t, next = next)
                                env.toast("Added ${t.title}")
                            }
                        }
                    }
                }
            } else {
                val q = applied.trim().lowercase()
                val songs = lib.allSongs.filter {
                    q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                }
                if (songs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        T("No songs on this phone yet", color = p.sub)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(songs, key = { it.id }) { t ->
                            PickRow(t, jam.sending[t.id]) { next ->
                                jamScope.launch {
                                    val err = jam.send(t, next = next)
                                    env.toast(err ?: "Sent ${t.title}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(track: Track, progress: Double?, onAdd: (next: Boolean) -> Unit) {
    val p = palette()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(enabled = progress == null) { onAdd(false) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, size = 46.dp, radius = 10.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            T(track.title, size = 16, maxLines = 1)
            T(track.artist, color = p.sub, maxLines = 1)
        }
        Spacer(Modifier.width(16.dp))
        if (progress != null) {
            Box(Modifier.size(24.dp)) {
                if (progress > 0) {
                    CircularProgressIndicator(
                        progress = { progress.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.size(24.dp), color = p.ink, strokeWidth = 2.5.dp,
                    )
                } else {
                    Spinner(24.dp, 2.5.dp)
                }
            }
        } else {
            Box {
                AppIconButton(AppIcons.add, { menu = true }, tint = p.ink)
                DropdownMenu(menu, { menu = false }, containerColor = p.card) {
                    DropdownMenuItem(text = { T("Add to queue") }, onClick = { menu = false; onAdd(false) })
                    DropdownMenuItem(text = { T("Play next") }, onClick = { menu = false; onAdd(true) })
                }
            }
        }
    }
}
