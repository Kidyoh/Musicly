package com.musicly.musicly.jam

import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import com.musicly.musicly.data.get
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

/** Just enough of ntfy.sh and Litterbox to run an online Jam locally. */
class FakeCloud {
    lateinit var server: HttpServer
    val topics = HashMap<String, MutableList<JsonObject>>()
    val listeners = HashMap<String, MutableList<HttpExchange>>()
    val files = ConcurrentHashMap<String, ByteArray>()
    private val seq = AtomicInteger()
    val published = AtomicInteger()
    private val lock = Any()

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: Throwable) {
                try { ex.close() } catch (_: Exception) {}
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    private fun reply(ex: HttpExchange, code: Int, body: ByteArray = ByteArray(0), type: String? = null) {
        if (type != null) ex.responseHeaders.add("Content-Type", type)
        if (body.isEmpty()) {
            ex.sendResponseHeaders(code, -1)
        } else {
            ex.sendResponseHeaders(code, body.size.toLong())
            ex.responseBody.write(body)
        }
        ex.close()
    }

    private fun handle(ex: HttpExchange) {
        val seg = ex.requestURI.path.split('/').filter { it.isNotEmpty() }
        val query = (ex.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        fun readBody() = ex.requestBody.readBytes()
        when (seg.first()) {
            "fail" -> { readBody(); return reply(ex, 503) }
            "uguu" -> {
                val name = "u${seq.getAndIncrement()}"
                files[name] = readBody()
                return reply(
                    ex, 200,
                    """{"success":true,"files":[{"url":"$base/files/$name","filename":"$name"}]}""".toByteArray(),
                )
            }
            "tmpapi", "tmpweb" -> {
                // tmpweb: like tmpfiles when its /dl/ links give a web page.
                val web = seg.first() == "tmpweb"
                val name = "t${seq.getAndIncrement()}"
                files[name] = readBody()
                return reply(
                    ex, 200,
                    """{"status":"success","data":{"url":"$base/${if (web) "page" else "123"}/$name"}}""".toByteArray(),
                )
            }
            "page" -> return reply(ex, 200, "<!DOCTYPE html><html>Download page</html>".toByteArray(), "text/html")
            "dl" -> {
                if (seg[1] == "page") {
                    return reply(ex, 200, "<!DOCTYPE html><html>Click to download</html>".toByteArray(), "text/html")
                }
                return reply(ex, 200, files[seg.last()] ?: ByteArray(0))
            }
            "files" -> {
                if (ex.requestMethod == "POST") {
                    val name = "f${seq.getAndIncrement()}"
                    files[name] = readBody()
                    return reply(ex, 200, "$base/files/$name".toByteArray())
                }
                return reply(ex, 200, files[seg[1]] ?: ByteArray(0))
            }
        }
        val topic = seg.first()
        if (ex.requestMethod == "POST") {
            val body = String(readBody(), Charsets.UTF_8)
            synchronized(lock) {
                val e = JsonObject(
                    linkedMapOf(
                        "id" to JsonPrimitive("m${seq.getAndIncrement()}"),
                        "time" to JsonPrimitive(System.currentTimeMillis() / 1000),
                        "event" to JsonPrimitive("message"),
                        "topic" to JsonPrimitive(topic),
                        "message" to JsonPrimitive(body),
                    ),
                )
                topics.getOrPut(topic) { mutableListOf() }.add(e)
                published.incrementAndGet()
                val gone = mutableListOf<HttpExchange>()
                for (l in listeners[topic].orEmpty()) {
                    try {
                        l.responseBody.write("$e\n".toByteArray())
                        l.responseBody.flush()
                    } catch (_: IOException) {
                        gone.add(l)
                    }
                }
                listeners[topic]?.removeAll(gone)
            }
            return reply(ex, 200)
        }
        val since = query["since"]
        synchronized(lock) {
            val list = topics.getOrPut(topic) { mutableListOf() }
            var past: List<JsonObject> = emptyList()
            if (since != null) {
                val i = list.indexOfFirst { it["id"].str() == since }
                val ts = since.toLongOrNull()
                past = if (i >= 0) list.drop(i + 1)
                else if (ts != null) list.filter { (it["time"] as JsonPrimitive).content.toLong() >= ts }
                else list.toList()
            }
            val text = past.joinToString("") { "$it\n" }
            if (query["poll"] == "1") {
                reply(ex, 200, text.toByteArray())
                return
            }
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write("""{"event":"open"}""".toByteArray() + "\n".toByteArray())
            ex.responseBody.write(text.toByteArray())
            ex.responseBody.flush()
            listeners.getOrPut(topic) { mutableListOf() }.add(ex)
        }
    }

    fun stop() {
        synchronized(lock) {
            for (l in listeners.values.flatten()) {
                try { l.close() } catch (_: Exception) {}
            }
        }
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}

class JamOnlineTest {
    private lateinit var cloud: FakeCloud
    private val added = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
    private val uploads = CopyOnWriteArrayList<Pair<JamUpload, String>>()
    private val controls = CopyOnWriteArrayList<String>()

    @Volatile
    private var now = JamTrack(
        id = "tg:a", title = "Blinding Lights", artist = "The Weeknd",
        durationMs = 200000, url = "https://files/a.mp3",
    )

    private val tempDirs = mutableListOf<File>()

    /** The real hosts' formats, pointed at the fake cloud's [routes]. */
    private fun hosts(litterbox: String, uguu: String, tmp: String): List<FileHost> {
        val real = FileDrop.defaults.associateBy { it.name }
        fun at(name: String, route: String) = FileHost(
            name = name,
            endpoint = "${cloud.base}/$route",
            field = real[name]!!.field,
            fields = real[name]!!.fields,
            lasts = real[name]!!.lasts,
            linkFrom = real[name]!!.linkFrom,
        )
        return listOf(at("Litterbox", litterbox), at("Uguu", uguu), at("tmpfiles", tmp))
    }

    private fun app() = JamHostCallbacks(
        state = {
            JamState(
                now = now, playing = true, positionMs = 30000, at = System.currentTimeMillis(),
                queue = (0 until 80).map { JamTrack("q$it", "Queued song $it", "Artist $it") },
                library = "${cloud.base}/files/lib",
            )
        },
        onAdd = { id, by, next -> added.add(Triple(id, by.name, next)) },
        onUpload = { song, by, _ -> uploads.add(song to by.name) },
        onControl = { a, _, _ -> controls.add(a) },
        search = { emptyList() },
        art = { null },
    )

    @Before
    fun setUp() {
        cloud = FakeCloud().also { it.start() }
        FileDrop.hosts = hosts("files", "uguu", "tmpapi")
        OnlineJamHost.minGapMs = 200
        cloud.files["lib"] = OnlineJamGuest.libraryJson(
            listOf(
                JamTrack("tg:a", "Blinding Lights", "The Weeknd"),
                JamTrack("dev:7", "Lose Yourself", "Eminem"),
            ),
        ).toByteArray()
    }

    @After
    fun tearDown() {
        cloud.stop()
        tempDirs.forEach { it.deleteRecursively() }
        FileDrop.hosts = FileDrop.defaults
    }

    private suspend fun until(ok: () -> Boolean) {
        withTimeout(6000) { while (!ok()) delay(50) }
    }

    @Test
    fun onlineCodes() {
        val c = OnlineJamCode.create()
        assertTrue(Regex("^[2-9A-Z]{4}-[2-9A-Z]{4}-[2-9A-Z]{4}$").matches(c))
        assertEquals(c, OnlineJamCode.normalise(c.lowercase().replace("-", "")))
        assertEquals(null, OnlineJamCode.normalise("ABCD-EFGH-JK"))
        assertEquals("musicly-jam-k7qm2xrb9tfa", OnlineJamCode.topic("K7QM-2XRB-9TFA"))
    }

    @Test
    fun aFriendJoinsFollowsAlongAndAddsSongs() = runBlocking {
        withTimeout(60_000) {
            val code = "K7QM-2XRB-9TFA"
            val topic = OnlineJamCode.topic(code)
            val host = OnlineJamHost(code, "Kidyoh's Jam", "Kidyoh", app(), NtfyRelay(topic, cloud.base))
            host.start()

            // Every message fits in the relay's size limit.
            val first = cloud.topics[topic]!!.first()["message"].str()!!
            assertTrue(first.toByteArray().size <= NtfyRelay.maxBytes)

            val guest = OnlineJamGuest(code, NtfyRelay(topic, cloud.base))
            val sawAbel = async(start = CoroutineStart.UNDISPATCHED) {
                guest.states.first { "Abel" in it.people }
            }
            guest.join("Abel")
            assertEquals("Kidyoh's Jam", guest.jamName)
            assertEquals("Kidyoh", guest.hostName)
            assertEquals("https://files/a.mp3", guest.state.now?.url)
            assertTrue(guest.positionMs >= 30000)

            // The host hears hello and tells everyone who's here.
            assertEquals(listOf("Kidyoh", "Abel"), withTimeout(5000) { sawAbel.await() }.people)
            assertEquals(listOf("Abel"), host.guests.map { it.name })

            // Browsing the host's library.
            assertEquals(listOf("dev:7"), guest.search("lose").map { it.id })

            guest.add("dev:7", next = true)
            until { added.isNotEmpty() }
            assertEquals(Triple("dev:7", "Abel", true), added.single())

            // Sending a song from the friend's phone goes through the file host.
            val dir = Files.createTempDirectory("jamonline").toFile().also { tempDirs.add(it) }
            val song = File(dir, "s.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            var progress = 0.0
            guest.sendSong(
                audio = song, ext = "mp3", title = "My Song", artist = "Me",
                art = byteArrayOf(7, 7), durationMs = 1000, onProgress = { progress = it },
            )
            until { uploads.isNotEmpty() }
            assertEquals(1.0, progress, 0.0)
            val (up, by) = uploads.single()
            assertEquals("Abel", by)
            assertEquals("My Song", up.title)
            assertTrue(up.source.startsWith("${cloud.base}/files/"))
            assertNotNull(up.art)

            // Control only once the host allows it.
            guest.control("next")
            delay(300)
            assertTrue(controls.isEmpty())
            host.guestsControl = true
            guest.control("next")
            until { controls.isNotEmpty() }

            // Nothing changed: no new message. A change: exactly one more.
            delay(400)
            val before = cloud.published.get()
            host.pushState()
            delay(400)
            assertEquals(before, cloud.published.get())
            now = JamTrack("dev:7", "Lose Yourself", "Eminem", url = "https://files/b.mp3")
            val changed = async(start = CoroutineStart.UNDISPATCHED) { guest.states.first { it.now?.id == "dev:7" } }
            host.pushState()
            host.pushState()
            assertEquals("https://files/b.mp3", withTimeout(5000) { changed.await() }.now?.url)
            delay(400)
            assertEquals(before + 1, cloud.published.get())

            // Ending the Jam reaches the friend.
            host.stop()
            assertEquals("ended", withTimeout(5000) { guest.closed.await() })

            // And a late joiner is told there's no Jam.
            val late = OnlineJamGuest(code, NtfyRelay(topic, cloud.base))
            try {
                late.join("Sara")
                fail("should not join")
            } catch (_: JamError) {
            }
        }
    }

    @Test
    fun uploadsMoveOnToAHostThatWorksAndRememberIt() = runBlocking {
        withTimeout(60_000) {
            val (good, lasts) = FileDrop.upload(bytes = byteArrayOf(1, 2, 3), name = "a")
            assertTrue(good.startsWith("${cloud.base}/files/f"))
            assertEquals(12.hours, lasts)

            // Like on Kidus's network: Litterbox refuses uploads.
            FileDrop.hosts = hosts("fail", "uguu", "tmpweb")
            var progress = 0.0
            val (link, lasts2) = FileDrop.upload(
                bytes = ByteArray(5000) { 7 }, name = "b.mp3", verify = true, onProgress = { progress = it },
            )
            assertTrue(link.startsWith("${cloud.base}/files/u"))
            assertEquals(3.hours, lasts2)
            assertEquals(1.0, progress, 0.0)
            assertTrue(FileDrop.isLink(link))
            // Uguu worked, so it goes first next time.
            assertEquals("Uguu", FileDrop.hosts.first().name)

            // tmpfiles' direct link is still built right when it's the one used.
            FileDrop.hosts = hosts("fail", "fail", "tmpapi")
            val (tmp, _) = FileDrop.upload(bytes = byteArrayOf(5), name = "c", verify = true)
            assertTrue(tmp, Regex("/dl/123/t\\d+$").containsMatchIn(tmp))

            // Nothing works: the error says why for each host.
            FileDrop.hosts = hosts("fail", "fail", "tmpweb")
            try {
                FileDrop.upload(bytes = byteArrayOf(1), name = "d", verify = true)
                fail("should fail")
            } catch (e: JamError) {
                assertTrue(e.message, e.message.contains("Litterbox: HTTP 503"))
                assertTrue(e.message, e.message.contains("Uguu: HTTP 503"))
                assertTrue(e.message, e.message.contains("tmpfiles: link gives a web page"))
            }
        }
    }

    @Test
    fun sharedLinksAreCheckedBeforeAnyoneGetsThem() = runBlocking {
        withTimeout(30_000) {
            val (link, _) = FileDrop.upload(
                bytes = byteArrayOf(0x49, 0x44, 0x33, 4, 0), name = "song.mp3", verify = true,
            )
            assertTrue(link.startsWith("${cloud.base}/files/"))
            FileDrop.check(link)
            try {
                FileDrop.check("${cloud.base}/page/x")
                fail("should reject a web page")
            } catch (_: JamError) {
            }
            assertTrue(FileDrop.looksLikePage("  <html>".toByteArray(), null))
            assertFalse(FileDrop.looksLikePage(byteArrayOf(0x49, 0x44, 0x33), "audio/mpeg"))
            assertEquals("no connection", FileDrop.describe(SocketException("x")))
        }
    }

    @Test
    fun theHostCanRemoveAFriend() = runBlocking {
        withTimeout(60_000) {
            val code = "ABCD-EFGH-JKMN"
            val topic = OnlineJamCode.topic(code)
            val host = OnlineJamHost(code, "Jam", "Kidyoh", app(), NtfyRelay(topic, cloud.base))
            host.start()
            val guest = OnlineJamGuest(code, NtfyRelay(topic, cloud.base))
            guest.join("Abel")
            until { host.guests.isNotEmpty() }
            host.remove(host.guests.single().id)
            assertEquals("removed", withTimeout(5000) { guest.closed.await() })
            host.stop()
        }
    }

    @Test
    fun protocolFieldsThePhonesAgreeOn() {
        // A state message as the Flutter host sends it, read by the Kotlin guest.
        val m = parseJson(
            """{"src":"x","host":true,"name":"J","hostName":"H","t":"state","now":{"id":"a","title":"T","artist":"A","dur":10,"url":"https://u"},""" +
                """"playing":true,"pos":5,"at":6,"queue":[],"people":["H"],"control":false,"lib":"https://l"}""",
        ) as JsonObject
        val s = JamState.fromJson(m)
        assertEquals("https://u", s.now?.url)
        assertEquals(10L, s.now?.durationMs)
        assertEquals("https://l", s.library)
        assertTrue(JsonArray(emptyList()).isEmpty())
    }
}
