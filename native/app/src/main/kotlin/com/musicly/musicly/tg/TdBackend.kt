package com.musicly.musicly.tg

import android.content.Context
import android.os.Build
import com.musicly.musicly.data.arr
import com.musicly.musicly.data.bool
import com.musicly.musicly.data.int
import com.musicly.musicly.data.long
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** TDLib answered with an `error`. [message] is Telegram's own text (never contains the user's secrets). */
class TdError(val code: Int, override val message: String) : IOException(message)

/**
 * Logs in as the user's own Telegram account with TDLib and reads audio from the
 * channels and groups they are in. Read-only: nothing is ever sent, joined, left,
 * forwarded or deleted. TDLib's native library is only loaded by [start], so the
 * app works (and starts fast) for people who never log in.
 *
 * Never logs phone numbers, codes, passwords or the api hash.
 */
class TdBackend(context: Context) : TgBackend {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val state = MutableStateFlow<TgAuth>(TgAuth.Off)
    override val auth: StateFlow<TgAuth> = state.asStateFlow()

    private val incoming = MutableSharedFlow<TgSong>(extraBufferCapacity = 64)

    private val lifecycle = Mutex()

    @Volatile
    private var client: TdJsonClient? = null

    // Kept only in memory; sent to TDLib in setTdlibParameters.
    private var apiId = 0
    private var apiHash = ""

    @Volatile
    private var paramsSent = false

    @Volatile
    private var loggingOut = false

    @Volatile
    private var closed: CompletableDeferred<Unit>? = null

    private var authSeq = AtomicInteger()

    private val dbDir get() = File(app.filesDir, "td")
    private val filesDir get() = File(app.cacheDir, "td-files")

    // ---- start / auth -------------------------------------------------------------------

    override suspend fun start(apiId: Int, apiHash: String) {
        lifecycle.withLock {
            if (client != null) return
            this.apiId = apiId
            this.apiHash = apiHash
            state.value = TgAuth.Starting
            val problem = withContext(Dispatchers.IO) {
                try {
                    TdJsonClient.load()
                } catch (e: Throwable) {
                    e.message ?: "native library missing"
                }
            }
            if (problem != null) {
                state.value = TgAuth.Failed("Telegram's library could not start on this phone ($problem).")
                return
            }
            withContext(Dispatchers.IO) { dbDir.mkdirs(); filesDir.mkdirs() }
            paramsSent = false
            loggingOut = false
            val c = try {
                TdJsonClient(::onUpdate)
            } catch (e: Throwable) {
                state.value = TgAuth.Failed("Telegram's library could not start on this phone.")
                return
            }
            client = c
            // The first request wakes TDLib up; it then reports the authorization state.
            try {
                call(buildJsonObject { put("@type", "setLogVerbosityLevel"); put("new_verbosity_level", 1) })
            } catch (_: Exception) {
            }
        }
    }

    private fun onUpdate(type: String, json: String) {
        when (type) {
            "updateAuthorizationState" -> {
                val st = parseJson(json).obj()?.get("authorization_state").obj() ?: return
                onAuthState(st)
            }
            "updateNewChat" -> parseJson(json).obj()?.get("chat").obj()?.let { rememberChat(it) }
            "updateChatTitle" -> parseJson(json).obj()?.let { o ->
                val id = o["chat_id"].long() ?: return
                chatInfo[id]?.let { chatInfo[id] = it.copy(title = o["title"].str() ?: it.title) }
            }
            "updateChatPhoto" -> parseJson(json).obj()?.let { o ->
                val id = o["chat_id"].long() ?: return
                chatInfo[id]?.let { chatInfo[id] = it.copy(photo = o["photo"].obj()?.get("small").obj()?.let(::fileSnap)) }
            }
            "updateSupergroup" -> parseJson(json).obj()?.get("supergroup").obj()?.let { g ->
                val id = g["id"].long() ?: return
                memberCounts["s$id"] = g["member_count"].int() ?: 0
            }
            "updateBasicGroup" -> parseJson(json).obj()?.get("basic_group").obj()?.let { g ->
                val id = g["id"].long() ?: return
                memberCounts["b$id"] = g["member_count"].int() ?: 0
            }
            "updateFile" -> if (slots.isNotEmpty()) {
                val f = parseJson(json).obj()?.get("file").obj() ?: return
                val id = f["id"].int() ?: return
                slots[id]?.state?.value = fileSnap(f)
            }
            "updateNewMessage" -> {
                // Cheap pre-check: only audio and documents are interesting.
                if (!json.contains("\"messageAudio\"") && !json.contains("\"messageDocument\"")) return
                val m = parseJson(json).obj()?.get("message").obj() ?: return
                val chatId = m["chat_id"].long() ?: return
                if (chatInfo[chatId]?.kind == null) return
                songFrom(m)?.let { incoming.tryEmit(it) }
            }
        }
    }

    private fun onAuthState(st: JsonObject) {
        if (loggingOut) {
            if (st["@type"].str() == "authorizationStateClosed") closed?.complete(Unit)
            return
        }
        val seq = authSeq.incrementAndGet()
        when (st["@type"].str()) {
            "authorizationStateWaitTdlibParameters" -> {
                if (paramsSent) return
                paramsSent = true
                scope.launch { sendParameters() }
            }
            "authorizationStateWaitPhoneNumber" -> state.value = TgAuth.WaitPhone()
            "authorizationStateWaitCode" -> {
                val info = st["code_info"].obj()
                val type = info?.get("type").obj()
                state.value = TgAuth.WaitCode(
                    phone = info?.get("phone_number").str()?.let { "+" + it.trimStart('+') } ?: "",
                    where = TdText.codeWhere(type?.get("@type").str()),
                    length = (type?.get("length").int() ?: 0).takeIf { it > 0 } ?: 5,
                    resendInSec = info?.get("timeout").int()?.takeIf { it > 0 },
                )
            }
            "authorizationStateWaitPassword" ->
                state.value = TgAuth.WaitPassword(hint = st["password_hint"].str()?.takeIf { it.isNotBlank() })
            "authorizationStateReady" -> scope.launch { becomeReady(seq) }
            "authorizationStateWaitRegistration" ->
                state.value = TgAuth.Failed("This phone number has no Telegram account yet. Create one in the Telegram app first, then log in here.")
            "authorizationStateWaitEmailAddress", "authorizationStateWaitEmailCode", "authorizationStateWaitPremiumPurchase",
            "authorizationStateWaitOtherDeviceConfirmation",
            -> state.value = TgAuth.Failed("Telegram asks for a login step this app can't do yet. Log in once with the Telegram app, then try again.")
            "authorizationStateLoggingOut", "authorizationStateClosing" -> Unit
            "authorizationStateClosed" -> {
                closed?.complete(Unit)
                if (client != null && !loggingOut) {
                    client = null
                    state.value = TgAuth.Failed("Telegram closed the connection. Try again.")
                }
            }
        }
    }

    private suspend fun sendParameters() {
        try {
            call(
                buildJsonObject {
                    put("@type", "setTdlibParameters")
                    put("use_test_dc", false)
                    put("database_directory", dbDir.absolutePath)
                    put("files_directory", filesDir.absolutePath)
                    put("database_encryption_key", "")
                    put("use_file_database", true)
                    put("use_chat_info_database", true)
                    put("use_message_database", false)
                    put("use_secret_chats", false)
                    put("api_id", apiId)
                    put("api_hash", apiHash)
                    put("system_language_code", Locale.getDefault().language.ifEmpty { "en" })
                    put("device_model", Build.MODEL.ifBlank { "Android" })
                    put("system_version", "")
                    put("application_version", appVersion())
                },
            )
        } catch (e: TdError) {
            state.value = TgAuth.Failed(TdText.human(e))
        } catch (e: IOException) {
            state.value = TgAuth.Failed("Could not start Telegram. Check your connection.")
        }
    }

    private fun appVersion(): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    private suspend fun becomeReady(seq: Int) {
        var name = "Telegram"
        var phone: String? = null
        try {
            val me = call(buildJsonObject { put("@type", "getMe") })
            val first = me["first_name"].str().orEmpty()
            val last = me["last_name"].str().orEmpty()
            name = "$first $last".trim().ifEmpty { me["usernames"].obj()?.get("editable_username").str() ?: "Telegram" }
            phone = me["phone_number"].str()?.takeIf { it.isNotBlank() }?.let { "+" + it.trimStart('+') }
        } catch (_: Exception) {
        }
        if (authSeq.get() == seq && !loggingOut) state.value = TgAuth.Ready(name, phone)
    }

    override suspend fun submitPhone(phone: String) {
        val cleaned = phone.trim().filter { it.isDigit() || it == '+' }
        authCall(
            buildJsonObject { put("@type", "setAuthenticationPhoneNumber"); put("phone_number", cleaned) },
        ) { msg -> state.value = TgAuth.WaitPhone(msg) }
    }

    override suspend fun submitCode(code: String) {
        authCall(
            buildJsonObject { put("@type", "checkAuthenticationCode"); put("code", code.filter { it.isDigit() }) },
        ) { msg -> (state.value as? TgAuth.WaitCode)?.let { state.value = it.copy(error = msg) } }
    }

    override suspend fun submitPassword(password: String) {
        authCall(
            buildJsonObject { put("@type", "checkAuthenticationPassword"); put("password", password) },
        ) { msg -> (state.value as? TgAuth.WaitPassword)?.let { state.value = it.copy(error = msg) } }
    }

    override suspend fun resendCode() {
        authCall(buildJsonObject { put("@type", "resendAuthenticationCode") }) { msg ->
            (state.value as? TgAuth.WaitCode)?.let { state.value = it.copy(error = msg) }
        }
    }

    /** One login step: no automatic retries (the user is looking at the screen); failures go back to the screen. */
    private suspend fun authCall(request: JsonObject, onError: (String) -> Unit) {
        if (client == null) {
            state.value = TgAuth.Failed("Telegram is not started.")
            return
        }
        try {
            call(request, retryFlood = false)
        } catch (e: TdError) {
            onError(TdText.human(e))
        } catch (e: IOException) {
            onError("Could not reach Telegram. Check your connection and try again.")
        }
    }

    override suspend fun logOut() {
        lifecycle.withLock {
            val c = client
            if (c != null) {
                loggingOut = true
                val done = CompletableDeferred<Unit>()
                closed = done
                try {
                    call(buildJsonObject { put("@type", "logOut") }, timeoutMs = 20_000)
                } catch (_: Exception) {
                    // Offline: still close and wipe locally; the session stays listed in Telegram's devices.
                    try {
                        call(buildJsonObject { put("@type", "close") }, timeoutMs = 10_000)
                    } catch (_: Exception) {
                    }
                }
                withTimeoutOrNull(15_000) { done.await() }
                c.close()
                client = null
            }
            slots.clear()
            chatInfo.clear()
            memberCounts.clear()
            withContext(Dispatchers.IO) {
                dbDir.deleteRecursively()
                filesDir.deleteRecursively()
            }
            paramsSent = false
            loggingOut = false
            closed = null
            state.value = TgAuth.Off
        }
    }

    // ---- requests -----------------------------------------------------------------------

    /**
     * Sends one request and returns the answer object. Throws [TdError] for TDLib errors.
     * On Telegram's "wait N seconds" (flood) answers it waits N+1 seconds and tries again
     * (up to 3 times) when [retryFlood] is set; a wait over 10 minutes is passed on as an error.
     */
    private suspend fun call(request: JsonObject, retryFlood: Boolean = true, timeoutMs: Long = 60_000): JsonObject {
        val c = client ?: throw IOException("Telegram is not started")
        var attempt = 0
        while (true) {
            val answer = parseJson(c.request(request, timeoutMs)).obj() ?: throw IOException("Unreadable answer from Telegram")
            if (answer["@type"].str() != "error") return answer
            val err = TdError(answer["code"].int() ?: 0, answer["message"].str() ?: "Unknown error")
            val wait = TdText.floodSeconds(err)
            if (!retryFlood || wait == null || wait > 600 || ++attempt > 3) throw err
            delay((wait + 1) * 1000L)
        }
    }

    // Chat lists and searches go through one lane, one at a time, with a little pause between them.
    private val lane = Semaphore(1)
    private var lastLane = 0L

    private suspend fun <T> gentle(block: suspend () -> T): T = lane.withPermit {
        val pause = 150 - (System.nanoTime() - lastLane) / 1_000_000
        if (pause > 0) delay(pause)
        try {
            block()
        } finally {
            lastLane = System.nanoTime()
        }
    }

    /** Calls made right after start() wait (up to 20 s) until TDLib has logged in. */
    private suspend fun ready() {
        if (state.value is TgAuth.Ready) return
        if (client == null) throw IOException("Telegram is not started")
        val s = withTimeoutOrNull(20_000) { state.first { it is TgAuth.Ready || it is TgAuth.Failed || it is TgAuth.Off || it is TgAuth.WaitPhone } }
        if (s !is TgAuth.Ready) throw IOException("Telegram is not logged in")
    }

    // ---- chats --------------------------------------------------------------------------

    private data class ChatInfo(
        val id: Long,
        val title: String,
        val kind: TgChatKind?,
        val groupKey: String?,
        val photo: FileSnap?,
    )

    private val chatInfo = ConcurrentHashMap<Long, ChatInfo>()
    private val memberCounts = ConcurrentHashMap<String, Int>()

    private fun rememberChat(chat: JsonObject) {
        val id = chat["id"].long() ?: return
        val type = chat["type"].obj()
        val (kind, key) = when (type?.get("@type").str()) {
            "chatTypeSupergroup" ->
                (if (type?.get("is_channel").bool() == true) TgChatKind.Channel else TgChatKind.Group) to "s${type?.get("supergroup_id").long()}"
            "chatTypeBasicGroup" -> TgChatKind.Group to "b${type?.get("basic_group_id").long()}"
            else -> null to null
        }
        chatInfo[id] = ChatInfo(id, chat["title"].str().orEmpty(), kind, key, chat["photo"].obj()?.get("small").obj()?.let(::fileSnap))
    }

    private suspend fun loadAllChats() {
        repeat(10) {
            try {
                gentle { call(buildJsonObject { put("@type", "loadChats"); put("limit", 100) }) }
            } catch (e: TdError) {
                if (e.code == 404) return // everything is loaded
                throw e
            }
        }
    }

    override suspend fun chats(): List<TgChat> {
        ready()
        loadAllChats()
        val ids = gentle {
            call(buildJsonObject { put("@type", "getChats"); put("limit", 1000) })
        }["chat_ids"].arr().orEmpty().mapNotNull { it.long() }
        val out = ArrayList<TgChat>()
        for (id in ids) {
            var info = chatInfo[id]
            if (info == null) {
                val chat = try {
                    gentle { call(buildJsonObject { put("@type", "getChat"); put("chat_id", id) }) }
                } catch (_: TdError) {
                    continue
                }
                rememberChat(chat)
                info = chatInfo[id]
            }
            val kind = info?.kind ?: continue
            out += TgChat(
                id = id,
                title = info.title.ifBlank { "Chat" },
                kind = kind,
                members = info.groupKey?.let { memberCounts[it] }?.takeIf { it > 0 },
                photoPath = info.photo?.takeIf { it.completed }?.path?.takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    override suspend fun chatPhoto(chatId: Long): String? {
        ready()
        val photo = (chatInfo[chatId] ?: return null).photo ?: return null
        if (photo.completed && photo.path.isNotEmpty()) return photo.path
        val f = try {
            gentle {
                call(
                    buildJsonObject {
                        put("@type", "downloadFile"); put("file_id", photo.id); put("priority", 1)
                        put("offset", 0); put("limit", 0); put("synchronous", true)
                    },
                )
            }
        } catch (_: IOException) {
            return null
        }
        val snap = fileSnap(f)
        chatInfo[chatId]?.let { chatInfo[chatId] = it.copy(photo = snap) }
        return snap.path.takeIf { snap.completed && it.isNotEmpty() }
    }

    // ---- songs --------------------------------------------------------------------------

    override suspend fun audioPage(chatId: Long, fromMessageId: Long, limit: Int): List<TgSong> {
        ready()
        val out = LinkedHashMap<Long, TgSong>()
        var from = fromMessageId
        var rounds = 0
        while (out.size < limit && rounds++ < 6) {
            val want = (limit - out.size).coerceIn(20, 100)
            val r = gentle {
                call(
                    buildJsonObject {
                        put("@type", "searchChatMessages")
                        put("chat_id", chatId)
                        put("query", "")
                        put("from_message_id", from)
                        put("offset", 0)
                        put("limit", want)
                        put("filter", buildJsonObject { put("@type", "searchMessagesFilterAudio") })
                    },
                )
            }
            val messages = r["messages"].arr().orEmpty()
            if (messages.isEmpty()) break
            for (m in messages) {
                val mo = m.obj() ?: continue
                val id = mo["id"].long() ?: continue
                // from_message_id itself is included in the answer; the caller wants songs below it.
                if (fromMessageId != 0L && id >= fromMessageId) continue
                songFrom(mo)?.let { out[id] = it }
            }
            val next = r["next_from_message_id"].long() ?: 0L
            if (next == 0L || next == from) break
            from = next
        }
        return out.values.take(limit)
    }

    override fun newAudio(): Flow<TgSong> = incoming.asSharedFlow()

    // ---- playing ------------------------------------------------------------------------

    private class Slot(val state: MutableStateFlow<FileSnap>) {
        val refs = AtomicInteger()
    }

    private val slots = ConcurrentHashMap<Int, Slot>()

    override suspend fun openAudio(chatId: Long, messageId: Long): TgOpenFile {
        ready()
        val msg = getMessageKnownChat(chatId, messageId)
        val file = TdText.audioFile(msg["content"].obj()) ?: throw IOException("That message has no audio")
        val id = file["id"].int() ?: throw IOException("That message has no audio")
        val total = (file["size"].long() ?: 0L).takeIf { it > 0 } ?: (file["expected_size"].long() ?: 0L)
        if (total <= 0) throw IOException("Unknown file size")
        val slot = slots.getOrPut(id) { Slot(MutableStateFlow(fileSnap(file))) }
        slot.refs.incrementAndGet()
        val open = OpenFile(id, slot, total)
        try {
            open.start()
        } catch (e: Throwable) {
            open.close()
            throw e
        }
        return open
    }

    private suspend fun getMessageKnownChat(chatId: Long, messageId: Long): JsonObject {
        val req = buildJsonObject { put("@type", "getMessage"); put("chat_id", chatId); put("message_id", messageId) }
        try {
            return call(req)
        } catch (e: TdError) {
            // After a restart TDLib only knows the chats it has loaded this run.
            if (e.code != 400 && e.code != 404) throw e
            loadAllChats()
            return call(req)
        }
    }

    private inner class OpenFile(val fileId: Int, val slot: Slot, override val totalBytes: Long) : TgOpenFile {
        private var closedFlag = false

        override val path: String get() = slot.state.value.path

        suspend fun start() {
            request(0)
            val end = await(0, 1)
            if (end <= 0) throw IOException("Telegram did not start the download")
        }

        private suspend fun request(offset: Long) {
            val f = call(
                buildJsonObject {
                    put("@type", "downloadFile"); put("file_id", fileId); put("priority", 32)
                    put("offset", offset); put("limit", 0); put("synchronous", false)
                },
            )
            slot.state.value = fileSnap(f)
        }

        override fun available(): Long = slot.state.value.let { if (it.completed) totalBytes else if (it.offset == 0L) it.prefix else 0L }

        override suspend fun await(offset: Long, upTo: Long): Long {
            val need = minOf(upTo, totalBytes)
            if (offset >= totalBytes) return totalBytes
            var s = slot.state.value
            if (!s.covers(offset, need, totalBytes)) {
                // Not heading there already: ask TDLib to continue from this offset.
                val heading = s.active && s.offset <= offset && offset <= s.offset + s.prefix + AHEAD
                if (!heading) request(offset)
            }
            while (true) {
                val mark = slot.state.value.downloaded
                val got = withTimeoutOrNull(STALL_MS) {
                    slot.state.first { it.covers(offset, need, totalBytes) }
                }
                if (got != null) return got.end(totalBytes)
                val now = slot.state.value
                if (now.downloaded == mark) return now.end(totalBytes) // stalled: nothing new for STALL_MS
            }
        }

        override fun close() {
            if (closedFlag) return
            closedFlag = true
            if (slot.refs.decrementAndGet() > 0) return
            // Give a quick re-open (seeking) time before stopping the download.
            scope.launch {
                delay(5_000)
                if (slot.refs.get() == 0 && !slot.state.value.completed && slot.state.value.active) {
                    try {
                        call(buildJsonObject { put("@type", "cancelDownloadFile"); put("file_id", fileId); put("only_if_pending", false) })
                    } catch (_: Exception) {
                    }
                }
                if (slot.refs.get() == 0) slots.remove(fileId, slot)
            }
        }
    }

    // ---- mapping ------------------------------------------------------------------------

    private fun songFrom(m: JsonObject): TgSong? = TdText.song(m)

    private companion object {
        const val STALL_MS = 30_000L
        const val AHEAD = 1L shl 20
    }
}

/** What TDLib says about one file right now. [offset]/[prefix]: bytes [offset, offset+prefix) can be read. */
internal data class FileSnap(
    val id: Int,
    val path: String,
    val size: Long,
    val active: Boolean,
    val completed: Boolean,
    val offset: Long,
    val prefix: Long,
    val downloaded: Long,
) {
    fun covers(from: Long, to: Long, total: Long): Boolean =
        completed || (path.isNotEmpty() && offset <= from && offset + prefix >= minOf(to, total) && offset + prefix > from)

    fun end(total: Long): Long = if (completed) total else offset + prefix
}

internal fun fileSnap(f: JsonObject): FileSnap {
    val local = f["local"].obj()
    val size = (f["size"].long() ?: 0L).takeIf { it > 0 } ?: (f["expected_size"].long() ?: 0L)
    return FileSnap(
        id = f["id"].int() ?: 0,
        path = local?.get("path").str().orEmpty(),
        size = size,
        active = local?.get("is_downloading_active").bool() ?: false,
        completed = local?.get("is_downloading_completed").bool() ?: false,
        offset = local?.get("download_offset").long() ?: 0L,
        prefix = local?.get("downloaded_prefix_size").long() ?: 0L,
        downloaded = local?.get("downloaded_size").long() ?: 0L,
    )
}

/** Pure helpers (no Android, no TDLib), so they can be unit-tested. */
internal object TdText {
    const val MAX_FILE = 2L * 1024 * 1024 * 1024
    private val audioExt = setOf("mp3", "m4a", "flac", "ogg", "oga", "opus", "wav", "aac", "wma", "alac")

    fun codeWhere(type: String?): String = when (type) {
        "authenticationCodeTypeTelegramMessage" -> "Telegram app"
        "authenticationCodeTypeSms", "authenticationCodeTypeSmsWord", "authenticationCodeTypeSmsPhrase" -> "SMS"
        "authenticationCodeTypeCall", "authenticationCodeTypeFlashCall", "authenticationCodeTypeMissedCall" -> "phone call"
        "authenticationCodeTypeFragment" -> "Fragment"
        else -> "Telegram"
    }

    /** Seconds Telegram wants us to wait, from a 429 / FLOOD_WAIT answer; null if it isn't one. */
    fun floodSeconds(e: TdError): Int? {
        val m = e.message
        Regex("FLOOD_WAIT_(\\d+)", RegexOption.IGNORE_CASE).find(m)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("retry after (\\d+)", RegexOption.IGNORE_CASE).find(m)?.let { return it.groupValues[1].toIntOrNull() }
        return if (e.code == 429) 5 else null
    }

    fun human(e: TdError): String {
        val m = e.message.uppercase()
        floodSeconds(e)?.let { return "Too many tries. Wait ${waitText(it)}." }
        return when {
            "PHONE_NUMBER_INVALID" in m -> "That phone number is not valid. Use the international format, like +251911234567."
            "PHONE_NUMBER_BANNED" in m -> "Telegram has banned this phone number."
            "PHONE_NUMBER_FLOOD" in m -> "Too many tries with this number. Try again later."
            "PHONE_NUMBER_UNOCCUPIED" in m -> "This phone number has no Telegram account."
            "PHONE_CODE_INVALID" in m || "CODE_INVALID" in m -> "That code is not right."
            "PHONE_CODE_EMPTY" in m -> "Type the code first."
            "PHONE_CODE_EXPIRED" in m || "CODE_EXPIRED" in m -> "That code has expired. Ask for a new one."
            "PASSWORD_HASH_INVALID" in m -> "Wrong password."
            "PASSWORD_EMPTY" in m -> "Type the password first."
            "API_ID_INVALID" in m || "API_ID" in m && "INVALID" in m || "VALID API_ID" in m -> "Telegram does not accept this app key (api_id / api_hash). Check them at my.telegram.org."
            "AUTH_RESTART" in m -> "Telegram asked to start the login again."
            e.code == 401 || "UNAUTHORIZED" in m -> "Telegram says you are not logged in."
            else -> "Telegram said: ${e.message}"
        }
    }

    private fun waitText(sec: Int): String = when {
        sec <= 120 -> "$sec seconds"
        sec < 7200 -> "about ${(sec + 59) / 60} minutes"
        else -> "about ${(sec + 3599) / 3600} hours"
    }

    /** The file object of an audio message, or of a document that is audio. */
    fun audioFile(content: JsonObject?): JsonObject? = when (content?.get("@type").str()) {
        "messageAudio" -> content?.get("audio").obj()?.get("audio").obj()
        "messageDocument" -> {
            val d = content?.get("document").obj()
            if (d != null && isAudioDocument(d)) d["document"].obj() else null
        }
        else -> null
    }

    private fun isAudioDocument(d: JsonObject): Boolean {
        val mime = d["mime_type"].str().orEmpty().lowercase()
        val ext = d["file_name"].str().orEmpty().substringAfterLast('.', "").lowercase()
        return mime.startsWith("audio/") || (mime.isEmpty() || mime == "application/octet-stream") && ext in audioExt
    }

    /** A song from a TDLib message object (audio, or a document that is audio); null if it is neither or too big. */
    fun song(m: JsonObject): TgSong? {
        val content = m["content"].obj() ?: return null
        val a: JsonObject
        val isAudio = content["@type"].str() == "messageAudio"
        a = when {
            isAudio -> content["audio"].obj() ?: return null
            content["@type"].str() == "messageDocument" -> content["document"].obj()?.takeIf { isAudioDocument(it) } ?: return null
            else -> return null
        }
        val file = (if (isAudio) a["audio"] else a["document"]).obj() ?: return null
        val size = (file["size"].long() ?: 0L).takeIf { it > 0 } ?: (file["expected_size"].long() ?: 0L)
        if (size <= 0 || size > MAX_FILE) return null
        val chatId = m["chat_id"].long() ?: return null
        val id = m["id"].long() ?: return null

        val rawName = a["file_name"].str().orEmpty()
        val fileName = rawName.replace('_', ' ')
        val base = if (fileName.contains('.')) fileName.substring(0, fileName.lastIndexOf('.')) else fileName
        var title = a["title"].str().orEmpty().trim()
        var artist = a["performer"].str().orEmpty().trim()
        if (title.isEmpty() && base.contains(" - ")) {
            artist = base.substring(0, base.indexOf(" - ")).trim()
            title = base.substring(base.indexOf(" - ") + 3).trim()
        }
        if (title.isEmpty()) title = base.trim().ifEmpty { "Track" }
        val mime = a["mime_type"].str().orEmpty().lowercase()
        return TgSong(
            chatId = chatId,
            messageId = id,
            title = title,
            artist = artist.ifEmpty { "Unknown artist" },
            durationSec = if (isAudio) a["duration"].int() ?: 0 else 0,
            mime = if (mime.startsWith("audio/")) mime else "audio/mpeg",
            sizeBytes = size,
            fileName = rawName,
            dateSec = (m["date"].long() ?: 0L),
        )
    }
}
