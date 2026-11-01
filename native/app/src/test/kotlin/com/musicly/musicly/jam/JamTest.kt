package com.musicly.musicly.jam

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class JamTest {
    @Test
    fun joinCodesRoundTripAndRejectJunk() {
        for ((ip, port) in listOf(
            "192.168.1.23" to 40213,
            "10.0.0.5" to 1,
            "255.255.255.255" to 65535,
            "192.168.43.1" to 8080,
        )) {
            val code = JamCode.encode(ip, port)
            assertTrue(code, Regex("^[2-9A-Z]{4}-[2-9A-Z]{4}-[2-9A-Z]{2}$").matches(code))
            assertEquals(ip to port, JamCode.decode(code))
            assertEquals(ip to port, JamCode.decode(code.lowercase().replace("-", " ")))
        }
        assertNull(JamCode.decode("hello"))
        assertNull(JamCode.decode("0000-0000-00"))
    }

    @Test
    fun trackAndStateJsonMatchTheProtocol() {
        val s = JamState(
            now = JamTrack("a", "T", "A", durationMs = 5, by = "Abel", hasArt = true, url = "u", artUrl = "au"),
            playing = true, positionMs = 7, at = 9,
            queue = listOf(JamTrack("q", "Q", "B")), people = listOf("H", "G"),
            guestsControl = true, event = "e", library = "l",
        )
        assertEquals(
            """{"t":"state","now":{"id":"a","title":"T","artist":"A","dur":5,"by":"Abel","art":true,"url":"u","artUrl":"au"},""" +
                """"playing":true,"pos":7,"at":9,"queue":[{"id":"q","title":"Q","artist":"B"}],"people":["H","G"],""" +
                """"control":true,"event":"e","lib":"l"}""",
            s.toJson().toString(),
        )
        assertEquals(s, JamState.fromJson(s.toJson()))
        assertEquals("""{"t":"state","playing":false,"pos":0,"at":0,"queue":[],"people":[],"control":false}""", JamState().toJson().toString())
    }

    @Test
    fun onlineCodes() {
        val c = OnlineJamCode.create()
        assertTrue(Regex("^[2-9A-Z]{4}-[2-9A-Z]{4}-[2-9A-Z]{4}$").matches(c))
        assertEquals(c, OnlineJamCode.normalise(c.lowercase().replace("-", "")))
        assertNull(OnlineJamCode.normalise("ABCD-EFGH-JK"))
        assertEquals("musicly-jam-k7qm2xrb9tfa", OnlineJamCode.topic("K7QM-2XRB-9TFA"))
    }

    // ---- host and guest ----------------------------------------------------

    private lateinit var dir: File
    private lateinit var host: JamHost
    private val added = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
    private val uploads = CopyOnWriteArrayList<Pair<JamUpload, String>>()
    private val controls = CopyOnWriteArrayList<Pair<String, Long?>>()
    private val songs = listOf(
        JamTrack("tg:a", "Blinding Lights", "The Weeknd"),
        JamTrack("dev:1", "Lose Yourself", "Eminem"),
    )

    @Volatile
    private var now = JamTrack("tg:a", "Blinding Lights", "W")

    @Before
    fun setUp() = runBlocking {
        dir = Files.createTempDirectory("jam").toFile()
        host = JamHost(
            jamName = "Kidyoh's Jam",
            hostName = "Kidyoh",
            uploadsDir = File(dir, "up"),
            app = JamHostCallbacks(
                state = {
                    JamState(now = now, playing = true, positionMs = 1000, at = System.currentTimeMillis())
                },
                onAdd = { id, by, next -> added.add(Triple(id, by.name, next)) },
                onUpload = { song, by, _ -> uploads.add(song to by.name) },
                onControl = { a, ms, _ -> controls.add(a to ms) },
                search = { q -> songs.filter { it.title.lowercase().contains(q.lowercase()) } },
                art = { id -> if (id == "tg:a") byteArrayOf(1, 2, 3) else null },
            ),
        )
        host.start(beacon = false)
    }

    @After
    fun tearDown() = runBlocking {
        host.stop()
        dir.deleteRecursively()
        Unit
    }

    private suspend fun until(ok: () -> Boolean) {
        withTimeout(5000) { while (!ok()) delay(20) }
    }

    @Test
    fun joinsSeesTheJamSearchesAndAdds() = runBlocking {
        withTimeout(20_000) {
            val g = JamGuest("127.0.0.1", host.port)
            val first = async(start = CoroutineStart.UNDISPATCHED) { g.states.first() }
            g.join("Abel")
            assertEquals("Kidyoh's Jam", g.jamName)
            assertEquals("Kidyoh", g.hostName)
            val s = first.await()
            assertEquals("Blinding Lights", s.now?.title)
            assertEquals(listOf("Kidyoh", "Abel"), s.people)
            assertTrue(g.positionMs >= 1000)

            val found = g.search("lose")
            assertEquals(listOf("dev:1"), found.map { it.id })

            g.add("dev:1", next = true)
            until { added.isNotEmpty() }
            assertEquals(listOf(Triple("dev:1", "Abel", true)), added.toList())

            // Playback control is off until the host allows it.
            g.control("next")
            delay(200)
            assertTrue(controls.isEmpty())
            host.guestsControl = true
            g.control("seek", 5000)
            until { controls.isNotEmpty() }
            assertEquals(listOf("seek" to 5000L), controls.toList())
            g.leave()
        }
    }

    @Test
    fun aGuestSendsASongFromTheirPhone() = runBlocking {
        withTimeout(20_000) {
            val g = JamGuest("127.0.0.1", host.port)
            g.join("Abel")
            val song = File(dir, "song.mp3").apply { writeBytes(ByteArray(300000) { it.toByte() }) }
            var progress = 0.0
            g.sendSong(
                audio = song, ext = "mp3", title = "My Song", artist = "Me",
                art = byteArrayOf(9, 9, 9), durationMs = 180000, onProgress = { progress = it },
            )
            until { uploads.isNotEmpty() }
            assertEquals(1.0, progress, 0.0)
            assertEquals(1, uploads.size)
            val (up, by) = uploads.single()
            assertEquals("Abel", by)
            assertEquals("My Song", up.title)
            assertEquals(180000L, up.durationMs)
            assertEquals(300000L, File(up.source).length())
            assertEquals(listOf<Byte>(9, 9, 9), File(up.art!!).readBytes().toList())
            g.leave()
        }
    }

    @Test
    fun artPeopleUpdatesAndTheHostEndingTheJam() = runBlocking {
        withTimeout(30_000) {
            val bytes = URL("http://127.0.0.1:${host.port}/art/${JamGuest.encodeComponent("tg:a")}").readBytes()
            assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())

            val a = JamGuest("127.0.0.1", host.port)
            a.join("Abel")
            // The cover link a guest builds is encoded twice, like the Flutter app's.
            val link = a.artFor(JamTrack("tg:a", "x", "y", hasArt = true))
            assertNotNull(link)
            assertEquals(listOf<Byte>(1, 2, 3), URL(link).readBytes().toList())
            assertNull(a.artFor(JamTrack("tg:a", "x", "y")))

            val b = JamGuest("127.0.0.1", host.port)
            val sawBoth = async(start = CoroutineStart.UNDISPATCHED) {
                a.states.first { it.people.size == 3 }
            }
            b.join("Sara")
            assertEquals(listOf("Kidyoh", "Abel", "Sara"), sawBoth.await().people)

            now = JamTrack("dev:1", "Lose Yourself", "E")
            val changed = async(start = CoroutineStart.UNDISPATCHED) { b.states.first { it.now?.id == "dev:1" } }
            host.pushState()
            assertEquals("Lose Yourself", changed.await().now?.title)

            host.stop()
            assertEquals("ended", withTimeout(3000) { a.closed.await() })
            assertEquals("ended", withTimeout(3000) { b.closed.await() })
        }
    }

    @Test
    fun theHostCanRemoveAGuest() = runBlocking {
        withTimeout(20_000) {
            val a = JamGuest("127.0.0.1", host.port)
            a.join("Abel")
            until { host.guests.isNotEmpty() }
            host.remove(host.guests.single().id)
            assertEquals("removed", withTimeout(3000) { a.closed.await() })
            assertTrue(host.guests.isEmpty())
        }
    }

    @Test
    fun uploadsFromStrangersAreRefused() {
        val c = URL("http://127.0.0.1:${host.port}/upload?g=nobody&id=x").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(3)
        c.outputStream.use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(403, c.responseCode)
    }

    @Test
    fun helloAndUnknownPaths() {
        val hello = URL("http://127.0.0.1:${host.port}/hello").readText()
        assertEquals("""{"tag":"musicly-jam","name":"Kidyoh's Jam","host":"Kidyoh"}""", hello)
        val c = URL("http://127.0.0.1:${host.port}/nope").openConnection() as HttpURLConnection
        assertEquals(404, c.responseCode)
    }
}
