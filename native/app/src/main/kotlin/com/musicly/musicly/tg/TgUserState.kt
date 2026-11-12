package com.musicly.musicly.tg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicly.musicly.data.Store
import com.musicly.musicly.data.TelegramChannel
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.data.arr
import com.musicly.musicly.data.get
import com.musicly.musicly.data.jsonOf
import com.musicly.musicly.data.long
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** True for songs read through the user's own Telegram account (never downloaded through the Bot API). */
val Track.isTgUser: Boolean get() = id.startsWith("tu:")

/**
 * The user's own Telegram account: which chats they picked, the songs found in
 * them, and the gentle background reading that keeps those lists fresh.
 *
 * Owned by Library, which passes in its scope and a way to save and redraw.
 * Persisted in Store key "tguser" ({chats:[...], done:[...]}); the app key
 * (tg_api_id / tg_api_hash) and the login session are never part of that JSON.
 */
class TgUserState(
    private val store: Store,
    private val scope: CoroutineScope,
    /** Called when songs or chats changed; [rebuild] asks for the home mix to be rebuilt too. */
    private val onChanged: (rebuild: Boolean) -> Unit,
    /** Asks the owner to save soon (which also calls [persist]). */
    private val onSave: () -> Unit,
) {
    /** Picked chats, with their songs newest first. */
    val chats = mutableListOf<TelegramChannel>()

    /** Chats whose whole history has been read. */
    private val done = mutableSetOf<Long>()

    private var provider: (() -> TgBackend)? = null
    val backend: TgBackend? get() = provider?.invoke()

    /** True once the user has logged in on this phone (kept between launches). */
    var loggedIn by mutableStateOf(false)
        private set
    var accountName by mutableStateOf<String?>(null)
        private set

    /** The chat being read right now, and how many songs were found so far in this run. */
    var reading by mutableStateOf<Long?>(null)
        private set
    var readingCount by mutableIntStateOf(0)
        private set

    /** Chats waiting for their turn (including the one being read). */
    var queued by mutableStateOf<Set<Long>>(emptySet())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private enum class Mode { Auto, Older }

    private val queue = ArrayDeque<Pair<Long, Mode>>()
    private var worker: Job? = null
    private var watchJob: Job? = null
    private var newJob: Job? = null
    private var needGap = false

    // ---- app key ---------------------------------------------------------------------------

    /** The key built into this app (see build.gradle.kts), or null in builds made without one. */
    val builtInKey: Pair<Int, String>?
        get() = if (com.musicly.musicly.BuildConfig.TG_API_ID > 0 && com.musicly.musicly.BuildConfig.TG_API_HASH.isNotBlank()) {
            com.musicly.musicly.BuildConfig.TG_API_ID to com.musicly.musicly.BuildConfig.TG_API_HASH
        } else null

    /** The app's built-in key, else an api_id / api_hash the user saved on this phone. */
    fun apiKey(): Pair<Int, String>? = builtInKey ?: savedKey()

    /** Only the key the user typed in and saved (ignores the built-in one). */
    fun savedKey(): Pair<Int, String>? {
        val id = store.long(KEY_ID)?.toInt() ?: return null
        val hash = store.string(KEY_HASH) ?: return null
        return if (id > 0 && hash.isNotBlank()) id to hash else null
    }

    fun saveKey(apiId: Int, apiHash: String) {
        store.put(KEY_ID, apiId.toLong())
        store.put(KEY_HASH, apiHash.trim())
    }

    fun clearKey() {
        store.remove(KEY_ID)
        store.remove(KEY_HASH)
    }

    // ---- start & login -----------------------------------------------------------------------

    /**
     * Gives the state its backend. When the user has logged in before and the
     * key is still saved, TDLib is started right away so songs keep flowing.
     */
    fun attach(backend: () -> TgBackend) {
        provider = backend
        if (loggedIn && apiKey() != null) scope.launch { begin() }
    }

    /** Starts TDLib with the saved key (and watches the login state). */
    suspend fun begin() {
        val (id, hash) = apiKey() ?: return
        val b = backend ?: return
        watch(b)
        try {
            b.start(id, hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not start Telegram."
            onChanged(false)
        }
    }

    /** Saves the key, then starts TDLib. */
    suspend fun begin(apiId: Int, apiHash: String) {
        saveKey(apiId, apiHash)
        begin()
    }

    /** Runs a call to the backend in the app's scope so it survives leaving the screen. */
    fun act(block: suspend TgBackend.() -> Unit): Job = scope.launch {
        val b = backend ?: return@launch
        watch(b)
        try {
            b.block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Something went wrong."
            onChanged(false)
        }
    }

    fun clearError() {
        error = null
    }

    private fun watch(b: TgBackend) {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            b.auth.collect { a ->
                when (a) {
                    is TgAuth.Ready -> {
                        error = null
                        accountName = a.name
                        loggedIn = true
                        store.put(KEY_READY, true)
                        store.put(KEY_NAME, a.name)
                        startNewAudio(b)
                        for (c in chats.toList()) enqueue(c.id, Mode.Auto)
                    }
                    is TgAuth.WaitPhone, is TgAuth.WaitCode, is TgAuth.WaitPassword -> {
                        newJob?.cancel()
                        if (loggedIn) {
                            loggedIn = false
                            store.remove(KEY_READY)
                            onChanged(false)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startNewAudio(b: TgBackend) {
        newJob?.cancel()
        newJob = scope.launch {
            try {
                b.newAudio().collect { s ->
                    val c = chat(s.chatId) ?: return@collect
                    if (add(c, listOf(s)) > 0) {
                        onChanged(true)
                        onSave()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Logs out, forgets the picked chats and stops all reading. The app key stays. */
    suspend fun logOut() {
        newJob?.cancel()
        watchJob?.cancel()
        worker?.cancel()
        queue.clear()
        reading = null
        queued = emptySet()
        needGap = false
        try {
            backend?.logOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        chats.clear()
        done.clear()
        loggedIn = false
        accountName = null
        error = null
        store.remove(KEY_STORE)
        store.remove(KEY_READY)
        store.remove(KEY_NAME)
        onChanged(true)
        onSave()
    }

    // ---- picked chats ------------------------------------------------------------------------

    fun chat(id: Long) = chats.firstOrNull { it.id == id }
    fun isPicked(id: Long) = chat(id) != null
    fun isDone(id: Long) = id in done

    fun setPicked(c: TgChat, on: Boolean) {
        if (on) {
            if (isPicked(c.id)) return
            chats += TelegramChannel(c.id, c.title)
            enqueue(c.id, Mode.Auto)
        } else {
            chats.removeAll { it.id == c.id }
            done.remove(c.id)
            queue.removeAll { it.first == c.id }
            queued = queued - c.id
        }
        onChanged(true)
        onSave()
    }

    /** Keeps picked chats' names up to date. */
    fun syncNames(all: List<TgChat>) {
        var any = false
        for (c in chats) all.firstOrNull { it.id == c.id }?.let { if (it.title != c.name) { c.name = it.title; any = true } }
        if (any) onSave()
    }

    /** Looks for songs newer than what's stored, in every picked chat. */
    fun refreshAll() {
        for (c in chats.toList()) enqueue(c.id, Mode.Auto)
    }

    /** Reads the next 300 older songs of one chat. */
    fun loadOlder(id: Long) = enqueue(id, Mode.Older)

    // ---- reading -----------------------------------------------------------------------------

    private fun enqueue(id: Long, mode: Mode) {
        if (queue.none { it.first == id && it.second == mode }) queue.add(id to mode)
        queued = queued + id
        if (worker?.isActive != true) worker = scope.launch { run() }
    }

    private suspend fun run() {
        try {
            while (true) {
                val (id, mode) = queue.removeFirstOrNull() ?: break
                val c = chat(id)
                if (c == null || backend == null || (mode == Mode.Older && id in done)) {
                    queued = queued - id
                    continue
                }
                reading = id
                readingCount = 0
                try {
                    read(c, mode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "Couldn't read ${c.name}: ${e.message ?: "Telegram didn't answer"}"
                }
                reading = null
                if (queue.none { it.first == id }) queued = queued - id
                onChanged(true)
                onSave()
            }
        } finally {
            reading = null
            needGap = false
        }
    }

    private suspend fun page(id: Long, from: Long): List<TgSong> {
        if (needGap) delay(GAP_MS)
        needGap = true
        return backend!!.audioPage(id, from, PAGE)
    }

    private suspend fun read(c: TelegramChannel, mode: Mode) {
        val first = c.tracks.isEmpty() && c.id !in done && c.latestPostId == 0L
        if (!first && mode == Mode.Auto) {
            // Only what's newer than the newest stored song.
            val base = c.latestPostId
            var from = 0L
            var pages = 0
            while (pages++ < MAX_REFRESH_PAGES) {
                val songs = page(c.id, from)
                val fresh = songs.filter { it.messageId > base }
                readingCount += add(c, fresh)
                if (songs.size < PAGE || songs.last().messageId <= base || chat(c.id) == null) break
                from = songs.last().messageId
                onChanged(false)
            }
            return
        }
        // First read, or "Load older songs": up to FIRST_SCAN songs, newest to oldest.
        var from = if (first) 0L else oldest(c)
        var got = 0
        while (got < FIRST_SCAN && chat(c.id) != null) {
            val songs = page(c.id, from)
            readingCount += add(c, songs)
            got += songs.size
            if (songs.size < PAGE) {
                done += c.id
                break
            }
            from = songs.last().messageId
            onChanged(false)
        }
    }

    private fun oldest(c: TelegramChannel): Long = c.tracks.minOfOrNull { messageIdOf(it) } ?: 0L

    /** Adds songs (deduped by id), keeps the list newest first, returns how many were new. */
    private fun add(c: TelegramChannel, songs: List<TgSong>): Int {
        if (songs.isEmpty()) return 0
        val have = c.tracks.mapTo(HashSet()) { it.id }
        var n = 0
        for (s in songs) {
            val t = trackOf(s, c.name)
            if (have.add(t.id)) {
                c.tracks += t
                n++
            }
            if (s.messageId > c.latestPostId) c.latestPostId = s.messageId
        }
        if (n > 0) c.tracks.sortByDescending { messageIdOf(it) }
        return n
    }

    // ---- saving ------------------------------------------------------------------------------

    fun persist() {
        if (chats.isEmpty() && done.isEmpty()) {
            store.remove(KEY_STORE)
            return
        }
        store.put(KEY_STORE, jsonOf("chats" to chats.map { it.toJson() }, "done" to done.toList()).toString())
    }

    fun load() {
        chats.clear()
        done.clear()
        loggedIn = store.bool(KEY_READY) == true
        accountName = store.string(KEY_NAME)
        try {
            val j = parseJson(store.string(KEY_STORE) ?: return).obj() ?: return
            chats += (j["chats"].arr() ?: return).mapNotNull { TelegramChannel.fromJson(it) }
            j["done"].arr()?.forEach { e -> e.long()?.let { done += it } }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val KEY_ID = "tg_api_id"
        const val KEY_HASH = "tg_api_hash"
        const val KEY_READY = "tg_ready"
        const val KEY_NAME = "tg_name"
        const val KEY_STORE = "tguser"

        const val PAGE = 50
        const val FIRST_SCAN = 300
        const val GAP_MS = 1200L
        private const val MAX_REFRESH_PAGES = 20

        /** The Telegram message id inside a "tu:<chat>:<message>" track id. */
        fun messageIdOf(t: Track): Long = t.id.substringAfterLast(':').toLongOrNull() ?: 0L

        fun trackOf(s: TgSong, chatTitle: String): Track = Track(
            id = "tu:${s.chatId}:${s.messageId}",
            title = s.title.ifBlank { s.fileName.substringBeforeLast('.').ifBlank { "Untitled" } },
            artist = s.artist.ifBlank { "Unknown artist" },
            source = TrackSource.telegram,
            uri = "td://${s.chatId}/${s.messageId}",
            duration = s.durationSec * 1000L,
            album = chatTitle,
            mime = s.mime.ifBlank { "audio/mpeg" },
        )
    }
}
