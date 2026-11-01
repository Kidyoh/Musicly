package com.musicly.musicly.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.musicly.musicly.net.Http
import com.musicly.musicly.net.TelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Loads the cover shown on the lock screen and in the notification: web
 * links, Telegram covers (`tgthumb:`), covers saved from a Jam friend (file
 * paths) and the art embedded in songs on the phone.
 */
class CoverLoader(private val context: Context) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String) = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
        return if (bmp != null) Futures.immediateFuture(bmp)
        else Futures.immediateFailedFuture(IllegalArgumentException("not an image"))
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val out = SettableFuture.create<Bitmap>()
        scope.launch {
            val bmp = load(uri)
            if (bmp != null) out.set(bmp) else out.setException(IllegalStateException("no cover"))
        }
        return out
    }

    private suspend fun load(uri: Uri): Bitmap? {
        val s = uri.toString()
        return try {
            when {
                s.startsWith("tgthumb:") -> {
                    val url = TelegramFiles.resolveThumb(s) ?: return null
                    download(url)
                }
                s.startsWith("http") -> download(s)
                s.startsWith("/") || s.startsWith("file:") -> BitmapFactory.decodeFile(uri.path)
                s.startsWith("content://media/external/audio/media/") -> embedded(uri)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun download(url: String): Bitmap? =
        Http.get(url).use { r ->
            if (r.code != 200) null else BitmapFactory.decodeStream(r.body.byteStream())
        }

    private fun embedded(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } catch (_: Exception) {
                null
            }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }
}
