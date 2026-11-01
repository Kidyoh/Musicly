package com.musicly.musicly.jam

import com.musicly.musicly.data.jsonOf
import com.musicly.musicly.data.long
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.bool
import com.musicly.musicly.data.get
import com.musicly.musicly.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import android.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The host side of a Jam in the same room: a small web server on this
 * phone that friends' Musicly apps connect to, plus a beacon so they find
 * it on the Wi-Fi.
 *
 * One port serves the WebSocket (`/ws`), cover art (`/art/<id>`), song
 * uploads (`POST /upload`) and `/hello`, so this is a tiny hand-written
 * HTTP + WebSocket server on a plain [ServerSocket].
 *
 * It knows nothing about playback; the callbacks connect it to the player.
 */
class JamHost(
    val jamName: String,
    val hostName: String,
    val uploadsDir: File,
    val app: JamHostCallbacks,
) : JamHosting {

    @Volatile
    override var guestsControl: Boolean = false

    @Volatile
    override var onPeopleChanged: (() -> Unit)? = null

    private val lock = Any()
    private val guestMap = LinkedHashMap<String, JamPerson>()
    private val sockets = HashMap<String, WsConn>()
    private val pendingUploads = HashMap<String, String>() // upload id -> audio path
    private val pendingArt = HashMap<String, String>() // upload id -> art path
    private val seq = AtomicInteger()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var beaconSocket: DatagramSocket? = null
    private var beaconTimer: ScheduledExecutorService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "jam-host").apply { isDaemon = true } }

    @Volatile
    private var stopped = false

    override val guests: List<JamPerson> get() = synchronized(lock) { guestMap.values.toList() }

    val port: Int get() = server?.localPort ?: 0

    suspend fun start(port: Int = 0, beacon: Boolean = true) = withContext(Dispatchers.IO) {
        uploadsDir.mkdirs()
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        server = s
        stopped = false
        pool.execute { acceptLoop(s) }
        if (beacon) startBeacon()
    }

    override suspend fun stop(deleteUploads: Boolean) = withContext(Dispatchers.IO) {
        if (stopped && server == null) return@withContext
        stopped = true
        beaconTimer?.shutdownNow()
        beaconTimer = null
        try {
            beaconSocket?.close()
        } catch (_: Exception) {
        }
        beaconSocket = null
        val now = guests
        for (g in now) {
            send(g, jsonOf("t" to "bye", "reason" to "ended").toString())
            val c = synchronized(lock) { sockets[g.id] }
            c?.close()
        }
        synchronized(lock) {
            guestMap.clear()
            sockets.clear()
        }
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        scope.cancel()
        if (deleteUploads) {
            try {
                uploadsDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    /** Sends to one guest; a guest who just left is skipped quietly. */
    private fun send(g: JamPerson, msg: String) {
        val c = synchronized(lock) { sockets[g.id] } ?: return
        try {
            c.sendText(msg)
        } catch (_: Exception) {
        }
    }

    /** Sends the current Jam to every guest. */
    override fun pushState() {
        val now = guests
        if (now.isEmpty()) return
        val msg = stateWithPeople().toJson().toString()
        for (g in now) send(g, msg)
    }

    override fun remove(guestId: String) {
        val c = synchronized(lock) {
            if (guestMap.remove(guestId) == null) return
            sockets.remove(guestId)
        }
        try {
            c?.sendText(jsonOf("t" to "bye", "reason" to "removed").toString())
        } catch (_: Exception) {
        }
        c?.close()
        onPeopleChanged?.invoke()
        pushState()
    }

    private fun stateWithPeople(): JamState =
        app.state().copy(people = listOf(hostName) + guests.map { it.name })

    // ---- Beacon ------------------------------------------------------------

    private fun startBeacon() {
        try {
            val s = DatagramSocket()
            s.broadcast = true
            beaconSocket = s
            val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jam-beacon").apply { isDaemon = true } }
            beaconTimer = ex
            ex.scheduleAtFixedRate({ ping() }, 0, 2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    private fun ping() {
        val s = beaconSocket ?: return
        val data = jsonOf(
            "tag" to BEACON_TAG,
            "v" to 1,
            "name" to jamName,
            "host" to hostName,
            "port" to port,
            "people" to synchronized(lock) { guestMap.size } + 1,
        ).toString().toByteArray(Charsets.UTF_8)
        val targets = linkedSetOf("255.255.255.255")
        try {
            // Also the subnet broadcast of each network, for hotspots.
            for (i in NetworkInterface.getNetworkInterfaces()) {
                for (a in i.inetAddresses) {
                    if (a !is Inet4Address || a.isLoopbackAddress) continue
                    val p = a.hostAddress!!.split('.')
                    targets.add("${p[0]}.${p[1]}.${p[2]}.255")
                }
            }
        } catch (_: Exception) {
        }
        for (t in targets) {
            try {
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), BEACON_PORT))
            } catch (_: Exception) {
            }
        }
    }

    // ---- Requests ------------------------------------------------------------

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try {
                s.accept()
            } catch (_: Exception) {
                break
            }
            try {
                pool.execute { handle(c) }
            } catch (_: Exception) {
                try { c.close() } catch (_: Exception) {}
            }
        }
    }

    private class Req(
        val method: String,
        val segments: List<String>,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    private fun handle(socket: Socket) {
        var keepOpen = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val out = socket.getOutputStream()
            val req = readRequest(input)
            if (req == null) {
                respond(out, 400, "Bad Request")
            } else {
                val path = req.segments
                try {
                    when {
                        path.size == 1 && path[0] == "ws" -> keepOpen = join(socket, input, out, req)
                        path.size == 2 && path[0] == "art" -> art(out, path[1])
                        path.size == 1 && path[0] == "upload" -> receiveUpload(req, input, out)
                        path.size == 1 && path[0] == "hello" ->
                            json(out, jsonOf("tag" to BEACON_TAG, "name" to jamName, "host" to hostName))
                        else -> respond(out, 404, "Not Found")
                    }
                } catch (_: Exception) {
                    try {
                        respond(out, 500, "Internal Server Error")
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (!keepOpen) lingerClose(socket)
        }
    }

    /** Closes politely so a client still sending (a refused upload) sees our answer. */
    private fun lingerClose(socket: Socket) {
        try {
            socket.shutdownOutput()
            socket.soTimeout = 500
            val buf = ByteArray(8192)
            var total = 0
            val i = socket.getInputStream()
            while (total < 256 * 1024) {
                val n = i.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun readRequest(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
            if (head.size() > 32 * 1024) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val first = lines[0].split(' ')
        if (first.size < 2) return null
        val target = first[1]
        val q = target.indexOf('?')
        val rawPath = if (q >= 0) target.substring(0, q) else target
        val rawQuery = if (q >= 0) target.substring(q + 1) else ""
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val c = l.indexOf(':')
            if (c > 0) headers[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
        }
        val segs = rawPath.split('/').filter { it.isNotEmpty() }.map { pctDecode(it) }
        val query = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val e = pair.indexOf('=')
            val k = if (e >= 0) pair.substring(0, e) else pair
            val v = if (e >= 0) pair.substring(e + 1) else ""
            query[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return Req(first[0].uppercase(), segs, query, headers)
    }

    private fun respond(
        out: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ) {
        val sb = StringBuilder("HTTP/1.1 $status $reason\r\n")
        sb.append("Content-Length: ${body.size}\r\nConnection: close\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, body: JsonObject) =
        respond(
            out, 200, "OK", mapOf("Content-Type" to "application/json; charset=utf-8"),
            body.toString().toByteArray(Charsets.UTF_8),
        )

    // ---- WebSocket -----------------------------------------------------------

    /** Returns true when the socket now belongs to the WebSocket. */
    private fun join(socket: Socket, input: InputStream, out: OutputStream, req: Req): Boolean {
        val key = req.headers["sec-websocket-key"]
        val upgrade = req.headers["upgrade"]?.lowercase()
        if (req.method != "GET" || upgrade != "websocket" || key == null) {
            respond(out, 400, "Bad Request")
            return false
        }
        val name = (req.query["name"] ?: "Guest").trim()
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP,
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        out.flush()
        socket.soTimeout = 40_000 // our pings (every 10 s) are answered by any live phone
        val conn = WsConn(socket, input, out)
        val g = JamPerson(
            "${System.currentTimeMillis() * 1000}-${seq.getAndIncrement()}",
            if (name.isEmpty()) "Guest" else name.take(30),
        )
        synchronized(lock) {
            guestMap[g.id] = g
            sockets[g.id] = conn
        }
        conn.startPinging(pool)
        conn.sendText(jsonOf("t" to "welcome", "name" to jamName, "host" to hostName, "you" to g.id).toString())
        onPeopleChanged?.invoke()
        pushState()
        conn.readLoop { raw -> message(g, raw) }
        synchronized(lock) { sockets.remove(g.id) }
        val removed = synchronized(lock) { guestMap.remove(g.id) }
        if (removed != null && !stopped) {
            onPeopleChanged?.invoke()
            pushState()
        }
        conn.close()
        return true
    }

    private fun message(g: JamPerson, raw: String) {
        val m = try {
            parseJson(raw) as JsonObject
        } catch (_: Exception) {
            return
        }
        when (m["t"].str()) {
            "ping" -> send(
                g,
                JsonObject(
                    linkedMapOf(
                        "t" to JsonPrimitive("pong"),
                        "c" to (m["c"] ?: JsonNull),
                        "h" to JsonPrimitive(System.currentTimeMillis()),
                    ),
                ).toString(),
            )
            "search" -> {
                val q = m["q"].let { if (it == null || it is JsonNull) "" else it.text() }
                send(
                    g,
                    JsonObject(
                        linkedMapOf(
                            "t" to JsonPrimitive("results"),
                            "rid" to (m["rid"] ?: JsonNull),
                            "items" to JsonArray(app.search(q).take(80).map { it.toJson() }),
                        ),
                    ).toString(),
                )
            }
            "add" -> scope.launch {
                try {
                    app.onAdd(m["ref"].text(), g, m["next"].bool() == true)
                } catch (_: Exception) {
                }
            }
            "addUpload" -> {
                val id = m["upload"].text()
                val (audio, art) = synchronized(lock) { pendingUploads.remove(id) to pendingArt.remove(id) }
                if (audio == null) return
                scope.launch {
                    try {
                        app.onUpload(
                            JamUpload(
                                source = audio,
                                art = art,
                                title = m["title"].str() ?: "Unknown",
                                artist = m["artist"].str() ?: "Unknown artist",
                                durationMs = m["dur"].long(),
                            ),
                            g,
                            m["next"].bool() == true,
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            "ctl" -> if (guestsControl) {
                try {
                    app.onControl(m["a"].text(), m["ms"].long(), g)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun art(out: OutputStream, id: String) {
        // The id is percent-encoded twice on the wire (Dart's Uri re-encodes the '%').
        val bytes = runBlocking { app.art(pctDecode(id)) }
        if (bytes == null) {
            respond(out, 404, "Not Found")
        } else {
            respond(out, 200, "OK", mapOf("Content-Type" to "image/jpeg", "Cache-Control" to "max-age=3600"), bytes)
        }
    }

    /** `POST /upload?g=guest&id=upload&kind=audio|art&ext=mp3` */
    private fun receiveUpload(req: Req, input: InputStream, out: OutputStream) {
        val q = req.query
        val id = (q["id"] ?: "").replace(Regex("[^\\w-]"), "")
        val kind = if (q["kind"] == "art") "art" else "audio"
        val ext = (q["ext"] ?: "mp3").replace(Regex("[^\\w]"), "")
        val known = q["g"]?.let { gid -> synchronized(lock) { guestMap.containsKey(gid) } } ?: false
        if (req.method != "POST" || !known || id.isEmpty()) {
            respond(out, 403, "Forbidden")
            return
        }
        val limit: Long = if (kind == "art") 2L shl 20 else 80L shl 20
        val declared = req.headers["content-length"]?.toLongOrNull()
        if (declared != null && declared > limit) {
            respond(out, 413, "Payload Too Large")
            return
        }
        val file = File(uploadsDir, "$id.${if (kind == "art") "jpg" else if (ext.isEmpty()) "mp3" else ext}")
        uploadsDir.mkdirs()
        val fits = file.outputStream().buffered().use { sink ->
            copyBody(input, req.headers, limit, sink)
        }
        if (!fits) {
            file.delete()
            respond(out, 413, "Payload Too Large")
            return
        }
        synchronized(lock) { (if (kind == "art") pendingArt else pendingUploads)[id] = file.path }
        json(out, jsonOf("ok" to true))
    }

    /** Copies the request body (length-delimited or chunked); false if it exceeds [limit]. */
    private fun copyBody(input: InputStream, headers: Map<String, String>, limit: Long, sink: OutputStream): Boolean {
        val buf = ByteArray(64 * 1024)
        var size = 0L
        if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
            while (true) {
                val line = readLine(input).substringBefore(';').trim()
                val n = line.toLong(16)
                if (n == 0L) {
                    while (readLine(input).isNotEmpty()) { /* trailers */ }
                    return true
                }
                var left = n
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) throw IOException("closed")
                    size += r
                    if (size > limit) return false
                    sink.write(buf, 0, r)
                    left -= r
                }
                readLine(input) // CRLF after the chunk
            }
        }
        var left = headers["content-length"]?.toLongOrNull() ?: 0L
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("closed")
            size += r
            if (size > limit) return false
            sink.write(buf, 0, r)
            left -= r
        }
        return true
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Percent-decoding that leaves '+' alone (path segments). */
        internal fun pctDecode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

        /** The phone's address on the Wi-Fi or hotspot, for the join code. */
        fun localAddress(): String? {
            fun rank(i: NetworkInterface): Int {
                val n = i.name.lowercase()
                return when {
                    n.startsWith("wlan") || n.startsWith("swlan") -> 0
                    n.startsWith("ap") || n.contains("softap") -> 1
                    n.startsWith("eth") || n.startsWith("en") -> 2
                    // Mobile data last: friends can't reach the phone through it.
                    n.startsWith("rmnet") || n.startsWith("ccmni") -> 9
                    else -> 5
                }
            }
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { runCatching { it.isUp }.getOrDefault(false) }
                    .sortedBy { rank(it) }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** One accepted WebSocket (RFC 6455, text frames only). */
internal class WsConn(
    private val socket: Socket,
    input: InputStream,
    private val output: OutputStream,
) {
    private val input = DataInputStream(input)

    @Volatile
    private var closed = false

    fun sendText(text: String) = writeFrame(0x1, text.toByteArray(Charsets.UTF_8))

    private fun writeFrame(op: Int, data: ByteArray) {
        if (closed) throw IOException("closed")
        synchronized(output) {
            output.write(0x80 or op)
            when {
                data.size < 126 -> output.write(data.size)
                data.size <= 0xFFFF -> {
                    output.write(126)
                    output.write(data.size ushr 8)
                    output.write(data.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    for (s in 56 downTo 0 step 8) output.write(((data.size.toLong() ushr s) and 0xFF).toInt())
                }
            }
            output.write(data)
            output.flush()
        }
    }

    /** Pings every 10 s so dead phones are noticed (the read times out after 40 s). */
    fun startPinging(pool: java.util.concurrent.ExecutorService) {
        pool.execute {
            while (!closed) {
                try {
                    Thread.sleep(10_000)
                    if (!closed) writeFrame(0x9, ByteArray(0))
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    /** Reads frames until the peer closes; calls [onText] for each text message. */
    fun readLoop(onText: (String) -> Unit) {
        val frag = ByteArrayOutputStream()
        var fragOp = 0
        try {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = input.read()
                if (b1 < 0) break
                val fin = b0 and 0x80 != 0
                val op = b0 and 0x0F
                val masked = b1 and 0x80 != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = input.readUnsignedShort().toLong()
                else if (len == 127L) len = input.readLong()
                if (len < 0 || len > MAX_MESSAGE) break
                val mask = ByteArray(4)
                if (masked) input.readFully(mask)
                val payload = ByteArray(len.toInt())
                input.readFully(payload)
                if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (op) {
                    0x8 -> {
                        try { writeFrame(0x8, payload.copyOf(minOf(2, payload.size))) } catch (_: Exception) {}
                        break
                    }
                    0x9 -> try { writeFrame(0xA, payload) } catch (_: Exception) { break }
                    0xA -> {}
                    0x1, 0x2, 0x0 -> {
                        if (op != 0x0) {
                            frag.reset()
                            fragOp = op
                        }
                        frag.write(payload)
                        if (frag.size() > MAX_MESSAGE) break
                        if (fin) {
                            if (fragOp == 0x1) onText(frag.toString(Charsets.UTF_8.name()))
                            frag.reset()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        closed = true
    }

    fun close() {
        if (!closed) {
            try {
                writeFrame(0x8, byteArrayOf(0x03, 0xE8.toByte()))
            } catch (_: Exception) {
            }
        }
        closed = true
        try {
            socket.shutdownOutput()
        } catch (_: Exception) {
        }
        // Give the peer a moment to answer the close, then drop the connection.
        val t = Thread {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
    }

    private companion object {
        const val MAX_MESSAGE = 1L shl 20
    }
}
