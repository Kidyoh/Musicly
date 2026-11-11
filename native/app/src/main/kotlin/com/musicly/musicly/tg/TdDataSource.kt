package com.musicly.musicly.tg

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Plays audio that TDLib is downloading, for URIs like `td://<chatId>/<messageId>`
 * (chat ids are negative for channels and groups, so `td://-1001234567890/42`).
 *
 * [opener] starts (or joins) the download and returns once the first bytes exist; see
 * [TgBackend.openAudio]. Bytes are read with random access from the growing local file;
 * when the reader is ahead of the download, [TgOpenFile.await] is asked for more
 * (and re-targets the download when the player seeks).
 */
@UnstableApi
class TdDataSource(private val opener: suspend (chatId: Long, messageId: Long) -> TgOpenFile) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var file: TgOpenFile? = null
    private var raf: RandomAccessFile? = null
    private var openedPath: String? = null
    private var position = 0L
    private var remaining = 0L
    private var knownEnd = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val u = dataSpec.uri
        uri = u
        transferInitializing(dataSpec)
        val (chatId, messageId) = parse(u) ?: throw IOException("Not a td:// address: $u")
        val f = try {
            runBlocking { opener(chatId, messageId) }
        } catch (e: InterruptedException) {
            throw InterruptedIOException()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Telegram could not open the file", e)
        }
        file = f
        try {
            val total = f.totalBytes
            if (total <= 0) throw IOException("Telegram did not tell the size of the file")
            if (dataSpec.position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            position = dataSpec.position
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, total - position) else total - position
            knownEnd = f.available()
            openRaf(f.path)
        } catch (e: Throwable) {
            closeQuietly()
            throw e
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun openRaf(path: String) {
        raf?.close()
        raf = RandomAccessFile(path, "r")
        openedPath = path
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: throw IOException("Data source is not open")
        val want = minOf(length.toLong(), remaining).toInt()
        if (position + want > knownEnd) {
            // Reader caught up with the download (or jumped): wait for the bytes.
            knownEnd = try {
                runBlocking { f.await(position, position + want) }
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            } catch (e: IOException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw InterruptedIOException()
            } catch (e: Exception) {
                throw IOException(e.message ?: "Download failed", e)
            }
        }
        val n = minOf(want.toLong(), knownEnd - position).toInt()
        if (n <= 0) throw IOException("The download stopped before this part of the song arrived")
        // TDLib moves the file when it completes; the open handle keeps working, but re-open if the path changed under us.
        val r = raf ?: throw IOException("Data source is not open")
        var read: Int
        try {
            r.seek(position)
            read = r.read(buffer, offset, n)
        } catch (e: IOException) {
            openRaf(f.path)
            raf!!.seek(position)
            read = raf!!.read(buffer, offset, n)
        }
        if (read < 0) throw IOException("The song file ended early")
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        closeQuietly()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeQuietly() {
        try {
            raf?.close()
        } catch (_: IOException) {
        }
        raf = null
        file?.close()
        file = null
    }

    class Factory(private val opener: suspend (Long, Long) -> TgOpenFile) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(opener)
    }

    companion object {
        const val SCHEME = "td"

        fun uriFor(chatId: Long, messageId: Long): String = "$SCHEME://$chatId/$messageId"

        /** `td://<chatId>/<messageId>` -> (chatId, messageId); null if it isn't one. */
        fun parse(uri: Uri): Pair<Long, Long>? {
            if (uri.scheme != SCHEME) return null
            val parts = uri.schemeSpecificPart.removePrefix("//").trim('/').split('/')
            if (parts.size != 2) return null
            val chat = parts[0].toLongOrNull() ?: return null
            val msg = parts[1].toLongOrNull() ?: return null
            return chat to msg
        }
    }
}

/** Sends `td` addresses to [td] and everything else to [other]. */
@UnstableApi
class SchemeRoutingDataSource(private val other: DataSource, private val td: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        other.addTransferListener(transferListener)
        td.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.scheme == TdDataSource.SCHEME) td else other
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("Data source is not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        val a = active ?: return
        active = null
        a.close()
    }
}
