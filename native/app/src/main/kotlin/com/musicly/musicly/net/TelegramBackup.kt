package com.musicly.musicly.net

import com.musicly.musicly.data.Store
import com.musicly.musicly.data.TelegramChannel
import com.musicly.musicly.data.arr
import com.musicly.musicly.data.get
import com.musicly.musicly.data.jsonOf
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import com.musicly.musicly.data.toJson
import com.musicly.musicly.net.Http.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps a copy of the whole library (likes, playlists, history, settings,
 * the Telegram song list) as one pinned file in the user's private chat
 * with their bot, so a reinstall can bring everything back. No server.
 */
class TelegramBackup(val bot: TelegramBot, val chatId: Long) {

    /**
     * Uploads the snapshot. Edits the existing pinned backup when there is one,
     * so the chat keeps a single file. Returns the message id.
     */
    suspend fun upload(snap: JsonObject, existing: Long? = null): Long {
        val bytes = snap.toString().toByteArray(Charsets.UTF_8)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val caption = "🎵 Musicly backup · $stamp\nKeep this pinned. It restores your library after a reinstall."
        if (existing != null) {
            try {
                multipart(
                    "editMessageMedia",
                    mapOf(
                        "chat_id" to "$chatId",
                        "message_id" to "$existing",
                        "media" to jsonOf("type" to "document", "media" to "attach://backup", "caption" to caption).toString(),
                    ),
                    "backup",
                    bytes,
                )
                return existing
            } catch (e: TelegramError) {
                // Unchanged content is fine; a deleted message means start over.
                if (e.description.contains("not modified")) return existing
            }
        }
        val sent = multipart(
            "sendDocument",
            mapOf("chat_id" to "$chatId", "caption" to caption, "disable_notification" to "true"),
            "document",
            bytes,
        ).obj()
        val id = sent["message_id"].let { (it as? JsonPrimitive)?.longOrNull } ?: throw TelegramError(500, "No message id")
        try {
            bot.call("pinChatMessage", mapOf("chat_id" to chatId, "message_id" to id, "disable_notification" to true))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return id
    }

    /** The pinned backup in the bot chat as JSON with an added `_messageId`, if any. */
    suspend fun latest(): JsonObject? {
        val chat = bot.call("getChat", mapOf("chat_id" to chatId)).obj()
        val pinned = chat["pinned_message"].obj()
        val doc = pinned["document"].obj()
        if (doc == null || doc["file_name"].str() != FILE_NAME) return null
        val url = bot.fileUrl(doc["file_id"].str() ?: return null)
        val text = Http.get(url, timeoutSeconds = 30).use {
            if (it.code != 200) return null
            it.body.string()
        }
        val j = parseJson(text).obj() ?: return null
        if (j["app"].str() != "musicly") return null
        return JsonObject(j + ("_messageId" to (pinned!!["message_id"] ?: JsonNull)))
    }

    private suspend fun multipart(method: String, fields: Map<String, String>, fileField: String, bytes: ByteArray): JsonElement {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(fileField, FILE_NAME, bytes.toRequestBody("application/octet-stream".toMediaType()))
        }.build()
        val req = Request.Builder().url("${TelegramBot.host}/bot${bot.token}/$method").post(form).build()
        val (code, text) = Http.withTimeout(60).newCall(req).await().use { it.code to it.body.string() }
        return TelegramBot.parseReply(code, text)
    }

    companion object {
        const val FILE_NAME = "musicly-backup.json"
        private val metaKeys = setOf("backup_msg", "backup_at")

        /**
         * Keys that never leave the phone: the user's own Telegram app key
         * (tg_api_id, tg_api_hash) and anything about their login (tg_*).
         */
        fun isPrivateKey(k: String) = k.startsWith("tg_")

        /**
         * Everything in the app's preferences, minus the bot token (the user types
         * it in again when reconnecting), the Telegram account key and session
         * (tg_*) and backup bookkeeping.
         */
        fun snapshot(store: Store): JsonObject {
            val data = LinkedHashMap<String, JsonElement>()
            for ((k, v) in store.dump()) {
                if (k in metaKeys || v == null || isPrivateKey(k)) continue
                if (k == "telegram" && v is String) {
                    val j = try {
                        parseJson(v).obj() ?: continue
                    } catch (_: Exception) {
                        continue
                    }
                    data[k] = JsonPrimitive(JsonObject(j - "token").toString())
                } else {
                    data[k] = toJson(v)
                }
            }
            return jsonOf(
                "app" to "musicly",
                "version" to 1,
                "savedAt" to LocalDateTime.now().toString(),
                "prefs" to JsonObject(data),
            )
        }

        /**
         * Writes a backup into the store. The current bot token wins; channels
         * and their song lists are merged with the ones in the backup.
         */
        fun apply(store: Store, backup: JsonObject) {
            val prefs = backup["prefs"].obj() ?: return
            val values = LinkedHashMap<String, Any?>()
            for ((k, v) in prefs) {
                // Saved-song links are rebuilt from the files on the phone instead.
                if (k == "telegram" || k == "downloads" || isPrivateKey(k)) continue
                val p = v as? JsonPrimitive ?: continue
                if (p is JsonNull) continue
                when {
                    p.isString -> values[k] = p.content
                    p.booleanOrNull != null -> values[k] = p.booleanOrNull
                    p.longOrNull != null -> values[k] = p.longOrNull
                    else -> values[k] = p.content // doubles are kept as text
                }
            }
            store.putAll(values)

            val current = store.string("telegram")
            val saved = prefs["telegram"].str()
            if (current != null && saved != null) {
                val cur = parseJson(current).obj() ?: return
                val old = parseJson(saved).obj() ?: return
                val channels = TelegramChannel.merge(
                    TelegramChannel.listFromSettings(cur),
                    TelegramChannel.listFromSettings(old),
                    sameBot = old["token"].str() == cur["token"].str(),
                )
                val merged = LinkedHashMap<String, JsonElement>(cur)
                merged["userChat"] = cur["userChat"].takeIf { it != null && it !is JsonNull } ?: old["userChat"] ?: JsonNull
                merged["channels"] = JsonArray(channels.map { it.toJson() })
                merged["forwarded"] = union(cur["forwarded"], old["forwarded"])
                store.put("telegram", JsonObject(merged).toString())
            }
        }

        private fun union(a: JsonElement?, b: JsonElement?): JsonArray {
            val out = a.arr().orEmpty().toMutableList()
            val ids = out.map { it["id"] }.toMutableSet()
            for (t in b.arr().orEmpty()) if (ids.add(t["id"])) out += t
            return JsonArray(out)
        }
    }
}
