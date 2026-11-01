package com.musicly.musicly

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.util.UnstableApi
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.net.Http
import com.musicly.musicly.net.TelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps the home-screen widgets in step with what's playing: title, artist,
 * cover and play/pause. Writes are debounced and skipped when nothing visible changed.
 */
object HomeWidgets {
    private val providers = listOf(NowPlayingWidget::class.java, MiniPlayerWidget::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var debounce: Job? = null
    private var artKey: String? = null
    private var lastSignature: String? = null

    fun update(context: Context, t: Track?, playing: Boolean, onAir: String?) {
        debounce?.cancel()
        debounce = scope.launch {
            delay(300)
            write(context.applicationContext, t, playing, onAir)
        }
    }

    private fun badge(t: Track) = when (t.source) {
        TrackSource.radio -> "● LIVE"
        TrackSource.telegram -> "TELEGRAM"
        TrackSource.feed -> "RSS"
        TrackSource.device, TrackSource.file -> "ON THIS PHONE"
    }

    private suspend fun write(ctx: Context, t: Track?, playing: Boolean, onAir: String?) {
        val subtitle = t?.let { if (it.isRadio) (onAir ?: it.artist) else it.artist }
        val signature = "${t?.id}|$playing|$subtitle"
        if (signature == lastSignature) return
        lastSignature = signature
        try {
            val prefs = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)
            val e = prefs.edit()
            e.putBoolean("has_track", t != null).putBoolean("playing", playing)
                .putString("title", t?.title).putString("artist", subtitle)
                .putString("badge", t?.let(::badge))
            e.apply()
            if (t != null) saveArt(ctx, prefs, t)
            val mgr = AppWidgetManager.getInstance(ctx)
            for (p in providers) {
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, p))
                if (ids.isNotEmpty()) ctx.sendBroadcast(
                    Intent(ctx, p).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        } catch (_: Exception) {
            // Widgets are a nicety; never let them break playback.
        }
    }

    private suspend fun saveArt(ctx: Context, prefs: SharedPreferences, t: Track) {
        val key = t.artworkUrl ?: t.mediaId?.let { "ms:$it" }
        if (key == artKey) return
        artKey = key
        var bytes: ByteArray? = null
        val app = ctx as? MusiclyApp ?: ctx.applicationContext as MusiclyApp
        if (t.mediaId != null) {
            bytes = app.device.artwork(t.mediaId)
        } else if (t.artworkUrl != null) {
            var url = t.artworkUrl
            if (TelegramFiles.isThumb(url)) url = TelegramFiles.resolveThumb(url) ?: ""
            if (url.startsWith("http")) {
                bytes = Http.get(url, timeoutSeconds = 15).use { if (it.code == 200) it.body.bytes() else null }
            }
        }
        if (bytes == null) {
            prefs.edit().remove("art_path").apply()
            return
        }
        // A new name per song so the launcher never shows a stale cover.
        val file = File(ctx.filesDir, "widget_art_${(key ?: "").hashCode().toUInt()}.img")
        file.writeBytes(bytes)
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("widget_art_") && it != file }?.forEach { it.delete() }
        prefs.edit().putString("art_path", file.path).apply()
    }

    /** Asks the launcher to place a widget (Android 8+ launchers that support it). */
    fun canPin(ctx: Context) = AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported

    fun pin(ctx: Context, mini: Boolean = false) {
        AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, providers[if (mini) 1 else 0]), null, null)
    }
}

object MusiclyWidgetViews {
    fun bind(context: Context, layout: Int, data: SharedPreferences, rounded: Float): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        val hasTrack = data.getBoolean("has_track", false)
        val playing = data.getBoolean("playing", false)

        views.setTextViewText(R.id.widget_title, if (hasTrack) data.getString("title", "") else context.getString(R.string.widget_empty_title))
        views.setTextViewText(R.id.widget_artist, if (hasTrack) data.getString("artist", "") else context.getString(R.string.widget_empty_subtitle))

        val art = data.getString("art_path", null)?.let { loadArt(it, rounded) }
        if (art != null) views.setImageViewBitmap(R.id.widget_art, art) else views.setImageViewResource(R.id.widget_art, R.drawable.ic_w_note)

        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)

        // Body opens the app; buttons control playback when something is queued.
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        if (hasTrack) {
            views.setOnClickPendingIntent(R.id.widget_play, action(context, WidgetActions.PLAY_PAUSE))
            if (layout == R.layout.widget_now_playing) {
                views.setOnClickPendingIntent(R.id.widget_next, action(context, WidgetActions.NEXT))
                views.setOnClickPendingIntent(R.id.widget_prev, action(context, WidgetActions.PREVIOUS))
            }
        } else {
            views.setOnClickPendingIntent(R.id.widget_play, openApp(context))
        }

        if (layout == R.layout.widget_now_playing) {
            val badge = data.getString("badge", null)
            views.setViewVisibility(R.id.widget_badge, if (badge.isNullOrEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_badge, badge ?: "")
            val dim = if (hasTrack) 255 else 90
            views.setInt(R.id.widget_next, "setImageAlpha", dim)
            views.setInt(R.id.widget_prev, "setImageAlpha", dim)
        }
        return views
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(context: Context, action: String): PendingIntent {
        val intent = Intent(context, WidgetActions::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Loads the cover, scaled down for RemoteViews, with rounded corners. */
    private fun loadArt(path: String, radiusFraction: Float): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (radiusFraction <= 0f) return src
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val r = size * radiusFraction
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        return out
    }
}

class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val data = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
        for (id in ids) manager.updateAppWidget(id, MusiclyWidgetViews.bind(context, R.layout.widget_now_playing, data, 0.12f))
    }
}

class MiniPlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val data = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
        for (id in ids) manager.updateAppWidget(id, MusiclyWidgetViews.bind(context, R.layout.widget_mini, data, 0.08f))
    }
}

/** Widget buttons: play/pause, next, previous. */
@UnstableApi
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val player = (context.applicationContext as MusiclyApp).player
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when (intent.action) {
                PLAY_PAUSE -> player.togglePlay()
                NEXT -> player.next()
                PREVIOUS -> player.previous()
            }
        }
    }

    companion object {
        const val PLAY_PAUSE = "com.musicly.musicly.widget.PLAY_PAUSE"
        const val NEXT = "com.musicly.musicly.widget.NEXT"
        const val PREVIOUS = "com.musicly.musicly.widget.PREVIOUS"
    }
}
