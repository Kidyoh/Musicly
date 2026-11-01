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

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        audio = AudioEngine(this)
        player = PlayerController(this, audio, store)
        device = Device(this)
        createPlayerChannel()
        library = Library(store, device)
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
}
