package com.musicly.musicly.net

import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.data.arr
import com.musicly.musicly.data.get
import com.musicly.musicly.data.long
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import com.musicly.musicly.data.toJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

class TelegramError(val code: Int, val description: String, val retryAfter: Int? = null) :
    Exception("Telegram $code: $description")

/** A channel the bot was made admin of. */
data class AdminChannel(val id: Long, val title: String)

/**
 * Talks to the Telegram Bot API straight from the app; no server involved.
 * The token is typed in by the user and stays on their phone.
 */
class TelegramBot(val token: String) {
    private class CachedUrl(val url: String, val expires: Long)

    private val files = ConcurrentHashMap<String, CachedUrl>()

    /** Calls a Bot API method and returns its `result`; throws [TelegramError]. */
    suspend fun call(method: String, params: Map<String, Any?> = emptyMap()): JsonElement {
        val body = toJson(params.filterValues { it != null }).toString()
        val (code, text) = Http.postJson("$host/bot$token/$method", body, 30).use { it.code to it.body.string() }
        return parseReply(code, text)
    }

    suspend fun getMe(): JsonObject = call("getMe").obj() ?: JsonObject(emptyMap())

    /** [channel] can be `@name`, a t.me link, or a numeric id like -100123.... */
    suspend fun getChat(channel: String): JsonObject =
        call("getChat", mapOf("chat_id" to normaliseChat(channel))).obj() ?: JsonObject(emptyMap())

    suspend fun getUpdates(offset: Long): List<JsonObject> =
        call("getUpdates", mapOf("offset" to offset, "timeout" to 0, "limit" to 100, "allowed_updates" to allowed))
            .arr().orEmpty().mapNotNull { it.obj() }

    /**
     * Channels this bot was recently made an admin of, newest first, read
     * from the "bot was added" updates without consuming them.
     */
    suspend fun adminChannels(): List<AdminChannel> {
        val updates = call("getUpdates", mapOf("offset" to -100, "timeout" to 0, "allowed_updates" to allowed))
            .arr().orEmpty().mapNotNull { it.obj() }
        return channelsFrom(updates)
    }

    /** Signed download link for a file; Telegram keeps them valid for at least an hour. */
    suspend fun fileUrl(fileId: String): String {
        val hit = files[fileId]
        if (hit != null && hit.expires > System.currentTimeMillis()) return hit.url
        val f = call("getFile", mapOf("file_id" to fileId)).obj()
        val path = f["file_path"].str() ?: throw TelegramError(400, "File not available")
        val url = "$host/file/bot$token/$path"
        files[fileId] = CachedUrl(url, System.currentTimeMillis() + 50 * 60_000L)
        return url
    }

    companion object {
        /** Always api.telegram.org in real builds; tests can point it elsewhere. */
        @Volatile
        var host: String = "https://api.telegram.org"

        /** Bots can download files up to 20 MB. */
        const val maxFileSize = 20 * 1024 * 1024

        private val allowed = listOf("channel_post", "edited_channel_post", "message", "my_chat_member")

        /** Result of a Bot API answer; throws [TelegramError] when `ok` is not true. */
        internal fun parseReply(httpCode: Int, text: String): JsonElement {
            val body = parseJson(text).obj() ?: throw IllegalStateException("Unexpected Telegram answer")
            val ok = (body["ok"] as? JsonPrimitive)?.content == "true"
            if (ok) return body["result"] ?: JsonNull
            throw TelegramError(
                body["error_code"].long()?.toInt() ?: httpCode,
                body["description"].str() ?: "Unknown error",
                body["parameters"]["retry_after"].long()?.toInt(),
            )
        }

        private val prefix = Regex("^(https?://)?(t\\.me|telegram\\.me)/")
        private val sPrefix = Regex("^s/")
        private val numeric = Regex("^-?\\d+$")

        /** Returns a Long for numeric ids, otherwise an `@name` String. */
        fun normaliseChat(input: String): Any {
            var s = input.trim()
            s = s.replaceFirst(prefix, "")
            s = s.replaceFirst(sPrefix, "")
            s = s.split('/', '?').first()
            if (numeric.matches(s)) s.toLongOrNull()?.let { return it }
            return if (s.startsWith("@")) s else "@$s"
        }

        fun channelsFrom(updates: List<JsonElement>): List<AdminChannel> {
            val found = LinkedHashMap<Long, String>()
            for (u in updates) {
                val m = u["my_chat_member"].obj()
                val chat = m["chat"].obj()
                if (chat == null || chat["type"].str() != "channel") continue
                val id = chat["id"].long() ?: continue
                val status = m["new_chat_member"]["status"].str()
                found.remove(id)
                if (status == "administrator") found[id] = chat["title"].str() ?: "Channel"
            }
            return found.entries.toList().reversed().map { AdminChannel(it.key, it.value) }
        }

        private fun audioOf(m: JsonElement?): JsonObject? = m["audio"].obj() ?: m["document"].obj()

        /** Song from a channel post or a forwarded message; null if it isn't audio or is too big for a bot to stream. */
        fun trackFrom(m: JsonElement?, channelName: String): Track? {
            val audio = audioOf(m) ?: return null
            val mime = (audio["mime_type"].str() ?: "").lowercase()
            if (m["audio"].obj() == null && !mime.startsWith("audio/")) return null
            val size = audio["file_size"].long() ?: 0L
            if (size > maxFileSize) return null

            val fileName = (audio["file_name"].str() ?: "").replace('_', ' ')
            val base = if (fileName.contains('.')) fileName.substring(0, fileName.lastIndexOf('.')) else fileName
            var title = (audio["title"].str() ?: "").trim()
            var artist = (audio["performer"].str() ?: "").trim()
            if (title.isEmpty() && base.contains(" - ")) {
                artist = base.substring(0, base.indexOf(" - ")).trim()
                title = base.substring(base.indexOf(" - ") + 3).trim()
            }
            if (title.isEmpty()) title = base.trim().ifEmpty { "Track" }
            val thumb = audio["thumbnail"].obj() ?: audio["thumb"].obj()
            return Track(
                id = "tg:${audio["file_unique_id"].str()}",
                title = title,
                artist = artist.ifEmpty { "Unknown artist" },
                source = TrackSource.telegram,
                uri = audio["file_id"].str() ?: return null,
                artworkUrl = thumb?.let { "tgthumb:${it["file_id"].str()}" },
                duration = (audio["duration"].long() ?: 0L) * 1000,
                album = channelName,
                mime = if (mime.startsWith("audio/")) mime else "audio/mpeg",
            )
        }

        fun isTooBig(m: JsonElement?): Boolean {
            val a = audioOf(m) ?: return false
            return (a["file_size"].long() ?: 0L) > maxFileSize
        }
    }
}

/** Lets widgets turn `tgthumb:<file_id>` cover references into real links. */
object TelegramFiles {
    @Volatile
    var bot: TelegramBot? = null

    fun isThumb(url: String?): Boolean = url != null && url.startsWith("tgthumb:")

    private val thumbs = ConcurrentHashMap<String, String>()

    /** Already-known link, so covers draw without waiting. */
    fun cachedThumb(ref: String): String? = thumbs[ref]

    suspend fun resolveThumb(ref: String): String? {
        val b = bot ?: return null
        return try {
            b.fileUrl(ref.substring(8)).also { thumbs[ref] = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
