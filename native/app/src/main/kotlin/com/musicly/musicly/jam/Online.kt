package com.musicly.musicly.jam

import com.musicly.musicly.data.bool
import com.musicly.musicly.data.get
import com.musicly.musicly.data.long
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import com.musicly.musicly.data.arr
import com.musicly.musicly.data.jsonOf
import com.musicly.musicly.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

internal const val JAM_USER_AGENT = "Musicly/1.4 (Flutter)"

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}

/** A request body that reports how much has been sent (a file or bytes). */
internal fun progressBody(
    file: File?,
    bytes: ByteArray?,
    contentType: String = "application/octet-stream",
    onProgress: ((Double) -> Unit)?,
): RequestBody {
    val total = file?.length() ?: bytes!!.size.toLong()
    val type = contentType.toMediaTypeOrNull()
    return object : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = total
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            fun report(n: Long) {
                sent += n
                onProgress?.invoke(if (total == 0L) 1.0 else sent.toDouble() / total)
            }
            if (file != null) {
                file.source().use { src ->
                    val buf = Buffer()
                    while (true) {
                        val n = src.read(buf, 64 * 1024L)
                        if (n < 0) break
                        sink.write(buf, n)
                        report(n)
                    }
                }
            } else {
                sink.write(bytes!!)
                report(bytes.size.toLong())
            }
        }
    }
}

/**
 * A channel on ntfy.sh, a free public message relay: phones in an online
 * Jam publish small JSON messages to it and listen for each other's.
 *
 * ntfy.sh allows about 250 messages a day per phone, so the Jam only sends
 * a message when something actually changes.
 */
class NtfyRelay(
    val topic: String,
    base: String? = null,
    private val client: OkHttpClient = Http.client,
) {
    val base: String = base ?: defaultBase

    suspend fun publish(msg: JsonObject) {
        val req = Request.Builder().url("$base/$topic")
            .post(msg.toString().toByteArray(Charsets.UTF_8).toRequestBody(null)).build()
        client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(req).awaitResponse().use { res ->
            if (res.code == 429) throw JamError("busy")
            if (res.code != 200) throw JamError("relay ${res.code}")
        }
    }

    /** Messages kept by the relay since [since] (e.g. "12h"), oldest first, with their ids. */
    suspend fun history(since: String): List<Pair<String, JsonObject>> {
        val req = Request.Builder().url("$base/$topic/json?poll=1&since=$since").build()
        val text = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(req).awaitResponse().use { res ->
            if (res.code != 200) throw JamError("relay ${res.code}")
            withContext(Dispatchers.IO) { res.body.string() }
        }
        return text.lines().mapNotNull { parseLine(it) }
    }

    /** New messages as they arrive, reconnecting by itself after drops. */
    fun listen(since: String? = null): Flow<JsonObject> = channelFlow {
        var last = since
        var wait = 1
        val streamClient = client.newBuilder().callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS).build()
        while (isActive) {
            try {
                val q = if (last == null) "" else "?since=$last"
                val call = streamClient.newCall(Request.Builder().url("$base/$topic/json$q").build())
                // A blocked read can't see coroutine cancellation, so cancel the call when it happens.
                val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
                try {
                    call.awaitResponse().use { res ->
                        if (res.code != 200) throw JamError("${res.code}")
                        wait = 1
                        val source = res.body.source()
                        while (isActive) {
                            val line = source.readUtf8Line() ?: break
                            val m = parseLine(line) ?: continue
                            last = m.first
                            send(m.second)
                        }
                    }
                } finally {
                    watcher.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (!isActive) break
            // Dropped (or never connected): try again, backing off.
            delay(wait * 1000L)
            wait = min(wait * 2, 30)
            if (last == null) last = "1m"
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        // Nothing to release: the HTTP client is shared.
    }

    companion object {
        @Volatile
        var defaultBase: String = System.getenv("JAM_RELAY") ?: "https://ntfy.sh"

        /** Messages over this size would be turned into attachments by ntfy. */
        const val maxBytes = 3900

        private fun parseLine(line: String): Pair<String, JsonObject>? {
            if (line.isBlank()) return null
            return try {
                val e = parseJson(line) as JsonObject
                if (e["event"].str() != "message") return null
                e["id"].str()!! to (parseJson(e["message"].str()!!) as JsonObject)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** A free temporary file host: where to upload and how to read the reply. */
class FileHost(
    val name: String,
    val endpoint: String,
    /** Form field that carries the file. */
    val field: String,
    /** How long the host keeps files. */
    val lasts: Duration,
    val fields: Map<String, String> = emptyMap(),
    /** The direct file link from the host's reply. */
    val linkFrom: (String) -> String,
) {
    val origin: String
        get() {
            val u = URI(endpoint)
            return "${u.scheme}://${u.rawAuthority}"
        }
}

/**
 * Songs in an online Jam are uploaded to free temporary file hosts as
 * unlisted links that delete themselves. Hosts are tried in turn (some are
 * blocked on some networks), every link is checked before anyone gets it,
 * and the host that worked is tried first next time.
 */
object FileDrop {
    /** Tried in this order at first. */
    val defaults: List<FileHost> = listOf(
        FileHost(
            name = "Litterbox",
            endpoint = "https://litterbox.catbox.moe/resources/internals/api.php",
            field = "fileToUpload",
            fields = mapOf("reqtype" to "fileupload", "time" to "12h"),
            lasts = 12.hours,
            linkFrom = { body -> body.trim() },
        ),
        FileHost(
            name = "Uguu",
            endpoint = "https://uguu.se/upload",
            field = "files[]",
            lasts = 3.hours,
            linkFrom = { body ->
                ((parseJson(body) as JsonObject)["files"] as JsonArray).first().obj()!!["url"].str()!!
            },
        ),
        FileHost(
            name = "tmpfiles",
            endpoint = "https://tmpfiles.org/api/v1/upload",
            field = "file",
            fields = mapOf("expire" to "43200"), // 12 hours
            lasts = 12.hours,
            linkFrom = { body ->
                val page = URI(((parseJson(body) as JsonObject)["data"] as JsonObject)["url"].str()!!)
                // The page link shows a download page; /dl/ is the file itself.
                val scheme = if (page.host == "tmpfiles.org") "https" else page.scheme
                val query = page.rawQuery?.let { "?$it" } ?: ""
                "$scheme://${page.rawAuthority}/dl${page.rawPath}$query"
            },
        ),
    )

    /** Tried in turn; the one that last worked comes first. */
    @Volatile
    var hosts: List<FileHost> = defaults

    /** The client uploads and link checks go through (replace in tests). */
    @Volatile
    var client: OkHttpClient = Http.client

    /** Whether [url] is a link we accept: https, or one of the hosts. */
    fun isLink(url: String?): Boolean {
        if (url == null) return false
        if (url.startsWith("https://")) return true
        return hosts.any { url.startsWith("${it.origin}/") }
    }

    /**
     * Uploads [bytes] (or the file at [file]); returns the link and how long
     * it lasts.
     *
     * With [verify], the start of the uploaded file is fetched back to make
     * sure the link really serves it, before anyone is given the link.
     */
    suspend fun upload(
        file: File? = null,
        bytes: ByteArray? = null,
        name: String,
        verify: Boolean = false,
        onProgress: ((Double) -> Unit)? = null,
    ): Pair<String, Duration> {
        val problems = mutableListOf<String>()
        for (host in hosts.toList()) {
            try {
                val body = post(host.endpoint, host.fields, host.field, file, bytes, name, onProgress)
                val link = host.linkFrom(body).trim()
                if (!isLink(link)) throw JamError("unexpected reply")
                if (verify) check(link)
                // Start with this host next time.
                synchronized(this) { hosts = listOf(host) + hosts.filter { it !== host } }
                return link to host.lasts
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems.add("${host.name}: ${describe(e)}")
            }
        }
        throw JamError("Could not share the song (${problems.joinToString("; ")})")
    }

    /** Makes sure [link] serves a file (not an error or web page). */
    suspend fun check(link: String) {
        val req = Request.Builder().url(link)
            .header("Range", "bytes=0-1023")
            .header("User-Agent", JAM_USER_AGENT).build()
        client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(req).awaitResponse().use { res ->
            val (head, type) = withContext(Dispatchers.IO) {
                val src = res.body.source()
                src.request(64)
                src.buffer.readByteArray(min(src.buffer.size, 1024L)) to res.header("content-type")
            }
            if (res.code != 200 && res.code != 206) throw JamError("link gives HTTP ${res.code}")
            if (looksLikePage(head, type)) throw JamError("link gives a web page, not the song")
        }
    }

    /** An HTML page (error, captcha, download page) rather than a file. */
    fun looksLikePage(head: ByteArray, contentType: String?): Boolean {
        if (contentType?.contains("text/html") == true) return true
        val start = String(head, 0, min(head.size, 64), Charsets.UTF_8).trimStart().lowercase()
        return start.startsWith("<!doctype") || start.startsWith("<html")
    }

    /** A short, readable reason for a network problem. */
    fun describe(e: Throwable): String {
        return when {
            e is JamError -> e.message
            e is SocketTimeoutException || e is InterruptedIOException -> "timed out"
            e is UnknownHostException -> {
                val host = e.message?.takeIf { it.isNotBlank() && !it.contains(' ') }
                if (host == null) "no connection" else "can't reach $host"
            }
            e is ConnectException || e is SocketException -> "no connection"
            e is SSLException -> "secure connection failed"
            e is IOException -> "connection failed (${e.message})"
            else -> {
                val text = e.toString()
                if (text.length > 80) text.substring(0, 80) + "…" else text
            }
        }
    }

    private suspend fun post(
        url: String,
        fields: Map<String, String>,
        field: String,
        file: File?,
        bytes: ByteArray?,
        name: String,
        onProgress: ((Double) -> Unit)?,
    ): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((k, v) in fields) form.addFormDataPart(k, v)
        form.addFormDataPart(field, name, progressBody(file, bytes, "application/octet-stream", onProgress))
        val req = Request.Builder().url(url).header("User-Agent", JAM_USER_AGENT).post(form.build()).build()
        val c = client.newBuilder().callTimeout(5, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build()
        return c.newCall(req).awaitResponse().use { res ->
            if (res.code != 200) throw JamError("HTTP ${res.code}")
            withContext(Dispatchers.IO) { res.body.string() }
        }
    }
}

private val sessionChars = "0123456789abcdefghijklmnopqrstuvwxyz"
private val sessionRandom = SecureRandom()

private fun sessionId(): String = String(CharArray(10) { sessionChars[sessionRandom.nextInt(36)] })

/** Hosting an online Jam: friends anywhere follow along through the relay. */
class OnlineJamHost(
    val code: String,
    val jamName: String,
    val hostName: String,
    val app: JamHostCallbacks,
    val relay: NtfyRelay = NtfyRelay(OnlineJamCode.topic(code)),
) : JamHosting {
    private val me = sessionId()
    private val lock = Any()
    private val guestMap = LinkedHashMap<String, JamPerson>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    override var guestsControl: Boolean = false

    @Volatile
    override var onPeopleChanged: (() -> Unit)? = null
    private var sub: Job? = null
    private var sweep: Job? = null
    private var sendJob: Job? = null

    @Volatile
    private var lastSent = 0L

    @Volatile
    private var lastBody: String? = null

    @Volatile
    private var lastPos: Triple<Long, Long, Boolean>? = null

    override val guests: List<JamPerson> get() = synchronized(lock) { guestMap.values.toList() }

    suspend fun start() {
        // Only messages from now on; anything older belongs to an earlier Jam.
        sub = scope.launch {
            relay.listen(since = "${System.currentTimeMillis() / 1000}").collect { message(it) }
        }
        // People who closed the app without leaving drop off after a while.
        sweep = scope.launch {
            while (isActive) {
                delay(2 * 60_000L)
                val now = System.currentTimeMillis()
                val changed = synchronized(lock) {
                    val before = guestMap.size
                    guestMap.values.removeAll { now - it.seen >= 25 * 60_000L }
                    guestMap.size != before
                }
                if (changed) {
                    onPeopleChanged?.invoke()
                    pushState()
                }
            }
        }
        relay.publish(stateMessage())
        lastSent = System.currentTimeMillis()
    }

    private fun stateMessage(): JsonObject {
        var s = app.state().copy(people = listOf(hostName) + guests.map { it.name })
        fun msg(): JsonObject {
            val m = LinkedHashMap<String, JsonElement>()
            m["src"] = JsonPrimitive(me)
            m["host"] = JsonPrimitive(true)
            m["name"] = JsonPrimitive(jamName)
            m["hostName"] = JsonPrimitive(hostName)
            m.putAll(s.toJson())
            return JsonObject(m)
        }
        // Keep it under the relay's message size by showing less of the queue.
        while (msg().toString().toByteArray(Charsets.UTF_8).size > NtfyRelay.maxBytes && s.queue.isNotEmpty()) {
            s = s.copy(queue = s.queue.dropLast(1))
        }
        return msg()
    }

    /**
     * Sends the Jam's state soon, but never more often than [minGapMs], and
     * skips it when nothing a friend would notice has changed.
     */
    @Synchronized
    override fun pushState() {
        if (sendJob?.isActive == true) return
        val wait = minGapMs - (System.currentTimeMillis() - lastSent)
        scheduleSend(max(0L, wait))
    }

    private fun scheduleSend(ms: Long) {
        // The timer job ends before the send starts, so a change during a send schedules the next one.
        sendJob = scope.launch {
            delay(ms)
            scope.launch { sendNow() }
        }
    }

    private suspend fun sendNow() {
        val msg = stateMessage()
        // Position and time always change; compare the rest, and resend for a
        // jump (seek) that the friends' clocks wouldn't predict.
        val body = JsonObject(msg.filterKeys { it != "at" && it != "pos" }).toString()
        if (body == lastBody && !jumped(msg)) return
        try {
            relay.publish(msg)
            lastBody = body
            lastSent = System.currentTimeMillis()
            lastPos = Triple(msg["pos"].long() ?: 0L, msg["at"].long() ?: 0L, msg["playing"].bool() == true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Busy or offline: try again a little later.
            synchronized(this) { scheduleSend(15_000) }
        }
    }

    private fun jumped(msg: JsonObject): Boolean {
        val last = lastPos ?: return true
        val pos = msg["pos"].long() ?: 0L
        val at = msg["at"].long() ?: 0L
        val expected = if (last.third) last.first + (at - last.second) else last.first
        return abs(pos - expected) > 2500
    }

    private fun person(m: JsonObject): JamPerson {
        val id = m["g"].text()
        val created = synchronized(lock) {
            val p = guestMap[id]
            if (p != null) {
                p.seen = System.currentTimeMillis()
                return p
            }
            val name = m["gname"].let { if (it == null || it is JsonNull) "Guest" else it.text() }.trim()
            JamPerson(id, if (name.isEmpty()) "Guest" else name.take(30)).also { guestMap[id] = it }
        }
        onPeopleChanged?.invoke()
        pushState()
        return created
    }

    private suspend fun message(m: JsonObject) {
        if (m["src"].str() == me || m["g"].let { it == null || it is JsonNull }) return
        when (m["t"].str()) {
            "hello" -> {
                person(m)
                lastBody = null // make sure the newcomer gets the state
                pushState()
            }
            "bye" -> {
                val gone = synchronized(lock) { guestMap.remove(m["g"].text()) }
                if (gone != null) {
                    onPeopleChanged?.invoke()
                    pushState()
                }
            }
            "add" -> {
                val by = person(m)
                scope.launch {
                    try {
                        app.onAdd(m["ref"].text(), by, m["next"].bool() == true)
                    } catch (_: Exception) {
                    }
                }
            }
            "link" -> {
                val url = m["url"].text()
                if (!FileDrop.isLink(url)) return
                val art = m["art"].str()
                val by = person(m)
                scope.launch {
                    try {
                        app.onUpload(
                            JamUpload(
                                source = url,
                                art = if (FileDrop.isLink(art)) art else null,
                                title = m["title"].str() ?: "Unknown",
                                artist = m["artist"].str() ?: "Unknown artist",
                                durationMs = m["dur"].long(),
                            ),
                            by,
                            m["next"].bool() == true,
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            "ctl" -> {
                val who = person(m)
                if (guestsControl) {
                    try {
                        app.onControl(m["a"].text(), m["ms"].long(), who)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun remove(guestId: String) {
        if (synchronized(lock) { guestMap.remove(guestId) } == null) return
        scope.launch {
            try {
                relay.publish(jsonOf("src" to me, "host" to true, "t" to "kick", "g" to guestId))
            } catch (_: Exception) {
            }
        }
        onPeopleChanged?.invoke()
        pushState()
    }

    override suspend fun stop(deleteUploads: Boolean) {
        synchronized(this) { sendJob?.cancel() }
        sweep?.cancel()
        sub?.cancel()
        sub?.join()
        try {
            relay.publish(jsonOf("src" to me, "host" to true, "t" to "end"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        relay.close()
        scope.cancel()
    }

    companion object {
        /** Gap between messages, to stay within the relay's daily allowance (ms). */
        @Volatile
        var minGapMs: Long = 4000
    }
}

/** Being in an online Jam: following the host's messages on the relay. */
class OnlineJamGuest(
    val code: String,
    val relay: NtfyRelay = NtfyRelay(OnlineJamCode.topic(code)),
) : JamSession {
    private val me = sessionId()
    private var myName = "Guest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    override var jamName: String = "Jam"
        private set

    @Volatile
    override var hostName: String = "Host"
        private set

    @Volatile
    override var state: JamState = JamState()
        private set

    private val _states = MutableSharedFlow<JamState>(extraBufferCapacity = 64)
    override val states: SharedFlow<JamState> = _states
    private val _closed = CompletableDeferred<String>()
    override val closed: kotlinx.coroutines.Deferred<String> get() = _closed
    private var sub: Job? = null
    private var hello: Job? = null

    /** Finds the Jam's latest state on the relay, then follows it. */
    suspend fun join(myName: String) {
        this.myName = myName
        val past = relay.history("12h")
        var lastId: String? = null
        var latest: JsonObject? = null
        for ((id, m) in past) {
            if (m["host"].bool() != true) continue
            if (m["t"].str() == "state") latest = m
            if (m["t"].str() == "end") latest = null
            lastId = id
        }
        if (latest == null) throw JamError("No Jam is running with that code.")
        applyState(latest)
        sub = scope.launch { relay.listen(since = lastId).collect { message(it) } }
        try {
            relay.publish(mine("hello"))
        } catch (e: Throwable) {
            sub?.cancel()
            throw e
        }
        // Lets the host know we're still here (people who vanish are dropped).
        hello = scope.launch {
            while (isActive) {
                delay(10 * 60_000L)
                try {
                    relay.publish(mine("hello"))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun mine(type: String, vararg more: Pair<String, Any?>): JsonObject =
        jsonOf("src" to me, "t" to type, "g" to me, "gname" to myName, *more)

    private fun applyState(m: JsonObject) {
        jamName = m["name"].str() ?: jamName
        hostName = m["hostName"].str() ?: hostName
        state = JamState.fromJson(m)
        _states.tryEmit(state)
    }

    private fun message(m: JsonObject) {
        if (m["host"].bool() != true) return
        when (m["t"].str()) {
            "state" -> applyState(m)
            "end" -> finish("ended")
            "kick" -> if (m["g"].str() == me) finish("removed")
        }
    }

    private fun finish(reason: String) {
        hello?.cancel()
        sub?.cancel()
        _closed.complete(reason)
    }

    /** Uses this phone's clock, which phones keep in sync with network time. */
    override val positionMs: Long
        get() {
            val s = state
            var ms = s.positionMs
            if (s.playing) ms += System.currentTimeMillis() - s.at
            val dur = s.now?.durationMs
            if (dur != null && ms > dur) ms = dur
            return max(0L, ms)
        }

    private var libraryUrl: String? = null
    private var library: List<JamTrack> = emptyList()

    override suspend fun search(query: String): List<JamTrack> {
        val url = state.library
        if (url != null && url != libraryUrl) {
            try {
                val req = Request.Builder().url(url).build()
                val text = Http.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
                    .newCall(req).awaitResponse().use { res -> withContext(Dispatchers.IO) { res.body.string() } }
                val j = parseJson(text) as JsonObject
                library = (j["songs"] as JsonArray).map { rowEl ->
                    val row = rowEl as JsonArray
                    JamTrack(
                        id = row[0].text(),
                        title = row[1].text(),
                        artist = row[2].text(),
                        durationMs = row[3].long(),
                    )
                }
                libraryUrl = url
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        val q = query.trim().lowercase()
        return library.filter {
            q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }.take(200)
    }

    override fun add(trackId: String, next: Boolean) {
        scope.launch {
            try {
                relay.publish(mine("add", "ref" to trackId, "next" to next))
            } catch (_: Exception) {
            }
        }
    }

    override fun control(action: String, ms: Long?) {
        scope.launch {
            try {
                relay.publish(mine("ctl", "a" to action, "ms" to ms))
            } catch (_: Exception) {
            }
        }
    }

    override fun artFor(t: JamTrack): String? = t.artUrl

    override suspend fun sendSong(
        audio: File,
        ext: String,
        title: String,
        artist: String,
        art: ByteArray?,
        durationMs: Long?,
        next: Boolean,
        onProgress: ((Double) -> Unit)?,
    ) {
        var artUrl: String? = null
        if (art != null && art.isNotEmpty()) {
            try {
                artUrl = FileDrop.upload(bytes = art, name = "cover.jpg").first
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        val (url, _) = FileDrop.upload(file = audio, name = "song.$ext", onProgress = onProgress)
        relay.publish(
            mine(
                "link",
                "url" to url,
                "art" to artUrl,
                "title" to title,
                "artist" to artist,
                "dur" to durationMs,
                "next" to next,
            ),
        )
    }

    override suspend fun leave() {
        try {
            relay.publish(mine("bye"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        finish("left")
        relay.close()
        scope.cancel()
    }

    companion object {
        /** The host's song list, as uploaded for friends to browse. */
        fun libraryJson(songs: List<JamTrack>): String = JsonObject(
            linkedMapOf(
                "v" to JsonPrimitive(1),
                "songs" to JsonArray(
                    songs.map { t ->
                        JsonArray(
                            listOf(
                                JsonPrimitive(t.id),
                                JsonPrimitive(t.title),
                                JsonPrimitive(t.artist),
                                t.durationMs?.let { JsonPrimitive(it) } ?: JsonNull,
                            ),
                        )
                    },
                ),
            ),
        ).toString()
    }
}
