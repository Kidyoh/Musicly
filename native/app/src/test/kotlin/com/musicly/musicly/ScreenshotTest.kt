@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musicly.musicly.data.Library
import com.musicly.musicly.player.PlayerController
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.data.UserPlaylist
import com.musicly.musicly.data.RssFeed
import com.musicly.musicly.data.PlayerStyle
import com.musicly.musicly.ui.Env
import com.musicly.musicly.ui.JamScreen
import com.musicly.musicly.ui.LocalEnv
import com.musicly.musicly.ui.MusiclyTheme
import com.musicly.musicly.ui.RadioScreen
import com.musicly.musicly.ui.RssScreen
import com.musicly.musicly.ui.CatalogScreen
import com.musicly.musicly.ui.Shell
import com.musicly.musicly.ui.ShellTabs
import com.musicly.musicly.ui.ArtistScreen
import com.musicly.musicly.ui.CollectionScreen
import com.musicly.musicly.ui.TelegramScreen
import com.musicly.musicly.ui.openPage
import com.musicly.musicly.ui.showPlayerStyle
import com.musicly.musicly.ui.showTrackOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens to PNG files (light and dark) so the UI can be
 * reviewed without a device. Output directory: system property or env var
 * MUSICLY_SHOTS (default /tmp/claude-0/shots).
 *
 *   ./gradlew --offline --no-daemon testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: MusiclyApp
    private lateinit var env: Env
    private var dark by mutableStateOf(false)

    private val outDir = File(
        System.getProperty("MUSICLY_SHOTS") ?: System.getenv("MUSICLY_SHOTS") ?: "/tmp/claude-0/shots",
    ).also { it.mkdirs() }

    // ---- Fake data ----------------------------------------------------------------------------

    private fun art(n: Int) = "https://example.invalid/art/$n.jpg"

    private fun song(src: TrackSource, id: String, title: String, artist: String, album: String? = null, ms: Long = 200_000, uri: String? = null) =
        Track(id = id, title = title, artist = artist, source = src, uri = uri ?: "https://example.invalid/$id.mp3", duration = ms, album = album, artworkUrl = art(id.hashCode() and 0xff))

    private val songs = listOf(
        "Blinding Lights" to "The Weeknd", "Save Your Tears" to "The Weeknd", "Starboy" to "The Weeknd",
        "As It Was" to "Harry Styles", "Watermelon Sugar" to "Harry Styles", "Levitating" to "Dua Lipa",
        "Don't Start Now" to "Dua Lipa", "Essence" to "Wizkid", "Calm Down" to "Rema",
        "Last Last" to "Burna Boy", "Peru" to "Fireboy DML", "Love Nwantiti" to "CKay",
    )

    private val channelTracks = songs.mapIndexed { i, (t, a) ->
        song(TrackSource.telegram, "tg:$i", t, a, album = "Album ${i / 3 + 1}", ms = 180_000L + i * 7_000)
    }
    private val favorites = channelTracks.take(5) + song(TrackSource.device, "ms:1", "Bad Guy", "Billie Eilish", "When We All Fall Asleep")
    private val recent = channelTracks.drop(3).take(6)
    private val deviceFiles = listOf(
        song(TrackSource.file, "file:1", "Midnight City", "M83", "Hurry Up, We're Dreaming", uri = "/sdcard/Music/midnight.mp3"),
        song(TrackSource.file, "file:2", "Redbone", "Childish Gambino", "Awaken, My Love!", uri = "/sdcard/Music/redbone.mp3"),
    )
    private val radio = Track(
        id = "rb:1", title = "Capital FM Lagos", artist = "Lagos, Nigeria", source = TrackSource.radio,
        uri = "https://example.invalid/stream", link = "https://example.invalid",
    )
    private val feedEp = song(TrackSource.feed, "rss:1", "Ep 42: The Sound of Lagos", "Afrobeats Weekly")

    @Before
    fun seed() {
        app = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("musicly", Context.MODE_PRIVATE).edit()
        fun arr(l: List<Track>) = JsonArray(l.map { it.toJson() }).toString()
        prefs.putString("favorites", arr(favorites))
        prefs.putString("recent", arr(recent))
        prefs.putString("files", arr(deviceFiles))
        val pl = UserPlaylist("pl1", "Late night drive", (channelTracks.take(8)).toMutableList())
        val pl2 = UserPlaylist("pl2", "Gym", channelTracks.drop(4).toMutableList())
        prefs.putString("playlists", JsonArray(listOf(pl.toJson(), pl2.toJson())).toString())
        prefs.putString(
            "plays",
            buildJsonObject {
                put("tracks", buildJsonObject { channelTracks.forEachIndexed { i, t -> put(t.id, 12 - i) } })
                put("artists", buildJsonObject { put("the weeknd", 40); put("harry styles", 22); put("dua lipa", 15); put("wizkid", 9) })
            }.toString(),
        )
        prefs.putString(
            "telegram",
            buildJsonObject {
                put("token", "123456:FAKE-TOKEN"); put("bot", "musicly_bot"); put("offset", 0)
                put("userChat", 42); put("tooBig", 0)
                put("channels", JsonArray(listOf(buildJsonObject {
                    put("id", -100123); put("name", "My Music Vault"); put("latest", 99)
                    put("tracks", JsonArray(channelTracks.map { it.toJson() }))
                })))
            }.toString(),
        )
        prefs.putString(
            "feeds",
            JsonArray(listOf(
                RssFeed("https://example.invalid/a.xml", "Afrobeats Weekly").toJson(),
                RssFeed("https://example.invalid/b.xml", "Lofi Hour").toJson(),
            )).toString(),
        )
        prefs.putBoolean("_migrated", true)
        prefs.commit()
        // The Application already started with an empty store; reload from the seeded prefs.
        app.library = Library(app.store, app.device)
        // Library restores from Store which reads SharedPreferences directly, so the
        // new instance sees the seeded data.
        env = Env(app)
        // PlayerController.startService() binds the PlaybackService through a MediaController,
        // which Robolectric's bindService crashes on (null ComponentName). Pretend it is connected.
        PlayerController::class.java.getDeclaredField("controller").apply { isAccessible = true }
            .set(app.player, com.google.common.util.concurrent.Futures.immediateCancelledFuture<Any>())
    }

    private fun start() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalEnv provides env) {
                MusiclyTheme(dark) { Shell() }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 1500) {
        // Advance in steps: animations that start after a recomposition need
        // more than one frame batch to finish under the manual clock.
        repeat(4) {
            rule.mainClock.advanceTimeBy(ms / 2)
            rule.waitForIdle()
        }
        rule.mainClock.advanceTimeBy(100)
    }

    private fun write(name: String, bmp: Bitmap) {
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Draws a window's root view to a bitmap (captureToImage's forceRedraw hangs with Robolectric's paused looper). */
    private fun grab(v: android.view.View): Bitmap {
        val root = v.rootView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        return bmp
    }

    /** Saves the main window; popups (bottom sheets, dialogs) are composited on top. */
    private fun capture(name: String) {
        val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes().map { (it.root as androidx.compose.ui.platform.ViewRootForTest).view }
        var base = grab(roots[0])
        if (roots.size > 1) {
            base = base.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(base)
            c.drawColor(0x66000000)
            for (i in 1 until roots.size) {
                val b = grab(roots[i])
                c.drawBitmap(b, (base.width - b.width) / 2f, (base.height - b.height).toFloat(), Paint())
            }
        }
        write(name, base)
    }

    /** Runs [setup] then captures light and dark. */
    private fun both(name: String, setup: () -> Unit = {}) {
        for (d in listOf(false, true)) {
            dark = d
            setup()
            settle()
            capture("${name}_${if (d) "dark" else "light"}")
        }
    }

    private fun openTab(i: Int) {
        env.closeSheet(); env.nowPlayingOpen = false; env.nav.popToRoot()
        ShellTabs.open(i)
        settle()
    }

    private fun page(key: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        env.closeSheet(); env.nowPlayingOpen = false; env.nav.popToRoot()
        openPage(env, key, content)
    }

    @Test fun smoke() {
        rule.setContent { androidx.compose.material3.Text("hello") }
        rule.waitForIdle()
        capture("00_smoke")
    }

    // ---- Screens ------------------------------------------------------------------------------

    @Test fun home() { start(); both("01_home") { openTab(0) } }
    @Test fun search() { start(); both("02_search") { openTab(1) } }
    @Test fun library() { start(); both("03_library") { openTab(2) } }
    @Test fun hotlist() { start(); both("04_hotlist") { openTab(3) } }
    @Test fun radioPage() { start(); both("05_radio") { page("radio") { RadioScreen() } } }
    @Test fun telegram() {
        start()
        both("06_telegram") { page("telegram:-100123") { TelegramScreen(channelId = -100123) } }
    }
    @Test fun collection() {
        start(); both("07_collection") { page("playlist:pl1") { CollectionScreen(playlistId = "pl1") } }
    }
    @Test fun artist() { start(); both("08_artist") { page("artist:The Weeknd") { ArtistScreen("The Weeknd") } } }
    @Test fun rss() { start(); both("09_rss") { page("rss") { RssScreen() } } }
    @Test fun rssCatalog() { start(); both("09b_rss_suggested") { page("rss-catalog") { CatalogScreen() } } }
    @Test fun rssEmpty() {
        start()
        both("09d_rss_empty") {
            env.library.feeds.toList().forEach { env.library.removeFeed(it) }
            page("rss-empty") { RssScreen() }
        }
    }
    @Test fun jam() { start(); both("10_jam") { page("jam") { JamScreen() } } }

    private fun play(t: List<Track> = channelTracks, i: Int = 0) {
        env.player.playQueue(t, i)
        settle()
    }

    @Test fun nowPlayingClassic() {
        start(); play()
        both("11_nowplaying_classic") { env.openNowPlaying() }
    }

    @Test fun miniPlayerOnHome() {
        start(); play()
        both("11b_home_with_miniplayer") { openTab(0) }
    }

    @Test fun nowPlayingMinimal() {
        start(); app.library.setPlayerStyle(PlayerStyle.minimal); play()
        both("12_nowplaying_minimal") { env.openNowPlaying() }
    }

    @Test fun lyrics() {
        start(); play()
        both("13_lyrics") { env.nowPlayingOpen = false; env.openNowPlaying(lyrics = true) }
    }

    @Test fun playerStyleSheet() {
        start(); play()
        both("14_player_style_sheet") { env.closeSheet(); env.openNowPlaying(); showPlayerStyle(env) }
    }

    @Test fun trackOptionsSheet() {
        start()
        both("15_track_options_sheet") { openTab(2); showTrackOptions(env, channelTracks[0]) }
    }

    @Test fun radioNowPlaying() {
        start(); play(listOf(radio))
        both("16_nowplaying_radio") { env.openNowPlaying() }
    }
}
