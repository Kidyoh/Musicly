package com.musicly.musicly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.musicly.musicly.R

/** A Phosphor icon: a glyph in one of the bundled icon fonts (MIT licence). */
data class Ph(val code: Int, val family: PhFamily)

enum class PhFamily { Regular, Fill, Bold }

val PhosphorFonts = mapOf(PhFamily.Regular to FontFamily(Font(R.font.phosphor_regular)), PhFamily.Fill to FontFamily(Font(R.font.phosphor_fill)), PhFamily.Bold to FontFamily(Font(R.font.phosphor_bold)))

/** Draws a Phosphor icon at [size] in [tint] (defaults to the current content colour). */
@Composable
fun Icon(icon: Ph, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    val px = with(LocalDensity.current) { size.toSp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            String(Character.toChars(icon.code)),
            style = TextStyle(fontFamily = PhosphorFonts[icon.family], fontSize = px, color = tint, lineHeight = px, platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
    }
}

object AppIcons {
    val bell = Ph(0xe0ce, PhFamily.Regular)
    val jam = Ph(0xe68e, PhFamily.Regular)
    val jamOn = Ph(0xe68e, PhFamily.Fill)
    val broadcast = Ph(0xe0f2, PhFamily.Regular)
    val crown = Ph(0xe614, PhFamily.Fill)
    val upload = Ph(0xe4c0, PhFamily.Regular)
    val leave = Ph(0xe42a, PhFamily.Regular)
    val wifi = Ph(0xe4ea, PhFamily.Regular)
    val home = Ph(0xe2c2, PhFamily.Regular)
    val homeOn = Ph(0xe2c2, PhFamily.Fill)
    val search = Ph(0xe30c, PhFamily.Regular)
    val searchOn = Ph(0xe30c, PhFamily.Bold)
    val library = Ph(0xe758, PhFamily.Regular)
    val libraryOn = Ph(0xe758, PhFamily.Fill)
    val hot = Ph(0xe242, PhFamily.Regular)
    val hotOn = Ph(0xe242, PhFamily.Fill)
    val back = Ph(0xe138, PhFamily.Regular)
    val down = Ph(0xe136, PhFamily.Regular)
    val chevron = Ph(0xe13a, PhFamily.Regular)
    val close = Ph(0xe4f6, PhFamily.Regular)
    val more = Ph(0xe1fe, PhFamily.Bold)
    val play = Ph(0xe3d0, PhFamily.Fill)
    val pause = Ph(0xe39e, PhFamily.Fill)
    val next = Ph(0xe5a6, PhFamily.Fill)
    val prev = Ph(0xe5a4, PhFamily.Fill)
    val shuffle = Ph(0xe422, PhFamily.Regular)
    val repeat = Ph(0xe3f6, PhFamily.Regular)
    val repeatOne = Ph(0xe3f8, PhFamily.Regular)
    val playCircle = Ph(0xe3d2, PhFamily.Regular)
    val queue = Ph(0xe6ac, PhFamily.Regular)
    val addQueue = Ph(0xe2f8, PhFamily.Regular)
    val playNext = Ph(0xe6aa, PhFamily.Regular)
    val drag = Ph(0xeae2, PhFamily.Regular)
    val heart = Ph(0xe2a8, PhFamily.Regular)
    val heartOn = Ph(0xe2a8, PhFamily.Fill)
    val add = Ph(0xe3d4, PhFamily.Regular)
    val addPlaylist = Ph(0xeb7c, PhFamily.Regular)
    val music = Ph(0xe340, PhFamily.Regular)
    val disc = Ph(0xecac, PhFamily.Regular)
    val artist = Ph(0xe326, PhFamily.Regular)
    val follow = Ph(0xe4d0, PhFamily.Regular)
    val following = Ph(0xeafa, PhFamily.Fill)
    val history = Ph(0xe1a0, PhFamily.Regular)
    val phone = Ph(0xe1e0, PhFamily.Regular)
    val folder = Ph(0xe256, PhFamily.Regular)
    val edit = Ph(0xe3b4, PhFamily.Regular)
    val trash = Ph(0xe4a6, PhFamily.Regular)
    val sort = Ph(0xe444, PhFamily.Regular)
    val check = Ph(0xe182, PhFamily.Bold)
    val external = Ph(0xe5de, PhFamily.Regular)
    val sparkle = Ph(0xe6a2, PhFamily.Fill)
    val trend = Ph(0xe4ae, PhFamily.Regular)
    val radio = Ph(0xe77e, PhFamily.Regular)
    val lyrics = Ph(0xe75c, PhFamily.Regular)
    val lyricsOn = Ph(0xe75c, PhFamily.Fill)
    val findLyrics = Ph(0xebe0, PhFamily.Regular)
    val sleep = Ph(0xe58e, PhFamily.Regular)
    val sleepOn = Ph(0xe58e, PhFamily.Fill)
    val speed = Ph(0xe628, PhFamily.Regular)
    val sound = Ph(0xe434, PhFamily.Regular)
    val soundOn = Ph(0xe434, PhFamily.Fill)
    val eq = Ph(0xebbc, PhFamily.Regular)
    val loud = Ph(0xe44a, PhFamily.Regular)
    val fade = Ph(0xe802, PhFamily.Regular)
    val minus = Ph(0xe32a, PhFamily.Bold)
    val plus = Ph(0xe3d4, PhFamily.Bold)
    val sun = Ph(0xe472, PhFamily.Regular)
    val moon = Ph(0xe330, PhFamily.Regular)
    val offline = Ph(0xe1b6, PhFamily.Regular)
    val waveform = Ph(0xe802, PhFamily.Regular)
    val telegram = Ph(0xe5bc, PhFamily.Regular)
    val telegramOn = Ph(0xe5bc, PhFamily.Fill)
    val connected = Ph(0xeb5a, PhFamily.Regular)
    val refresh = Ph(0xe094, PhFamily.Regular)
    val link = Ph(0xe2e2, PhFamily.Regular)
    val eye = Ph(0xe220, PhFamily.Regular)
    val eyeOff = Ph(0xe224, PhFamily.Regular)
    val backup = Ph(0xe1ae, PhFamily.Regular)
    val restore = Ph(0xe1ac, PhFamily.Regular)
    val download = Ph(0xe20c, PhFamily.Regular)
    val saved = Ph(0xe184, PhFamily.Fill)
    val key = Ph(0xe2d6, PhFamily.Regular)
    val headphones = Ph(0xe2a6, PhFamily.Regular)
}
