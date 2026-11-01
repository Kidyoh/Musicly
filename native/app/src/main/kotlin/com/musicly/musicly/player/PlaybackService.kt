package com.musicly.musicly.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.musicly.musicly.MainActivity
import com.musicly.musicly.MusiclyApp

/**
 * Keeps music playing with the screen off and shows the player in the
 * notification shade and on the lock screen (media controls, cover, seek bar).
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as MusiclyApp
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, app.audio.player)
            .setSessionActivity(open)
            .setBitmapLoader(CoverLoader(this))
            .build()
        // The channel is created by the app at default importance (many phones
        // hide silent notifications from the lock screen); Media3 reuses it.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(com.musicly.musicly.R.string.player_channel)
                .build().apply { setSmallIcon(com.musicly.musicly.R.drawable.ic_stat_musicly) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "com.musicly.player"
    }
}
