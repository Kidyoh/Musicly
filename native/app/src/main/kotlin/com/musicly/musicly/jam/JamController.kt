package com.musicly.musicly.jam

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.musicly.musicly.data.Library
import com.musicly.musicly.data.Store
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.net.Device
import com.musicly.musicly.net.Http
import com.musicly.musicly.net.TelegramFiles
import com.musicly.musicly.player.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class JamRole { none, host, guest }

/**
 * Listening together.
 *
 * In the same room, the host's phone plays and friends on the same Wi-Fi
 * add songs. Online, friends anywhere hear the host's songs on their own
 * phones, in step: messages go through the ntfy.sh relay and songs are
 * shared as temporary links.
 *
 * All observable state is Compose state and is changed on the main thread.
 * Engine callbacks arrive on background threads and hop to main.
 */
@UnstableApi
class JamController(
    val library: Library,
    val player: PlayerController,
    val store: Store,
    val device: Device,
    val cacheDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val supported: Boolean get() = true

    var role by mutableStateOf(JamRole.none)
    val active: Boolean get() = role != JamRole.none

    /** An online Jam (rather than one in the same room). */
    var online by mutableStateOf(false)

    /** What friends see you as. */
    var myName by mutableStateOf(store.string("jam_name") ?: "")
        private set
    val hasName: Boolean get() = myName.isNotEmpty()

    fun setName(name: String) {
        myName = name.trim()
        store.put("jam_name", myName)
    }

    /** The latest thing that happened, e.g. "Abel added Blinding Lights". */
    var event by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    // ---- Hosting -----------------------------------------------------------

    private var host: JamHosting? = null

    /** Same room: the address code. Online: the Jam's code. */
    var joinCode by mutableStateOf<String?>(null)
    private var tick: Job? = null
    private var debounce: Job? = null
    private var playerWatch: Job? = null
    private var playerListener: Player.Listener? = null

    var guestsControl by mutableStateOf(false)
        private set

    /** Who added which song (track id -> name), for "Added by". */
    private val addedByMap = ConcurrentHashMap<String, String>()
    private var uploads = 0

    /** People in the Jam as the host sees them (refreshed when they come and go). */
    var guests by mutableStateOf<List<JamPerson>>(emptyList())
        private set

    fun addedBy(t: Track): String? = addedByMap[t.id]

    val jamName: String
        get() = if (role == JamRole.guest) (session?.jamName ?: "Jam")
        else if (hasName) "$myName's Jam" else "My Jam"

    private fun <T> onMain(f: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) f() else runBlocking(Dispatchers.Main) { f() }

    private val callbacks = JamHostCallbacks(
        state = { onMain { hostState() } },
        onAdd = { id, by, next -> withContext(Dispatchers.Main) { guestAdded(id, by, next) } },
        onUpload = { song, by, next -> withContext(Dispatchers.Main) { guestUploaded(song, by, next) } },
        onControl = { action, ms, by -> scope.launch { guestControl(action, ms, by) } },
        search = { q -> search(q) },
        art = { id -> artFor(id) },
    )

    /** Starts a Jam in this room, or online for friends anywhere. Returns false (and sets [error]) on failure. */
    suspend fun startHosting(online: Boolean = false): Boolean {
        if (active) return false
        error = null
        this.online = online
        try {
            // Songs friends sent to an earlier Jam aren't needed any more.
            withContext(Dispatchers.IO) {
                cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("jam-") }?.forEach {
                    try { it.deleteRecursively() } catch (_: Exception) {}
                }
            }
            if (online) {
                val code = OnlineJamCode.create()
                val h = OnlineJamHost(code, jamName, if (hasName) myName else "Host", callbacks)
                h.onPeopleChanged = ::peopleChanged
                host = h
                joinCode = code
                role = JamRole.host
                h.start()
                scope.launch { shareLibrary() }
                scope.launch { shareUpcoming() }
                // Refreshes links before they expire.
                tick = scope.launch {
                    while (isActive) {
                        delay(60_000)
                        shareUpcoming()
                    }
                }
            } else {
                val h = JamHost(
                    jamName, if (hasName) myName else "Host",
                    File(cacheDir, "jam-${System.currentTimeMillis()}"), callbacks,
                )
                h.onPeopleChanged = ::peopleChanged
                h.start()
                host = h
                val ip = withContext(Dispatchers.IO) { JamHost.localAddress() }
                joinCode = ip?.let { JamCode.encode(it, h.port) }
                role = JamRole.host
                // Keeps everyone's progress bar honest even when nothing changes.
                tick = scope.launch {
                    while (isActive) {
                        delay(5000)
                        push()
                    }
                }
            }
            watchPlayer()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            role = JamRole.none
            endHosting()
            error = if (online) "Could not start an online Jam. Check your internet connection."
            else "Could not start a Jam. Check that Wi-Fi or your hotspot is on."
            return false
        }
    }

    fun setGuestsControl(on: Boolean) {
        guestsControl = on
        host?.guestsControl = on
        push()
    }

    fun removeGuest(id: String) {
        val h = host ?: return
        io.launch { h.remove(id) }
    }

    /** Tells the friends what's going on now (network work, so off the main thread). */
    private fun push() {
        val h = host ?: return
        io.launch { h.pushState() }
    }

    private fun peopleChanged() {
        val h = host
        scope.launch { guests = h?.guests ?: emptyList() }
    }

    private fun watchPlayer() {
        playerWatch = scope.launch {
            snapshotFlow {
                listOf(player.current?.id, player.isPlaying, player.queue.size, player.currentIndex, player.shuffle)
            }.collect { playerChanged() }
        }
        val l = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) playerChanged()
            }
        }
        playerListener = l
        player.player.addListener(l)
    }

    private fun playerChanged() {
        debounce?.cancel()
        debounce = scope.launch {
            delay(250)
            if (online) scope.launch { shareUpcoming() }
            push()
        }
    }

    /** Must run on the main thread (reads the player). */
    private val upcoming: List<Track>
        get() {
            val exo = player.player
            val tl = exo.currentTimeline
            if (tl.isEmpty) return emptyList()
            val out = mutableListOf<Track>()
            var i = exo.currentMediaItemIndex
            repeat(30) {
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, exo.shuffleModeEnabled)
                if (i == C.INDEX_UNSET) return out
                player.queue.getOrNull(i)?.let { out.add(it) }
            }
            return out
        }

    private fun jamTrack(t: Track): JamTrack {
        val link = links[t.id]
        return JamTrack(
            id = t.id,
            title = t.title,
            artist = t.artist,
            durationMs = t.duration,
            by = addedByMap[t.id],
            hasArt = t.artworkUrl != null || t.mediaId != null || library.downloadedCopy(t)?.mediaId != null,
            url = link?.url,
            artUrl = link?.art,
        )
    }

    private fun hostState(): JamState {
        val now = player.current
        return JamState(
            now = now?.let { jamTrack(it) },
            playing = player.isPlaying,
            positionMs = player.player.currentPosition,
            at = System.currentTimeMillis(),
            queue = upcoming.map { jamTrack(it) },
            guestsControl = guestsControl,
            event = event,
            library = libraryLink,
        )
    }

    private fun search(q: String): List<JamTrack> {
        val query = q.trim().lowercase()
        val songs = if (query.isEmpty()) library.forYou + library.allSongs
        else library.allSongs.filter { it.title.lowercase().contains(query) || it.artist.lowercase().contains(query) }
        val seen = HashSet<String>()
        return songs.filter { seen.add(it.id) }.map { jamTrack(it) }
    }

    private fun find(id: String): Track? =
        player.queue.firstOrNull { it.id == id } ?: library.allSongs.firstOrNull { it.id == id }

    private fun queueSong(t: Track, next: Boolean, by: String) {
        addedByMap[t.id] = by
        if (player.current == null) player.playQueue(listOf(t), 0)
        else if (next) player.playNext(t)
        else player.addToQueue(listOf(t))
        announce("$by added ${t.title}")
    }

    private fun guestAdded(id: String, by: JamPerson, next: Boolean) {
        val t = find(id)
        if (t != null) queueSong(t, next, by.name)
    }

    private fun guestUploaded(song: JamUpload, by: JamPerson, next: Boolean) {
        val t = Track(
            id = "jam:${uploads++}:${song.title}",
            title = song.title,
            artist = song.artist,
            source = TrackSource.file,
            uri = song.source,
            artworkUrl = song.art,
            duration = song.durationMs,
            album = "From ${by.name} in the Jam",
        )
        // Online, the friend's link can be passed on as it is.
        if (song.source.startsWith("http")) {
            val lasts = if (song.source.contains("uguu.se")) 3L else 12L
            links[t.id] = Link(song.source, song.art, System.currentTimeMillis() + lasts * 3_600_000L)
        }
        queueSong(t, next, by.name)
    }

    private fun guestControl(action: String, ms: Long?, by: JamPerson) {
        when (action) {
            "toggle" -> player.togglePlay()
            "next" -> {
                player.next()
                announce("${by.name} skipped")
            }
            "previous" -> player.previous()
            "seek" -> if (ms != null) player.seek(ms)
        }
    }

    private fun announce(what: String) {
        event = what
        push()
    }

    private val artCache = object : LinkedHashMap<String, ByteArray>() {}

    private suspend fun artFor(id: String): ByteArray? {
        synchronized(artCache) { artCache[id] }?.let { return it }
        val t = withContext(Dispatchers.Main) { find(id) } ?: return null
        val bytes = coverBytes(t)
        if (bytes != null && bytes.isNotEmpty()) {
            synchronized(artCache) {
                if (artCache.size > 120) artCache.remove(artCache.keys.first())
                artCache[id] = bytes
            }
        }
        return bytes
    }

    private suspend fun fetchBytes(url: String): ByteArray = Http.get(url).use { r ->
        withContext(Dispatchers.IO) { r.body.bytes() }
    }

    private suspend fun coverBytes(t: Track): ByteArray? {
        try {
            val media = t.mediaId ?: library.downloadedCopy(t)?.mediaId
            val url = t.artworkUrl
            if (url != null && url.startsWith("/")) {
                return withContext(Dispatchers.IO) { File(url).readBytes() }
            }
            if (url != null && TelegramFiles.isThumb(url)) {
                val link = TelegramFiles.resolveThumb(url)
                if (link != null) return fetchBytes(link)
            } else if (url != null && url.startsWith("http")) {
                return fetchBytes(url)
            } else if (media != null) {
                return device.artwork(media)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return null
    }

    // ---- Sharing songs in an online Jam ----------------------------------------

    private class Link(val url: String, val art: String?, val expires: Long)

    /** Track id -> (song link, cover link, when the link stops working). */
    private val links = ConcurrentHashMap<String, Link>()

    @Volatile
    private var libraryLink: String? = null
    private var sharing = false
    private var retryShareJob: Job? = null

    /** The song being uploaded for friends right now, for the host's screen. */
    var sharingTitle by mutableStateOf<String?>(null)

    /** Why the last song couldn't be shared, shown to the host. */
    var shareError by mutableStateOf<String?>(null)

    private fun linkUsable(id: String): Boolean {
        val link = links[id] ?: return false
        // Share again a few minutes before the link expires.
        return link.expires - System.currentTimeMillis() > 8 * 60_000L
    }

    /** Uploads the current and next song, so friends can play them. */
    private suspend fun shareUpcoming() {
        if (sharing || !online || role != JamRole.host) return
        sharing = true
        try {
            val todo = listOfNotNull(player.current) + upcoming.take(1)
            for (t in todo) {
                if (linkUsable(t.id) || t.isRadio) continue
                sharingTitle = t.title
                try {
                    val (file, ext) = fileFor(t)
                    val cover = coverBytes(t)
                    var art: String? = null
                    if (cover != null && cover.isNotEmpty()) {
                        try {
                            art = FileDrop.upload(bytes = cover, name = "cover.jpg").first
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    val (url, lasts) = FileDrop.upload(file = file, name = "song.$ext", verify = true)
                    if (role != JamRole.host) return
                    links[t.id] = Link(url, art, System.currentTimeMillis() + lasts.inWholeMilliseconds)
                    shareError = null
                    push()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    shareError = "Couldn't share \"${t.title}\" with friends: ${FileDrop.describe(e)}"
                    break
                }
            }
        } finally {
            sharing = false
            sharingTitle = null
        }
        if (role != JamRole.host || !online) return
        val now = player.current
        val missing = now != null && !now.isRadio && !linkUsable(now.id)
        if (missing && shareError == null) {
            // The song changed while uploading.
            scope.launch { shareUpcoming() }
        } else if (missing) {
            // Failed: keep trying, so friends aren't left in silence.
            retryShareJob?.cancel()
            retryShareJob = scope.launch {
                delay(20_000)
                shareUpcoming()
            }
        }
    }

    /** Tries sharing the current song again right away. */
    fun retryShare() {
        shareError = null
        scope.launch { shareUpcoming() }
    }

    /** Uploads the host's song list so friends can browse it. */
    private suspend fun shareLibrary() {
        try {
            val (url, _) = FileDrop.upload(
                bytes = OnlineJamGuest.libraryJson(search("")).toByteArray(Charsets.UTF_8),
                name = "library.json",
            )
            if (role != JamRole.host) return
            libraryLink = url
            push()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ---- Joining -----------------------------------------------------------

    private var session by mutableStateOf<JamSession?>(null)

    /** The Jam as the host last described it (guest side). */
    var state by mutableStateOf(JamState())
        private set
    val hostName: String get() = session?.hostName ?: ""

    /** Where the host's song is right now, in ms. */
    val position: Long get() = session?.positionMs ?: 0L
    private var stateJob: Job? = null

    /** Jams seen on this Wi-Fi recently, while [startLooking] runs (key = "address:port"). */
    val nearby = mutableStateMapOf<String, JamFound>()
    private var looking: Job? = null
    private var prune: Job? = null
    var lookingFailed by mutableStateOf(false)
        private set

    fun startLooking() {
        if (looking != null) return
        lookingFailed = false
        device.multicastLock(true)
        looking = scope.launch {
            try {
                JamGuest.discover().collect { f -> nearby[f.key] = f }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                lookingFailed = true
            }
        }
        prune = scope.launch {
            while (isActive) {
                delay(2000)
                val now = System.currentTimeMillis()
                nearby.entries.filter { now - it.value.seen > 7000 }.forEach { nearby.remove(it.key) }
            }
        }
    }

    fun stopLooking() {
        looking?.cancel()
        looking = null
        prune?.cancel()
        nearby.clear()
        device.multicastLock(false)
    }

    /** Joins a Jam found on this Wi-Fi. Returns an error message or null. */
    suspend fun join(address: String, port: Int): String? {
        if (active) return "You're already in a Jam."
        val g = JamGuest(address, port)
        try {
            g.join(if (hasName) myName else "Guest")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return "Could not reach that Jam. Make sure you're on the same Wi-Fi."
        }
        joined(g, isOnline = false)
        return null
    }

    /** Joins with a code: an online Jam's code, or a same-room address code. */
    suspend fun joinWithCode(code: String): String? {
        val onlineCode = OnlineJamCode.normalise(code)
        if (onlineCode != null) {
            if (active) return "You're already in a Jam."
            val g = OnlineJamGuest(onlineCode)
            try {
                g.join(if (hasName) myName else "Guest")
            } catch (e: CancellationException) {
                throw e
            } catch (e: JamError) {
                return e.message
            } catch (_: Exception) {
                return "Could not reach the Jam. Check your internet connection."
            }
            joined(g, isOnline = true)
            return null
        }
        val at = JamCode.decode(code) ?: return "That code doesn't look right."
        return join(at.first, at.second)
    }

    private fun joined(g: JamSession, isOnline: Boolean) {
        stopLooking()
        session = g
        online = isOnline
        role = JamRole.guest
        error = null
        event = g.state.event // the first update can arrive before we listen
        state = g.state
        listenHere = isOnline
        stateJob = scope.launch {
            g.states.collect { s ->
                state = s
                if (s.event != null) event = s.event
                follow()
            }
        }
        scope.launch {
            val reason = g.closed.await()
            if (session !== g) return@launch
            stopFollowing()
            role = JamRole.none
            endHosting()
            clearGuest()
            error = when (reason) {
                "ended" -> "The host ended the Jam."
                "removed" -> "The host removed you from the Jam."
                "lost" -> "Lost connection to the Jam."
                else -> null
            }
        }
        if (isOnline) {
            startFollowTimer()
            scope.launch { follow() }
        }
    }

    // ---- Listening along (online) ------------------------------------------

    /** Plays the host's songs on this phone, in step with the host. */
    var listenHere by mutableStateOf(false)
    private var followingId: String? = null
    private var loading = false
    private var followTimer: Job? = null

    /** The host's song is still being shared. */
    val waitingForSong: Boolean
        get() = online && role == JamRole.guest && state.now != null && state.now?.url == null

    private fun startFollowTimer() {
        followTimer?.cancel()
        followTimer = scope.launch {
            while (isActive) {
                delay(4000)
                follow()
            }
        }
    }

    fun setListenHere(on: Boolean) {
        listenHere = on
        if (on) {
            followingId = null
            if (followTimer == null && online) startFollowTimer()
            scope.launch { follow() }
        } else {
            followTimer?.cancel()
            followTimer = null
            if (followingId != null && (player.current?.isJam ?: false)) player.pause()
            followingId = null
        }
    }

    private fun followTrack(t: JamTrack, uri: String) = Track(
        id = "jam:online:${t.id}",
        title = t.title,
        artist = t.artist,
        source = TrackSource.file,
        uri = uri,
        artworkUrl = t.artUrl,
        duration = t.durationMs,
        album = "${session?.jamName ?: "Jam"} with ${session?.hostName}",
    )

    /** What listening along is doing, for the screen: null when all is well. */
    var followStatus by mutableStateOf<String?>(null)
        private set
    var followFailed by mutableStateOf(false)
        private set

    private fun setFollow(status: String?, failed: Boolean = false) {
        followStatus = status
        followFailed = failed
    }

    /** Tries the current song again after it failed. */
    fun retryFollow() {
        followingId = null
        setFollow(null)
        scope.launch { follow() }
    }

    private suspend fun follow() {
        val s = session
        if (s == null || !online || !listenHere || loading) return
        val now = s.state.now
        val mine = player.current
        // Playing something else on this phone means "stop listening along".
        if (followingId != null && mine != null && !mine.isJam) {
            listenHere = false
            followingId = null
            setFollow(null)
            return
        }
        if (now == null || now.url == null) {
            if (followingId != null && player.isPlaying) player.pause()
            return
        }
        loading = true
        try {
            if (followingId != now.id) {
                followingId = now.id
                setFollow("Loading ${now.title}…")
                // Stream the link; if this phone can't, download it first.
                var err = player.playJam(followTrack(now, now.url!!), s.positionMs + 400, s.state.playing)
                var why: String? = null
                if (err != null && followingId == now.id) {
                    setFollow("Downloading ${now.title}…")
                    try {
                        val file = download(now.url!!)
                        err = player.playJam(followTrack(now, file.path), s.positionMs, s.state.playing)
                        if (err != null) why = "the song file won't play ($err)"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        err = e.toString()
                        why = FileDrop.describe(e)
                    }
                }
                if (followingId != now.id) return // the song changed meanwhile
                if (err == null) {
                    setFollow(null)
                } else {
                    val host = try { android.net.Uri.parse(now.url).host } catch (_: Exception) { null } ?: "the file host"
                    setFollow("Couldn't play ${now.title}: ${why ?: "unknown problem"} ($host).", failed = true)
                }
                return
            }
            if (followFailed) return
            val pos = s.positionMs
            if (s.state.playing && !player.isPlaying) {
                player.seek(pos)
                player.play()
            } else if (!s.state.playing && player.isPlaying) {
                player.pause()
            } else if (s.state.playing) {
                val drift = player.player.currentPosition - pos
                if (Math.abs(drift) > 1500) player.seek(pos)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            loading = false
        }
    }

    /** Saves a shared song to a temporary file. */
    private suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("jam-in-") }?.forEach {
            try { it.delete() } catch (_: Exception) {}
        }
        val file = File(cacheDir, "jam-in-${System.currentTimeMillis()}.mp3")
        val req = Request.Builder().url(url).header("User-Agent", JAM_USER_AGENT).build()
        val client = Http.client.newBuilder().callTimeout(3, TimeUnit.MINUTES).build()
        client.newCall(req).execute().use { res ->
            if (res.code != 200) throw JamError("HTTP ${res.code}")
            file.outputStream().use { out -> res.body.byteStream().copyTo(out) }
            val head = file.inputStream().use { it.readNBytes(64) }
            if (FileDrop.looksLikePage(head, res.header("content-type"))) {
                throw JamError("the link gives a web page, not the song")
            }
        }
        file
    }

    private fun stopFollowing() {
        followTimer?.cancel()
        followTimer = null
        if (followingId != null && (player.current?.isJam ?: false)) player.pause()
        followingId = null
        listenHere = false
        followStatus = null
        followFailed = false
    }

    // ---- Adding songs as a guest ---------------------------------------------

    /** A song in the Jam as a [Track], for showing it with the usual widgets. */
    fun viewTrack(t: JamTrack) = Track(
        id = "jamview:${t.id}",
        title = t.title,
        artist = t.artist,
        source = TrackSource.file,
        duration = t.durationMs,
        artworkUrl = session?.artFor(t),
    )

    suspend fun searchHost(q: String): List<JamTrack> = session?.search(q) ?: emptyList()

    fun addFromHost(t: JamTrack, next: Boolean = false) {
        session?.add(t.id, next)
    }

    fun control(action: String, ms: Long? = null) {
        session?.control(action, ms)
    }

    /** Songs being sent right now (track id -> progress 0..1). */
    val sending = mutableStateMapOf<String, Double>()

    /** Sends one of my songs to the Jam. Returns an error message or null. */
    suspend fun send(t: Track, next: Boolean = false): String? {
        val g = session
        if (g == null || sending.containsKey(t.id)) return null
        sending[t.id] = 0.0
        try {
            val (file, ext) = fileFor(t)
            g.sendSong(
                audio = file,
                ext = ext,
                title = t.title,
                artist = t.artist,
                art = coverBytes(t),
                durationMs = t.duration,
                next = next,
                onProgress = { p -> sending[t.id] = p },
            )
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: JamError) {
            return e.message
        } catch (_: Exception) {
            return "Could not send ${t.title}."
        } finally {
            sending.remove(t.id)
        }
    }

    private fun extOf(mime: String?, fallback: String): String = when (mime) {
        "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> fallback
    }

    private suspend fun fetchToCache(url: String, ext: String): File = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "jam-out-${System.nanoTime() / 1000}.$ext")
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (res.code != 200) throw JamError("Could not get it.")
            file.outputStream().use { out -> res.body.byteStream().copyTo(out) }
        }
        file
    }

    /** A local file with the song's audio, fetching or copying it if needed. */
    private suspend fun fileFor(t: Track): Pair<File, String> {
        val copy = library.downloadedCopy(t)
        val src = copy ?: t
        val uri = src.uri
        if (uri != null && uri.startsWith("http")) {
            // A song a friend shared, being passed along.
            return fetchToCache(uri, "mp3") to "mp3"
        }
        if (src.source == TrackSource.file && uri != null) {
            val dot = uri.lastIndexOf('.')
            return File(uri) to (if (dot > 0) uri.substring(dot + 1) else "mp3")
        }
        if (uri != null && uri.startsWith("content://")) {
            val path = device.copyToCache(uri, "song") ?: throw JamError("Could not read that song.")
            return File(path) to extOf(t.mime, "mp3")
        }
        val bot = library.bot
        if (t.source == TrackSource.telegram && bot != null) {
            val ext = extOf(t.mime, "mp3")
            return fetchToCache(bot.fileUrl(t.uri!!), ext) to ext
        }
        throw JamError("That song can't be shared.")
    }

    // ---- Leaving -----------------------------------------------------------

    private fun clearGuest() {
        stateJob?.cancel()
        stateJob = null
        session = null
        sending.clear()
        role = JamRole.none
    }

    private suspend fun endHosting() {
        playerWatch?.cancel()
        playerWatch = null
        playerListener?.let { player.player.removeListener(it) }
        playerListener = null
        tick?.cancel()
        debounce?.cancel()
        val h = host
        host = null
        joinCode = null
        guests = emptyList()
        addedByMap.clear()
        synchronized(artCache) { artCache.clear() }
        links.clear()
        libraryLink = null
        retryShareJob?.cancel()
        shareError = null
        // Friends' songs stay until the next Jam, so the queue keeps playing.
        h?.stop(deleteUploads = false)
    }

    /** Ends the Jam for everyone (host) or leaves it (guest). */
    suspend fun leave() {
        if (role == JamRole.host) {
            role = JamRole.none
            endHosting()
        } else if (role == JamRole.guest) {
            stopFollowing()
            val g = session
            clearGuest()
            g?.leave()
        }
        role = JamRole.none
        event = null
        online = false
    }
}
