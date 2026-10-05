package com.musicly.musicly

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.util.Size
import com.ryanheise.audioservice.AudioServiceActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : AudioServiceActivity() {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var pendingPermission: MethodChannel.Result? = null

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "musicly/media")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestPermission" -> requestAudioPermission(result)
                    "requestNotifications" -> requestNotificationPermission(result)
                    "scan" -> io.execute {
                        val songs = try { scan() } catch (e: Exception) { null }
                        main.post {
                            if (songs == null) result.error("scan", "Could not read music", null)
                            else result.success(songs)
                        }
                    }
                    "artwork" -> {
                        val id = (call.argument<Number>("id") ?: 0).toLong()
                        io.execute {
                            val bytes = try { artwork(id) } catch (e: Exception) { null }
                            main.post { result.success(bytes) }
                        }
                    }
                    "saveAudio" -> {
                        val url = call.argument<String>("url")!!
                        val name = call.argument<String>("name")!!
                        val mime = call.argument<String>("mime") ?: "audio/mpeg"
                        io.execute {
                            val saved = try { saveAudio(url, name, mime) } catch (e: Exception) { null }
                            main.post {
                                if (saved == null) result.error("save", "Could not save the song", null)
                                else result.success(saved)
                            }
                        }
                    }
                    "findAudio" -> {
                        val name = call.argument<String>("name")!!
                        io.execute {
                            val found = try { findAudio(name) } catch (e: Exception) { null }
                            main.post { result.success(found) }
                        }
                    }
                    "saveBackup" -> {
                        val json = call.argument<String>("json")!!
                        io.execute {
                            val ok = try { saveBackup(json) } catch (e: Exception) { false }
                            main.post { result.success(ok) }
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /** Reading music, plus writing to shared folders on Android 9 and older. */
    private val neededPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT < 29)
            arrayOf(audioPermission, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(audioPermission)

    /** Android 13+: lets the player notification (and lock-screen controls) show. */
    private fun requestNotificationPermission(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            result.success(true)
            return
        }
        pendingPermission?.success(false)
        pendingPermission = result
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4711)
    }

    private fun requestAudioPermission(result: MethodChannel.Result) {
        if (neededPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            result.success(true)
            return
        }
        pendingPermission?.success(false)
        pendingPermission = result
        requestPermissions(neededPermissions, 4711)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4711) {
            pendingPermission?.success(
                grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            )
            pendingPermission = null
        }
    }

    private fun scan(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
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
        contentResolver.query(
            collection, projection, selection, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out.add(
                    mapOf(
                        "id" to id,
                        "title" to c.getString(titleCol),
                        "artist" to c.getString(artistCol),
                        "album" to c.getString(albumCol),
                        "albumId" to c.getLong(albumIdCol),
                        "duration" to c.getLong(durCol),
                        "uri" to ContentUris.withAppendedId(collection, id).toString(),
                    )
                )
            }
        }
        return out
    }

    private fun artwork(id: Long): ByteArray? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
            try { contentResolver.loadThumbnail(uri, Size(512, 512), null) } catch (e: Exception) { null }
        } else {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (e: Exception) { null } finally { retriever.release() }
        }
        if (bmp == null) return null
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, stream)
        return stream.toByteArray()
    }

    // ---- Saving channel songs and backups to shared storage -----------------

    private val musicDir = "Musicly"

    /** Downloads [url] into Music/Musicly/[name]. Returns {id, uri}. */
    private fun saveAudio(url: String, name: String, mime: String): Map<String, Any>? {
        findAudio(name)?.let { return it }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$musicDir")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: return null
            try {
                contentResolver.openOutputStream(uri)!!.use { download(url, it) }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
            val id = ContentUris.parseId(uri)
            return mapOf("id" to id, "uri" to ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString())
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), musicDir)
        dir.mkdirs()
        val file = File(dir, name)
        val tmp = File(dir, "$name.part")
        FileOutputStream(tmp).use { download(url, it) }
        tmp.renameTo(file)
        val lock = Object()
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mime)) { _, u ->
            synchronized(lock) { scanned = u; lock.notifyAll() }
        }
        synchronized(lock) { if (scanned == null) lock.wait(15000) }
        val u = scanned ?: return null
        return mapOf("id" to ContentUris.parseId(u), "uri" to u.toString())
    }

    /** A song Musicly saved earlier (also after a reinstall, with permission). */
    private fun findAudio(name: String): Map<String, Any>? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf(name, "${Environment.DIRECTORY_MUSIC}/$musicDir%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
            args = arrayOf("%/${Environment.DIRECTORY_MUSIC}/$musicDir/$name")
        }
        contentResolver.query(collection, arrayOf(MediaStore.Audio.Media._ID), selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return mapOf("id" to id, "uri" to ContentUris.withAppendedId(collection, id).toString())
            }
        }
        return null
    }

    private fun download(url: String, out: OutputStream) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        conn.inputStream.use { it.copyTo(out, 64 * 1024) }
        conn.disconnect()
    }

    /** Writes Download/Musicly/musicly-backup.json, replacing Musicly's own copy. */
    private fun saveBackup(json: String): Boolean {
        val name = "musicly-backup.json"
        val bytes = json.toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val rel = "${Environment.DIRECTORY_DOWNLOADS}/$musicDir/"
            var target: Uri? = null
            contentResolver.query(
                collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(name, rel), null
            )?.use { c -> if (c.moveToFirst()) target = ContentUris.withAppendedId(collection, c.getLong(0)) }
            if (target == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, rel)
                }
                target = contentResolver.insert(collection, values) ?: return false
            }
            contentResolver.openOutputStream(target!!, "wt")!!.use { it.write(bytes) }
            return true
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), musicDir)
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        return true
    }
}
