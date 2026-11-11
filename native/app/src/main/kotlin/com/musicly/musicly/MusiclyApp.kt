package com.musicly.musicly

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import com.musicly.musicly.data.Library
import com.musicly.musicly.data.Store
import com.musicly.musicly.net.Device
import com.musicly.musicly.player.LyricsController
import com.musicly.musicly.player.AudioEngine
import com.musicly.musicly.player.PlaybackService
import com.musicly.musicly.player.PlayerController

@UnstableApi
class MusiclyApp : Application() {
    lateinit var store: Store
    lateinit var audio: AudioEngine
    lateinit var player: PlayerController
    lateinit var device: Device
    lateinit var library: Library
    lateinit var lyrics: LyricsController
    lateinit var jam: com.musicly.musicly.jam.JamController

    /** The user's own Telegram account (TDLib). Lazy: TDLib's native library is only loaded once they log in. */
    val tg: com.musicly.musicly.tg.TgBackend by lazy { com.musicly.musicly.tg.TdBackend(this) }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        audio = AudioEngine(this)
        player = PlayerController(this, audio, store)
        device = Device(this)
        createPlayerChannel()
        trimTelegramCache()
        library = Library(store, device)
        library.tgUser.attach { tg } // logs the user's own Telegram account back in when they had one
        lyrics = LyricsController(player, store)
        jam = com.musicly.musicly.jam.JamController(library, player, store, device, cacheDir)
        player.onTrackStarted = library::recordPlay
        player.localCopy = library::downloadedCopy
        library.onRestored = player::restoreSettings
        // Home-screen widgets follow whatever is playing.
        val refresh = { HomeWidgets.update(this, player.current, player.isPlaying, player.nowOnAir) }
        kotlinx.coroutines.MainScope().launch {
            snapshotFlow { Triple(player.current?.id, player.isPlaying, player.nowOnAir) }.collect { refresh() }
        }
    }

    // Many phones hide silent (low importance) notifications on the lock screen.
    // A default-importance channel with no sound keeps the player visible there
    // without ever making a noise.
    private fun createPlayerChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel("com.musicly.audio")
        if (manager.getNotificationChannel(PlaybackService.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            PlaybackService.CHANNEL_ID, "Now playing", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Player controls in the notification shade and on the lock screen"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Songs streamed with a Telegram account stay in cache/td-files. Keep that
     * folder under 300 MB by deleting the oldest files (they download again if needed).
     */
    private fun trimTelegramCache() {
        Thread {
            try {
                val dir = java.io.File(cacheDir, "td-files")
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                var total = files.sumOf { it.length() }
                val limit = 300L * 1024 * 1024
                if (total <= limit) return@Thread
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= limit) break
                    total -= f.length()
                    f.delete()
                }
            } catch (_: Exception) {
            }
        }.start()
    }
}
