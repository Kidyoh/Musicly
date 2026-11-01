package com.musicly.musicly.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.net.Http
import com.musicly.musicly.net.TelegramFiles
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Telegram file links expire after about an hour, so channel songs are queued
 * as `tg://<file id>` and turned into a real link only when they are played.
 */
object TelegramLinks {
    private class Entry(val url: String, val expires: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun url(fileId: String): String {
        val hit = cache[fileId]
        if (hit != null && hit.expires > System.currentTimeMillis() + 45_000) return hit.url
        val bot = TelegramFiles.bot ?: throw IllegalStateException("Telegram is not connected")
        val url = bot.fileUrl(fileId)
        cache[fileId] = Entry(url, System.currentTimeMillis() + 50 * 60_000)
        return url
    }

    /** Drop a cached link, e.g. after it failed. */
    fun forget(fileId: String) {
        cache.remove(fileId)
    }

    /** Look the link up ahead of time so the next song starts instantly. */
    suspend fun warm(fileId: String) {
        try {
            url(fileId)
        } catch (_: Exception) {
        }
    }
}

/** Owns the single ExoPlayer used by the app and by the notification service. */
@UnstableApi
class AudioEngine(context: Context) {
    val player: ExoPlayer

    init {
        val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(Http.client))
        val factory = ResolvingDataSource.Factory(upstream) { spec: DataSpec ->
            if (spec.uri.scheme == "tg") {
                val id = spec.uri.schemeSpecificPart.removePrefix("//")
                spec.withUri(Uri.parse(runBlocking { TelegramLinks.url(id) }))
            } else {
                spec
            }
        }
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    companion object {
        /** What the lock screen, notification, headphones and car show. */
        fun mediaItem(t: Track, art: Track? = null): MediaItem {
            val src = art ?: t
            val uri = when (t.source) {
                TrackSource.telegram -> "tg://${t.uri}"
                TrackSource.file -> t.uri!!.let { if (it.startsWith("/")) Uri.fromFile(java.io.File(it)).toString() else it }
                else -> t.uri!!
            }
            val artUri = when {
                src.mediaId != null -> "content://media/external/audio/media/${src.mediaId}"
                else -> t.artworkUrl
            }
            val meta = MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setArtworkUri(artUri?.let { Uri.parse(it) })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .apply { if (!t.isRadio && t.duration != null && t.duration > 0) setDurationMs(t.duration) }
                .build()
            return MediaItem.Builder()
                .setMediaId(t.id)
                .setUri(uri)
                .setMediaMetadata(meta)
                .apply { if (t.source == TrackSource.telegram) setMimeType(t.mime ?: "audio/mpeg") }
                .build()
        }
    }
}
