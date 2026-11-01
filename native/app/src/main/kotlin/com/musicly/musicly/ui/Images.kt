@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.musicly.musicly.data.Track
import com.musicly.musicly.net.Device
import com.musicly.musicly.net.Http
import com.musicly.musicly.net.TelegramFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Tiny image loader (no extra dependency): OkHttp for links, BitmapFactory for
 * decoding, an in-memory LRU cache keyed by source.
 */
object Images {
    private const val MAX_DIM = 900

    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(8 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }

    fun peek(key: String): ImageBitmap? = cache.get(key)

    /** Key under which a track's cover is cached, or null if it has no cover. */
    fun keyOf(t: Track?): String? = when {
        t == null -> null
        t.artworkUrl != null -> t.artworkUrl
        t.mediaId != null -> "ms:${t.mediaId}"
        else -> null
    }

    /** Cache key for an arbitrary [RemoteImage] url (Telegram refs are resolved to links first). */
    private fun cachedKey(url: String): String =
        if (TelegramFiles.isThumb(url)) TelegramFiles.cachedThumb(url) ?: url else url

    /** Already-decoded image for [url], if cached (no network). */
    fun peekUrl(url: String?): ImageBitmap? = url?.let { peek(cachedKey(it)) }

    fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    private fun decodeFile(path: String): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    }

    private fun sample(w: Int, h: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= MAX_DIM) s *= 2
        return s
    }

    private suspend fun fetch(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val bytes = r.body.bytes()
                decode(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** Loads an http(s) link, `tgthumb:<id>` reference or absolute file path. */
    suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        val bmp: ImageBitmap?
        var key = url
        when {
            TelegramFiles.isThumb(url) -> {
                val link = TelegramFiles.cachedThumb(url) ?: TelegramFiles.resolveThumb(url) ?: return null
                key = link
                peek(key)?.let { return it }
                bmp = fetch(link)
            }
            url.startsWith("/") -> bmp = withContext(Dispatchers.IO) {
                try {
                    if (File(url).exists()) decodeFile(url) else null
                } catch (_: Throwable) {
                    null
                }
            }
            else -> bmp = fetch(url)
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    /** Cover of a track: web link, Telegram thumb, Jam file, or phone-library art. */
    suspend fun loadTrack(device: Device?, t: Track): ImageBitmap? {
        val url = t.artworkUrl
        if (url != null) return load(url)
        val id = t.mediaId ?: return null
        val key = "ms:$id"
        peek(key)?.let { return it }
        val bytes = try {
            device?.artwork(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return null
        val bmp = withContext(Dispatchers.IO) { decode(bytes) } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** Synchronous cache lookup for a track cover. */
    fun peekTrack(t: Track?): ImageBitmap? {
        t ?: return null
        val url = t.artworkUrl
        if (url != null) return peekUrl(url)
        return t.mediaId?.let { peek("ms:$it") }
    }
}

/**
 * Network / file image. Shows [placeholder] until loaded or when it fails.
 * Keeps the previous image while a new one loads (gapless).
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {},
) {
    var bmp by remember { mutableStateOf(Images.peekUrl(url)) }
    LaunchedEffect(url) {
        if (url == null) {
            bmp = null
            return@LaunchedEffect
        }
        val hit = Images.peekUrl(url)
        if (hit != null) {
            bmp = hit
            return@LaunchedEffect
        }
        bmp = Images.load(url)
    }
    val b = bmp
    if (b == null) {
        Box(modifier) { placeholder() }
    } else {
        Image(b, null, modifier, contentScale = contentScale)
    }
}

/** Cover of [track] (or null while loading / when it has none). */
@Composable
fun rememberTrackArt(track: Track?): ImageBitmap? {
    val env = LocalEnv.current
    var bmp by remember { mutableStateOf(Images.peekTrack(track)) }
    LaunchedEffect(track?.id, track?.artworkUrl, track?.mediaId) {
        if (track == null) {
            bmp = null
            return@LaunchedEffect
        }
        val hit = Images.peekTrack(track)
        if (hit != null) {
            bmp = hit
            return@LaunchedEffect
        }
        bmp = Images.loadTrack(env.device, track)
    }
    return bmp
}

// -- art_color.dart ---------------------------------------------------------

private object NoColor
private val colorCache = ConcurrentHashMap<String, Any>()

/** The dominant tone of a song's cover, used for the Now Playing glow. */
suspend fun artColor(env: Env, track: Track): Color? {
    val key = track.artworkUrl ?: track.mediaId?.let { "ms:$it" } ?: return null
    colorCache[key]?.let { return it as? Color }
    val color = withTimeoutOrNull(6000) {
        try {
            val img = Images.loadTrack(env.device, track) ?: return@withTimeoutOrNull null
            withContext(Dispatchers.Default) { dominant(img.asAndroidBitmap()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }
    colorCache[key] = color ?: NoColor
    return color
}

/** Picks the most prominent saturated tone, shaped like Material's `primary` (mid tone). */
private fun dominant(src: Bitmap): Color? {
    val w = 48
    val h = (48f * src.height / src.width).toInt().coerceIn(1, 96)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val weight = HashMap<Int, Double>()
    val hsv = FloatArray(3)
    for (c in px) {
        if ((c ushr 24) < 128) continue
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[2] < 0.12f) continue
        val bin = ((hsv[0] / 15f).toInt() % 24)
        weight[bin] = (weight[bin] ?: 0.0) + 0.1 + hsv[1] * hsv[2]
    }
    if (weight.isEmpty()) return null
    val best = weight.maxByOrNull { it.value }!!.key
    var sx = 0.0; var sy = 0.0; var s = 0.0; var n = 0
    for (c in px) {
        if ((c ushr 24) < 128) continue
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[2] < 0.12f || (hsv[0] / 15f).toInt() % 24 != best) continue
        val a = Math.toRadians(hsv[0].toDouble())
        sx += Math.cos(a); sy += Math.sin(a); s += hsv[1]; n++
    }
    if (n == 0) return null
    val hue = ((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360).toFloat()
    val sat = (s / n).toFloat().coerceIn(0.35f, 0.9f)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 0.62f)))
}
