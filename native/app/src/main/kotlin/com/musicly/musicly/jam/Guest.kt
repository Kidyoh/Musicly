package com.musicly.musicly.jam

import com.musicly.musicly.data.get
import com.musicly.musicly.data.int
import com.musicly.musicly.data.jsonOf
import com.musicly.musicly.data.long
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

/** A Jam another phone on this Wi-Fi is hosting. */
class JamFound(
    val address: String,
    val port: Int,
    val name: String,
    val host: String,
    val people: Int = 1,
) {
    /** When the beacon was heard, ms since epoch. */
    val seen: Long = System.currentTimeMillis()

    val key: String get() = "$address:$port"
}

/**
 * The guest side of a Jam in the same room: one WebSocket to the host's
 * phone.
 */
class JamGuest(
    val address: String,
    val port: Int,
    private val client: OkHttpClient = com.musicly.musicly.net.Http.client,
) : JamSession {
    private var ws: WebSocket? = null

    @Volatile
    var guestId: String? = null
        private set

    @Volatile
    override var jamName: String = "Jam"
        private set

    @Volatile
    override var hostName: String = "Host"
        private set

    @Volatile
    override var state: JamState = JamState()
        private set

    /** Host clock minus this phone's clock, in ms. */
    @Volatile
    var clockOffset: Long = 0
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableSharedFlow<JamState>(extraBufferCapacity = 64)
    override val states: SharedFlow<JamState> = _states
    private val searches = HashMap<Int, CompletableDeferred<List<JamTrack>>>()
    private val rid = AtomicInteger()

    private val _closed = CompletableDeferred<String>()
    override val closed: Deferred<String> get() = _closed

    private val opened = CompletableDeferred<Unit>()
    private val welcome = CompletableDeferred<Unit>()

    private fun url(path: String, vararg query: Pair<String, String>): HttpUrl =
        HttpUrl.Builder().scheme("http").host(address).port(port).addPathSegments(path.trimStart('/'))
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    /**
     * Cover image link for a song, if there is one. The id is encoded twice
     * (the Flutter app does the same, and the host decodes twice).
     */
    override fun artFor(t: JamTrack): String? =
        if (!t.hasArt) null
        else HttpUrl.Builder().scheme("http").host(address).port(port)
            .addPathSegment("art").addPathSegment(encodeComponent(t.id)).build().toString()

    /** Connects and waits for the host's welcome. */
    suspend fun join(myName: String) {
        val wsClient = client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val socket = wsClient.newWebSocket(
            Request.Builder().url(url("ws", "name" to myName)).build(),
            listener,
        )
        ws = socket
        try {
            withTimeout(8000) { opened.await() }
            withTimeout(8000) { welcome.await() }
        } catch (e: Throwable) {
            socket.cancel()
            scope.cancel()
            throw e
        }
        ping()
        scope.launch {
            while (isActive) {
                delay(20_000)
                ping()
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handle(parseJson(text) as JsonObject)
            } catch (_: Exception) {
                webSocket.cancel()
                finish("lost")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            finish("lost")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish("lost")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.completeExceptionally(t)
            welcome.completeExceptionally(t)
            finish("lost")
        }
    }

    private fun handle(m: JsonObject) {
        when (m["t"].str()) {
            "welcome" -> {
                guestId = m["you"].str()
                jamName = m["name"].text()
                hostName = m["host"].text()
                welcome.complete(Unit)
            }
            "state" -> {
                val s = JamState.fromJson(m)
                state = s
                _states.tryEmit(s)
            }
            "pong" -> {
                val sent = m["c"].long() ?: return
                val now = System.currentTimeMillis()
                val host = m["h"].long() ?: return
                clockOffset = host - (sent + now) / 2
            }
            "results" -> {
                val id = m["rid"].int() ?: return
                val items = (m["items"] as? kotlinx.serialization.json.JsonArray)
                    ?.map { JamTrack.fromJson(it as JsonObject) } ?: emptyList()
                synchronized(searches) { searches.remove(id) }?.complete(items)
            }
            "bye" -> finish(m["reason"].let { if (it == null || it is kotlinx.serialization.json.JsonNull) "ended" else it.text() })
        }
    }

    private fun finish(reason: String) {
        scope.cancel()
        val pending = synchronized(searches) { searches.values.toList().also { searches.clear() } }
        for (s in pending) s.complete(emptyList())
        _closed.complete(reason)
    }

    private fun send(m: JsonObject) {
        try {
            ws?.send(m.toString())
        } catch (_: Exception) {
            // the Jam just ended; [closed] says so
        }
    }

    private fun ping() = send(jsonOf("t" to "ping", "c" to System.currentTimeMillis()))

    /** Worked out from the last update and the clock difference. */
    override val positionMs: Long
        get() {
            val s = state
            var ms = s.positionMs
            if (s.playing) ms += System.currentTimeMillis() + clockOffset - s.at
            val dur = s.now?.durationMs
            if (dur != null && ms > dur) ms = dur
            return if (ms < 0) 0 else ms
        }

    override suspend fun search(query: String): List<JamTrack> {
        val id = rid.incrementAndGet()
        val c = CompletableDeferred<List<JamTrack>>()
        synchronized(searches) { searches[id] = c }
        send(jsonOf("t" to "search", "q" to query, "rid" to id))
        return withTimeoutOrNull(10_000) { c.await() }
            ?: emptyList<JamTrack>().also { synchronized(searches) { searches.remove(id) } }
    }

    override fun add(trackId: String, next: Boolean) =
        send(jsonOf("t" to "add", "ref" to trackId, "next" to next))

    override fun control(action: String, ms: Long?) =
        send(jsonOf("t" to "ctl", "a" to action, "ms" to ms))

    /** Uploads the song to the host's phone. */
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
        val id = "${System.currentTimeMillis() * 1000}"
        suspend fun post(kind: String, body: okhttp3.RequestBody) {
            val req = Request.Builder().url(
                url("upload", "g" to (guestId ?: ""), "id" to id, "kind" to kind, "ext" to ext),
            ).post(body).build()
            client.newBuilder().readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
                .newCall(req).awaitResponse().use { res ->
                    if (res.code == 413) throw JamError("That song is too big")
                    if (res.code != 200) throw JamError("Could not send it")
                }
        }
        if (art != null && art.isNotEmpty()) {
            post("art", progressBody(null, art, "application/octet-stream", null))
        }
        post("audio", progressBody(audio, null, "application/octet-stream", onProgress))
        send(
            jsonOf(
                "t" to "addUpload",
                "upload" to id,
                "title" to title,
                "artist" to artist,
                "dur" to durationMs,
                "next" to next,
            ),
        )
    }

    override suspend fun leave() {
        finish("left")
        ws?.close(1000, null)
    }

    companion object {
        /**
         * Listens for Jam beacons on the Wi-Fi. Cancel the collection to stop.
         * On Android hold a multicast lock while collecting.
         */
        fun discover(): Flow<JamFound> = callbackFlow {
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), BEACON_PORT))
                socket.broadcast = true
            } catch (e: Exception) {
                socket.close()
                close(e)
                return@callbackFlow
            }
            val reader = Thread {
                val buf = ByteArray(2048)
                while (!socket.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        val j = parseJson(String(p.data, 0, p.length, Charsets.UTF_8)) as JsonObject
                        if (j["tag"].str() != BEACON_TAG) continue
                        trySend(
                            JamFound(
                                address = p.address.hostAddress ?: continue,
                                port = j["port"].int() ?: continue,
                                name = j["name"].text(),
                                host = j["host"].text(),
                                people = j["people"].int() ?: 1,
                            ),
                        )
                    } catch (_: java.net.SocketException) {
                        break
                    } catch (_: Exception) {
                    }
                }
            }
            reader.isDaemon = true
            reader.name = "jam-discover"
            reader.start()
            awaitClose { socket.close() }
        }

        /** Dart's Uri.encodeComponent: leaves A-Z a-z 0-9 - _ . ! ~ * ' ( ) alone. */
        internal fun encodeComponent(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
                }
            }
            return sb.toString()
        }
    }
}
