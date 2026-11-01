package com.musicly.musicly.net

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.net.Http.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The phone itself: music library (MediaStore), saving songs and backups to
 * shared storage, the player notification channel, Wi-Fi multicast lock.
 * Requesting permissions is done by the Activity; see [neededPermissions].
 */
class Device(context: Context) {
    /** True on Android, where the phone's music library exists. */
    val supported: Boolean get() = true

    private val ctx: Context = context.applicationContext
    private val resolver get() = ctx.contentResolver

    // ---- Permissions --------------------------------------------------------

    val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Reading music, plus writing to shared folders on Android 9 and older. */
    val neededPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT < 29) arrayOf(audioPermission, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(audioPermission)

    fun hasPermission(): Boolean =
        neededPermissions.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Android 13+: lets the player notification (and lock-screen controls) show. */
    val notificationPermission: String? get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ---- Notification channel ----------------------------------------------

    /**
     * Creates the player channel at default importance with no sound, so the
     * player stays visible on the lock screen (many phones hide "silent"
     * low-importance notifications there) without ever making a noise.
     */
    fun createPlayerChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel("com.musicly.audio")
        if (manager.getNotificationChannel(PLAYER_CHANNEL) != null) return
        val channel = NotificationChannel(PLAYER_CHANNEL, "Now playing", NotificationManager.IMPORTANCE_DEFAULT).apply {
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
     * Opens this app's notification settings, where some phones keep a
     * separate "show on lock screen" switch.
     */
    fun openNotificationSettings(): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        }
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---- Multicast ----------------------------------------------------------

    private var multicast: WifiManager.MulticastLock? = null

    /** Lets Jam beacons through on phones that filter Wi-Fi broadcasts. */
    @Synchronized
    fun multicastLock(on: Boolean) {
        try {
            if (on) {
                if (multicast == null) {
                    val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    multicast = wifi.createMulticastLock("musicly-jam").apply { setReferenceCounted(false) }
                }
                multicast?.acquire()
            } else {
                multicast?.let { if (it.isHeld) it.release() }
            }
        } catch (_: Exception) {
        }
    }

    // ---- Library ------------------------------------------------------------

    /** Copies a phone song (content:// link) to a temporary file; null if it can't be read. */
    suspend fun copyToCache(uri: String, name: String): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "jam-send").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, name.replace(Regex("[^\\w. -]"), "_"))
            resolver.openInputStream(Uri.parse(uri))!!.use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            out.absolutePath
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** All songs in MediaStore (music, 30 s or longer), by title. Throws if the library can't be read. */
    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        try {
            scanBlocking()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not read music", e)
        }
    }

    private fun scanBlocking(): List<Track> {
        val out = ArrayList<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        resolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(artistCol) ?: ""
                out += Track(
                    id = "ms:$id",
                    title = c.getString(titleCol) ?: "Unknown",
                    artist = if (artist.isEmpty() || artist == "<unknown>") "Unknown artist" else artist,
                    source = TrackSource.device,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    duration = c.getLong(durCol),
                    album = c.getString(albumCol),
                    albumId = "ms:${c.getLong(albumIdCol)}",
                    mediaId = id,
                )
            }
        }
        return out
    }

    // Embedded album art, cached for the session (most recent 300).
    private val art = object : LinkedHashMap<Long, ByteArray?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray?>?) = size > 300
    }

    /** Embedded album art as JPEG bytes, or null. */
    suspend fun artwork(mediaId: Long): ByteArray? {
        synchronized(art) { if (art.containsKey(mediaId)) return art[mediaId] }
        val bytes = withContext(Dispatchers.IO) {
            try {
                artworkBlocking(mediaId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        synchronized(art) { art[mediaId] = bytes }
        return bytes
    }

    private fun artworkBlocking(id: Long): ByteArray? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
            try {
                resolver.loadThumbnail(uri, Size(512, 512), null)
            } catch (_: Exception) {
                null
            }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, uri)
                retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
        if (bmp == null) return null
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, stream)
        return stream.toByteArray()
    }

    // ---- Saving channel songs and backups to shared storage ----------------

    private class Saved(val id: Long, val uri: String)

    private fun copyOf(t: Track, m: Saved) = Track(
        id = "ms:${m.id}", title = t.title, artist = t.artist, source = TrackSource.device,
        uri = m.uri, duration = t.duration, album = t.album, mediaId = m.id,
    )

    /**
     * Saves a song into Music/Musicly (or finds the copy saved earlier) and
     * returns it as a phone track. Throws if it could not be saved.
     */
    suspend fun saveAudio(url: String, fileName: String, like: Track, mime: String = "audio/mpeg"): Track =
        copyOf(like, withContext(Dispatchers.IO) { saveAudioBlocking(url, fileName, mime) } ?: throw IOException("Could not save the song"))

    /** A copy saved by an earlier install, so it isn't downloaded twice. */
    suspend fun findAudio(fileName: String, like: Track): Track? = withContext(Dispatchers.IO) {
        try {
            findAudioBlocking(fileName)?.let { copyOf(like, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun saveAudioBlocking(url: String, name: String, mime: String): Saved? {
        findAudioBlocking(name)?.let { return it }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$MUSIC_DIR")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)!!.use { download(url, it) }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            val id = ContentUris.parseId(uri)
            return Saved(id, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString())
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), MUSIC_DIR)
        dir.mkdirs()
        val file = File(dir, name)
        val tmp = File(dir, "$name.part")
        FileOutputStream(tmp).use { download(url, it) }
        tmp.renameTo(file)
        val scanned: Uri? = withTimeoutOrNull(15000) {
            suspendCancellableCoroutine<Uri?> { cont ->
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime)) { _, u ->
                    if (cont.isActive) cont.resume(u)
                }
            }
        }
        val u = scanned ?: return null
        return Saved(ContentUris.parseId(u), u.toString())
    }

    /** A song Musicly saved earlier (also after a reinstall, with permission). */
    private fun findAudioBlocking(name: String): Saved? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf(name, "${Environment.DIRECTORY_MUSIC}/$MUSIC_DIR%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
            args = arrayOf("%/${Environment.DIRECTORY_MUSIC}/$MUSIC_DIR/$name")
        }
        resolver.query(collection, arrayOf(MediaStore.Audio.Media._ID), selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Saved(id, ContentUris.withAppendedId(collection, id).toString())
            }
        }
        return null
    }

    private suspend fun download(url: String, out: OutputStream) {
        val client = Http.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).await().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            res.body.byteStream().use { it.copyTo(out, 64 * 1024) }
        }
    }

    /** Writes Download/Musicly/musicly-backup.json (kept after uninstalling), replacing Musicly's own copy. */
    suspend fun saveBackupFile(json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            saveBackupBlocking(json)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun saveBackupBlocking(json: String): Boolean {
        val name = TelegramBackup.FILE_NAME
        val bytes = json.toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val rel = "${Environment.DIRECTORY_DOWNLOADS}/$MUSIC_DIR/"
            var target: Uri? = null
            resolver.query(
                collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(name, rel), null,
            )?.use { c -> if (c.moveToFirst()) target = ContentUris.withAppendedId(collection, c.getLong(0)) }
            if (target == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, rel)
                }
                target = resolver.insert(collection, values) ?: return false
            }
            resolver.openOutputStream(target!!, "wt")!!.use { it.write(bytes) }
            return true
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), MUSIC_DIR)
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        return true
    }

    companion object {
        const val PLAYER_CHANNEL = "com.musicly.player"
        private const val MUSIC_DIR = "Musicly"
    }
}
