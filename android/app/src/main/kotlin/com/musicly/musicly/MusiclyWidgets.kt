package com.musicly.musicly

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
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
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import com.ryanheise.audioservice.MediaButtonReceiver
import es.antonborri.home_widget.HomeWidgetProvider
import java.io.File

/**
 * Home-screen widgets. The Flutter side (lib/services/home_widgets.dart) saves
 * the current song into the widget storage; the buttons send standard media
 * button events to the player, so they work while the app is in the background.
 */
object MusiclyWidgetViews {
    fun bind(context: Context, layout: Int, data: SharedPreferences, rounded: Float): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        val hasTrack = data.getBoolean("has_track", false)
        val playing = data.getBoolean("playing", false)

        views.setTextViewText(R.id.widget_title,
            if (hasTrack) data.getString("title", "") else context.getString(R.string.widget_empty_title))
        views.setTextViewText(R.id.widget_artist,
            if (hasTrack) data.getString("artist", "") else context.getString(R.string.widget_empty_subtitle))

        val art = data.getString("art_path", null)?.let { loadArt(it, rounded) }
        if (art != null) views.setImageViewBitmap(R.id.widget_art, art)
        else views.setImageViewResource(R.id.widget_art, R.drawable.ic_w_note)

        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)

        // Body opens the app; buttons control playback when something is queued.
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        if (hasTrack) {
            views.setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            if (layout == R.layout.widget_now_playing) {
                views.setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                views.setOnClickPendingIntent(R.id.widget_prev, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
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
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        return PendingIntent.getBroadcast(context, keyCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Loads the cover, scaled down for RemoteViews, with rounded corners. */
    private fun loadArt(path: String, radiusFraction: Float): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (radiusFraction <= 0f) return src
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size * radiusFraction
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        return out
    }
}

class NowPlayingWidget : HomeWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray,
                          widgetData: SharedPreferences) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id,
                MusiclyWidgetViews.bind(context, R.layout.widget_now_playing, widgetData, 0.12f))
        }
    }
}

class MiniPlayerWidget : HomeWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray,
                          widgetData: SharedPreferences) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id,
                MusiclyWidgetViews.bind(context, R.layout.widget_mini, widgetData, 0.08f))
        }
    }
}
