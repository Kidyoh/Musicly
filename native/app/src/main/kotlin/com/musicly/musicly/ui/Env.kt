package com.musicly.musicly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.musicly.musicly.MusiclyApp
import com.musicly.musicly.data.Library
import com.musicly.musicly.net.Device
import com.musicly.musicly.player.LyricsController
import com.musicly.musicly.player.PlayerController

/** One page on the navigation stack. [key] identifies it (e.g. "artist:Tems"). */
class Page(val key: String, val content: @Composable () -> Unit)

/**
 * Pages opened on top of the tabs (artist, playlist, radio, Telegram, ...).
 * Back removes the top page. The Now Playing screen is separate ([Env.nowPlaying]).
 */
class Nav {
    val stack = mutableStateListOf<Page>()

    fun push(key: String, content: @Composable () -> Unit) {
        if (stack.lastOrNull()?.key == key) return
        stack.add(Page(key, content))
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToRoot() = stack.clear()
}

/** A bottom sheet. [content] gets a function that closes it. */
class SheetRequest(val content: @Composable (dismiss: () -> Unit) -> Unit)

/**
 * Everything a screen needs, passed down with [LocalEnv]:
 *
 *     val env = LocalEnv.current
 *     val lib = env.library.observe()   // redraws on any library change
 *     env.player.playQueue(songs, 0)
 *     env.toast("Added")
 *     env.nav.push("artist:Tems") { ArtistScreen("Tems") }
 *     env.showSheet { dismiss -> ...sheet content... }
 */
@UnstableApi
class Env(val app: MusiclyApp) {
    val library: Library get() = app.library
    val player: PlayerController get() = app.player
    val lyrics: LyricsController get() = app.lyrics
    val device: Device get() = app.device
    val jam: com.musicly.musicly.jam.JamController get() = app.jam
    val nav = Nav()

    /** Full-screen Now Playing on top of everything. */
    var nowPlayingOpen by mutableStateOf(false)
    var nowPlayingOnLyrics by mutableStateOf(false)

    fun openNowPlaying(lyrics: Boolean = false) {
        nowPlayingOnLyrics = lyrics
        nowPlayingOpen = true
    }

    // Toast, sheet and dialog hosts are drawn by the Shell.
    var toastMessage by mutableStateOf<String?>(null)
    fun toast(msg: String) {
        toastMessage = msg
    }

    var sheet by mutableStateOf<SheetRequest?>(null)
    fun showSheet(content: @Composable (dismiss: () -> Unit) -> Unit) {
        sheet = SheetRequest(content)
    }

    fun closeSheet() {
        sheet = null
    }

    var dialog by mutableStateOf<SheetRequest?>(null)
    fun showDialog(content: @Composable (dismiss: () -> Unit) -> Unit) {
        dialog = SheetRequest(content)
    }

    /** Set by MainActivity: system file pickers and permission prompts. */
    var pickAudioFiles: (suspend () -> List<Pair<String, String>>)? = null // (uri, name)
    var pickBackupFile: (suspend () -> String?)? = null // file text
    var openUrl: ((String) -> Unit)? = null
    var shareText: ((String) -> Unit)? = null
    var copyText: ((String) -> Unit)? = null
}

val LocalEnv = compositionLocalOf<Env> { error("Env not provided") }

@Composable
fun Fill(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
