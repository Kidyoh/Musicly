package com.musicly.musicly.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicly.musicly.net.Device
import com.musicly.musicly.net.RadioApi
import com.musicly.musicly.net.RssApi
import com.musicly.musicly.net.TelegramBackup
import com.musicly.musicly.net.TelegramBot
import com.musicly.musicly.net.TelegramError
import com.musicly.musicly.net.TelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** How the player looks: the colourful default, or a quiet, minimal one. */
enum class PlayerStyle { classic, minimal }

/** Light or dark appearance. */
enum class ThemeChoice { light, dark }

/**
 * Everything the user owns or that's personalised: likes, playlists, the
 * Telegram channels, phone music, RSS feeds, history, downloads and the home
 * feed. Every song here plays in full; there are no previews.
 *
 * Screens read [rev] (or call [observe]) so they redraw when anything changes.
 */
class Library(private val store: Store, private val device: Device) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Bumped on every change; reading it in a composable subscribes to updates. */
    var rev by mutableIntStateOf(0)
        private set

    fun observe(): Library {
        rev
        return this
    }

    private fun changed() {
        rev++
    }

    val radio = RadioApi()
    val rss = RssApi()

    var theme by mutableStateOf(ThemeChoice.light)
        private set
    @set:JvmName("assignPlayerStyle")
    var playerStyle by mutableStateOf(PlayerStyle.classic)
        private set

    val feeds = mutableListOf<RssFeed>()
    val favorites = mutableListOf<Track>()
    val recent = mutableListOf<Track>()
    val files = mutableListOf<Track>() // picked with the file picker
    var deviceTracks: List<Track> = emptyList() // scanned from the phone
    val playlists = mutableListOf<UserPlaylist>()

    /** Plays per song id and per artist name (lower-case), for "Your mix". */
    private val trackPlays = mutableMapOf<String, Int>()
    private val artistPlays = mutableMapOf<String, Int>()

    // Telegram channels, read directly with the Bot API (no server).
    var bot: TelegramBot? = null
    var botUsername: String? = null
    val channels = mutableListOf<TelegramChannel>()
    private val forwarded = mutableListOf<Track>()
    private val unclaimed = mutableMapOf<Long, MutableList<JsonObject>>()
    private var channelTracksCache: List<Track>? = null
    var channelLoading by mutableStateOf(false)
        private set
    var channelError by mutableStateOf<String?>(null)
        private set
    private var updateOffset = 0L
    private var userChatId: Long? = null
    var tooBigSkipped = 0
    var importing by mutableStateOf(false)
        private set
    var importingChannel: Long? = null
        private set
    var importScanned by mutableIntStateOf(0)
        private set
    var importFound by mutableIntStateOf(0)
        private set
    private var cancelImport = false
    private var syncJob: Job? = null
    val channelConnected get() = bot != null && channels.isNotEmpty()
    val canImport get() = userChatId != null

    /** Every song from every channel (plus ones sent to the bot), each once. */
    val channelTracks: List<Track>
        get() = channelTracksCache ?: run {
            val seen = mutableSetOf<String>()
            val out = mutableListOf<Track>()
            for (c in channels) for (t in c.tracks) if (seen.add(t.id)) out += t
            for (t in forwarded) if (seen.add(t.id)) out += t
            channelTracksCache = out
            out
        }

    val channelName: String?
        get() = when {
            channels.isEmpty() -> null
            channels.size == 1 -> channels.first().name
            else -> "Your channels"
        }

    fun channel(id: Long) = channels.firstOrNull { it.id == id }
    private fun channelsChanged() {
        channelTracksCache = null
    }

    // Channel songs saved to Music/Musicly on the phone
    var autoDownload = true
        private set
    private val downloads = mutableMapOf<String, Track>() // channel track id -> phone copy
    var downloading by mutableStateOf(false)
        private set
    var downloadDone by mutableIntStateOf(0)
        private set
    var downloadTotal by mutableIntStateOf(0)
        private set
    var downloadError by mutableStateOf<String?>(null)
        private set
    private val pendingDownloads = mutableListOf<Track>()

    // Backups: a pinned file in the bot chat, and a file in the phone's Download folder
    var lastBackup by mutableStateOf<String?>(null)
        private set
    var lastLocalBackup by mutableStateOf<String?>(null)
        private set
    var backingUp by mutableStateOf(false)
        private set
    var backupNote by mutableStateOf<String?>(null)
    private var backupMsgId: Long? = null
    private var backupJob: Job? = null
    private var restoreChecked = false
    val backupReady get() = bot != null && userChatId != null

    /** Lets the player reload its sound settings after a restore. */
    var onRestored: (() -> Unit)? = null

    /** Set by the activity: asks for the music permission, true if granted. */
    var requestMusicPermission: (suspend () -> Boolean)? = null

    var deviceScanEnabled = false
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanError by mutableStateOf<String?>(null)
        private set

    // Home feed
    var forYou by mutableStateOf<List<Track>>(emptyList())
        private set
    var forYouReason by mutableStateOf<String?>(null)
        private set
    var stations by mutableStateOf<List<Track>>(emptyList()) // live radio, local first
        private set
    var countryCode: String? = Locale.getDefault().country.ifEmpty { null }

    init {
        restore()
        scope.launch {
            loadHome()
            if (channelConnected) startSync()
            if (deviceScanEnabled) scanDevice(askPermission = false)
        }
    }

    fun isFavorite(t: Track) = favorites.any { it.id == t.id }
    val likedSongs get() = favorites.filter { !it.isRadio }
    val savedStations get() = favorites.filter { it.isRadio }

    /** Phone songs, minus the copies Musicly downloaded from the channel. */
    val localTracks: List<Track>
        get() {
            val copies = downloads.values.map { it.id }.toSet()
            return deviceTracks.filter { it.id !in copies } + files
        }

    /** Every full song the user has: channel first, then phone. */
    val allSongs get() = channelTracks + localTracks

    fun downloadedCopy(t: Track) = downloads[t.id]
    fun isDownloaded(t: Track) = downloads.containsKey(t.id)

    // ---- History & recommendations --------------------------------------------------

    fun recordPlay(t: Track) {
        if (t.isJam) return // only lives for the Jam
        recent.removeAll { it.id == t.id }
        recent.add(0, t)
        if (recent.size > 50) recent.removeAt(recent.lastIndex)
        if (t.isRadio) {
            scope.launch { radio.countClick(t.id) }
        } else {
            trackPlays[t.id] = (trackPlays[t.id] ?: 0) + 1
            val a = t.artist.lowercase()
            if (a.isNotEmpty() && a != "unknown artist") artistPlays[a] = (artistPlays[a] ?: 0) + 1
        }
        save()
        changed()
    }

    /** Artist names ranked by plays and likes. */
    fun topArtists(n: Int = 5): List<String> {
        val score = mutableMapOf<String, Double>()
        val display = mutableMapOf<String, String>()
        for (t in allSongs) display[t.artist.lowercase()] = t.artist
        artistPlays.forEach { (a, c) -> score[a] = (score[a] ?: 0.0) + c }
        for (f in likedSongs) {
            val a = f.artist.lowercase()
            score[a] = (score[a] ?: 0.0) + 3
        }
        score.keys.removeAll { it == "unknown artist" || !display.containsKey(it) }
        return score.keys.sortedByDescending { score[it] }.take(n).map { display[it]!! }
    }

    /** Songs ordered by how much you play them. */
    val mostPlayed: List<Track>
        get() = allSongs.filter { (trackPlays[it.id] ?: 0) > 0 }.sortedByDescending { trackPlays[it.id] ?: 0 }

    fun plays(t: Track) = trackPlays[t.id] ?: 0

    /** Your artists (channel + phone), most songs first. */
    val artists: List<Pair<String, List<Track>>>
        get() {
            val map = linkedMapOf<String, MutableList<Track>>()
            val names = mutableMapOf<String, String>()
            for (t in allSongs) {
                val k = t.artist.lowercase()
                if (k == "unknown artist") continue
                names.putIfAbsent(k, t.artist)
                map.getOrPut(k) { mutableListOf() } += t
            }
            return map.entries.map { names[it.key]!! to it.value.toList() }.sortedByDescending { it.second.size }
        }

    fun songsBy(artist: String): List<Track> {
        val k = artist.lowercase()
        return allSongs.filter { it.artist.lowercase() == k }
    }

    /**
     * "Your mix": a fresh blend of your own channel and phone songs, weighted
     * toward what you like and play, mixed with songs you haven't heard lately.
     */
    fun buildForYou() {
        val pool = allSongs
        if (pool.isEmpty()) {
            forYou = emptyList()
            forYouReason = null
            return
        }
        val now = java.util.Calendar.getInstance()
        val rng = Random(now.get(java.util.Calendar.DAY_OF_MONTH) * 31 + now.get(java.util.Calendar.HOUR_OF_DAY) / 6)
        val top = topArtists(3).map { it.lowercase() }.toSet()
        val liked = likedSongs.map { it.id }.toSet()
        val lately = recent.take(8).map { it.id }.toSet()
        fun weight(t: Track): Double {
            var w = 1.0
            if (t.id in liked) w += 3
            if (t.artist.lowercase() in top) w += 2
            w += min(trackPlays[t.id] ?: 0, 10) * 0.3
            if ((trackPlays[t.id] ?: 0) == 0) w += 0.8 // something new
            if (t.id in lately) w *= 0.2 // just heard it
            return w
        }
        // Weighted shuffle (Efraimidis–Spirakis): higher weight, earlier.
        val rest = pool.map { rng.nextDouble().pow(1 / weight(it)) to it }
            .sortedByDescending { it.first }.map { it.second }.toMutableList()
        // Avoid the same artist twice in a row where possible. An artist who
        // would otherwise be left with songs at the very end goes first.
        val mix = mutableListOf<Track>()
        fun key(t: Track) = t.artist.lowercase()
        while (rest.isNotEmpty() && mix.size < 40) {
            val last = mix.lastOrNull()?.let(::key)
            val counts = rest.groupingBy(::key).eachCount()
            val crowded = counts.entries.firstOrNull { it.key != last && it.value * 2 > rest.size }?.key
            var i = rest.indexOfFirst { if (crowded != null) key(it) == crowded else key(it) != last }
            if (i < 0) i = rest.indexOfFirst { key(it) != last }
            mix += rest.removeAt(if (i < 0) 0 else i)
        }
        forYou = mix
        val names = topArtists(2)
        val sources = listOfNotNull(
            if (channelTracks.isNotEmpty()) (if (channels.size > 1) "your channels" else "your channel") else null,
            if (localTracks.isNotEmpty()) "your phone" else null,
        ).joinToString(" & ")
        forYouReason = if (names.isEmpty()) "A mix from $sources" else "From $sources · ${names.joinToString(" & ")} and more"
    }

    suspend fun loadHome() {
        buildForYou()
        changed()
        val local = countryCode?.let { runCatching { radio.byCountry(it, 12) }.getOrDefault(emptyList()) } ?: emptyList()
        val top = runCatching { radio.top(20) }.getOrDefault(emptyList())
        val seen = mutableSetOf<String>()
        stations = (local + top).filter { seen.add(it.id) }.take(20)
        changed()
    }

    // ---- Likes ----------------------------------------------------------------------

    fun toggleFavorite(t: Track) {
        if (isFavorite(t)) favorites.removeAll { it.id == t.id } else favorites.add(0, t)
        save()
        changed()
    }

    fun favoriteAll(tracks: List<Track>) {
        for (t in tracks.reversed()) if (!isFavorite(t)) favorites.add(0, t)
        save()
        changed()
    }

    // ---- Playlists ------------------------------------------------------------------

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): UserPlaylist {
        val p = UserPlaylist(
            id = "${System.nanoTime() / 1000}${Random.nextInt(999)}",
            name = name.trim().ifEmpty { "My playlist" },
            tracks = tracks.toMutableList(),
        )
        playlists.add(0, p)
        save()
        changed()
        return p
    }

    fun playlist(id: String) = playlists.firstOrNull { it.id == id }

    /** Returns how many songs were actually added (duplicates are skipped). */
    fun addToPlaylist(p: UserPlaylist, tracks: List<Track>): Int {
        var n = 0
        for (t in tracks) {
            if (!t.isPersistable || p.tracks.any { it.id == t.id }) continue
            p.tracks += t
            n++
        }
        save()
        changed()
        return n
    }

    fun removeFromPlaylist(p: UserPlaylist, t: Track) {
        p.tracks.removeAll { it.id == t.id }
        save()
        changed()
    }

    fun reorderPlaylist(p: UserPlaylist, from: Int, to: Int) {
        p.tracks.add(to, p.tracks.removeAt(from))
        save()
        changed()
    }

    fun renamePlaylist(p: UserPlaylist, name: String) {
        if (name.isBlank()) return
        p.name = name.trim()
        save()
        changed()
    }

    fun deletePlaylist(p: UserPlaylist) {
        playlists.removeAll { it.id == p.id }
        save()
        changed()
    }

    // ---- RSS feeds ------------------------------------------------------------------

    /** Adds a feed by link. Throws an error with a readable message. */
    suspend fun addFeed(input: String): RssFeed {
        val res = rss.load(input)
        val i = feeds.indexOfFirst { it.url == res.feed.url }
        if (i >= 0) feeds[i] = res.feed else feeds += res.feed
        save()
        changed()
        return res.feed
    }

    fun removeFeed(feed: RssFeed) {
        feeds.removeAll { it.url == feed.url }
        save()
        changed()
    }

    // ---- Telegram channel -----------------------------------------------------------

    /**
     * Checks the bot and channel and adds the channel. Without [token], the bot
     * that's already connected is used. Returns an error message, or null when connected.
     */
    suspend fun connectTelegram(token: String?, channelInput: String): String? {
        val b = if (token == null || token.trim() == bot?.token) bot else TelegramBot(token.trim())
        if (b == null) return "Paste your bot token first."
        try {
            val me = b.getMe()
            val chat = b.getChat(channelInput)
            val id = chat["id"].long()!!
            try {
                val member = b.call("getChatMember", mapOf("chat_id" to id, "user_id" to me["id"].long())).obj()
                val status = member["status"].str()
                if (status != "administrator" && status != "creator") {
                    return "Add @${me["username"].str()} to the channel as an admin first."
                }
            } catch (_: TelegramError) {
                return "Add @${me["username"].str()} to the channel as an admin first."
            }
            if (channel(id) != null) return "That channel is already connected."
            if (b !== bot) {
                // A different bot: its updates start from scratch.
                updateOffset = 0
                tooBigSkipped = 0
                unclaimed.clear()
            }
            bot = b
            TelegramFiles.bot = b
            botUsername = me["username"].str()
            val added = TelegramChannel(id, chat["title"].str() ?: channelInput)
            channels += added
            for (post in unclaimed.remove(id) ?: mutableListOf()) {
                val n = post["message_id"].long() ?: 0
                if (n > added.latestPostId) added.latestPostId = n
                addChannelMessage(post, added)
            }
            channelsChanged()
            saveNow()
            startSync()
            changed()
            return null
        } catch (e: TelegramError) {
            if (e.code == 401 || e.code == 404) return "That bot token isn't valid. Copy it again from @BotFather."
            if (e.description.contains("chat not found")) {
                return "Channel not found. Use its @username, or add the bot to the private channel first."
            }
            return e.description
        } catch (_: Exception) {
            return "Could not reach Telegram. Check your connection."
        }
    }

    private fun startSync() {
        TelegramFiles.bot = bot
        syncJob?.cancel()
        syncJob = scope.launch {
            while (true) {
                syncTelegram()
                delay(2 * 60_000)
            }
        }
    }

    /** Picks up new channel posts and songs forwarded to the bot. */
    suspend fun syncTelegram() {
        val b = bot
        if (b == null || channelLoading) return
        channelLoading = true
        channelError = null
        try {
            while (true) {
                val updates = b.getUpdates(updateOffset)
                if (updates.isEmpty()) break
                for (u in updates) {
                    updateOffset = (u["update_id"].long() ?: 0) + 1
                    val post = (u["channel_post"] ?: u["edited_channel_post"]).obj()
                    val msg = u["message"].obj()
                    val c = post?.let { channel(it["chat"]["id"].long() ?: 0) }
                    if (post != null && c != null) {
                        val id = post["message_id"].long() ?: 0
                        if (id > c.latestPostId) c.latestPostId = id
                        addChannelMessage(post, c)
                    } else if (post != null && post["audio"] != null) {
                        val chat = post["chat"]["id"].long() ?: 0
                        val list = unclaimed.getOrPut(chat) { mutableListOf() }
                        list += post
                        if (list.size > 300) list.removeAt(0)
                    } else if (msg != null && msg["chat"]["type"].str() == "private") {
                        if (userChatId == null) userChatId = msg["chat"]["id"].long()
                        addChannelMessage(msg, null) // songs forwarded to the bot
                    }
                }
            }
            saveNow()
            if (userChatId != null && !restoreChecked) {
                restoreChecked = true
                restoreFromTelegram(onlyIfFresh = true)
            }
            buildForYou()
            if (autoDownload && canDownload && notDownloaded > 0) scope.launch { downloadSongs(channelTracks) }
        } catch (e: TelegramError) {
            channelError = if (e.code == 409) "Another app is reading this bot's updates. Use a bot just for Musicly."
            else "Telegram: ${e.description}"
        } catch (_: Exception) {
            channelError = "Could not reach Telegram. Pull down to retry."
        }
        channelLoading = false
        changed()
    }

    /** Adds a song to [c], or to the songs sent to the bot when [c] is null. True if it was new. */
    private fun addChannelMessage(m: JsonObject, c: TelegramChannel?): Boolean {
        val t = TelegramBot.trackFrom(m, c?.name ?: "Sent to @$botUsername")
        if (t == null) {
            if (TelegramBot.isTooBig(m)) tooBigSkipped++
            return false
        }
        channelsChanged()
        val list = c?.tracks ?: forwarded
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) {
            list[i] = t
            return false
        }
        list.add(0, t)
        return true
    }

    /**
     * Bots can't list a channel's history, so each older post is briefly
     * forwarded to the user's private chat with the bot (silently), read, and deleted again.
     */
    suspend fun importTelegramHistory(c: TelegramChannel) {
        val b = bot
        val to = userChatId
        if (b == null || to == null || importing) return
        importing = true
        importingChannel = c.id
        cancelImport = false
        importScanned = 0
        importFound = 0
        var misses = 0
        val last = c.latestPostId
        var id = 1L
        while (!cancelImport) {
            if (last > 0 && id > last) break
            if (last == 0L && misses >= 150) break
            if (channel(c.id) == null) break // removed meanwhile
            try {
                val fwd = b.call("forwardMessage", mapOf("chat_id" to to, "from_chat_id" to c.id, "message_id" to id, "disable_notification" to true)).obj()!!
                misses = 0
                if (addChannelMessage(fwd, c)) importFound++
                try {
                    b.call("deleteMessage", mapOf("chat_id" to to, "message_id" to fwd["message_id"].long()))
                } catch (_: Exception) {
                }
                delay(350)
            } catch (e: TelegramError) {
                if (e.retryAfter != null) {
                    delay((e.retryAfter + 1) * 1000L)
                    continue // same post again
                }
                misses++ // deleted post or a service message
            } catch (_: Exception) {
                delay(2000)
                continue
            }
            importScanned = id.toInt()
            if (id % 10 == 0L) changed()
            id++
        }
        importing = false
        importingChannel = null
        buildForYou()
        saveNow()
        changed()
        if (autoDownload && canDownload && notDownloaded > 0) scope.launch { downloadSongs(channelTracks) }
    }

    fun cancelImport() {
        cancelImport = true
    }

    private val isFresh get() = favorites.isEmpty() && playlists.isEmpty() && recent.size < 3

    private fun scheduleBackup() {
        backupJob?.cancel()
        backupJob = scope.launch {
            delay(20_000)
            backupNow(fromTimer = true)
        }
    }

    /** Called when the app goes to the background: save pending changes now. */
    fun flushBackup() {
        val pending = backupJob?.isActive == true
        writeAll()
        if (pending) {
            backupJob?.cancel()
            scope.launch { backupNow(fromTimer = true) }
        }
    }

    /** Saves the library to Download/Musicly on the phone, and to the pinned backup in the bot chat. */
    suspend fun backupNow(fromTimer: Boolean = false): Boolean {
        if (backingUp) return false
        // The timer's own job must not cancel itself, and backing up never re-schedules a backup.
        if (!fromTimer) backupJob?.cancel()
        backingUp = true
        var ok = false
        try {
            writeAll()
            val snap = TelegramBackup.snapshot(store)
            if (device.saveBackupFile(snap.toString())) {
                lastLocalBackup = java.time.Instant.now().toString()
                store.put("local_backup_at", lastLocalBackup)
                ok = true
            }
            val b = bot
            val chat = userChatId
            if (b != null && chat != null) {
                backupMsgId = TelegramBackup(b, chat).upload(snap, backupMsgId)
                lastBackup = java.time.Instant.now().toString()
                store.put("backup_msg", backupMsgId!!)
                store.put("backup_at", lastBackup)
                ok = true
            }
        } catch (_: Exception) {
        } finally {
            backingUp = false
        }
        return ok
    }

    /** Restores from the text of a musicly-backup.json. Returns an error message or null. */
    suspend fun restoreFromText(text: String): String? {
        return try {
            val j = parseJson(text).obj() ?: return "Could not read that file."
            if (j["app"].str() != "musicly") return "That isn't a Musicly backup file."
            applyBackup(j)
            null
        } catch (_: Exception) {
            "Could not read that file."
        }
    }

    private fun applyBackup(backup: JsonObject) {
        TelegramBackup.apply(store, backup)
        reloadFromPrefs()
        onRestored?.invoke()
        fun n(c: Int, w: String) = "$c ${if (c == 1) w else w + "s"}"
        backupNote = "Restored ${n(likedSongs.size, "liked song")}, ${n(playlists.size, "playlist")} and ${n(channelTracks.size, "channel song")}"
        restoreDismissed = true
        changed()
    }

    /** Hides the "restore your library" card on a fresh install. */
    var restoreDismissed by mutableStateOf(false)
        private set
    val showRestoreCard get() = !restoreDismissed && isFresh && lastLocalBackup == null

    fun dismissRestore() {
        restoreDismissed = true
    }

    /** Brings back a backup from the bot chat. With [onlyIfFresh], only on a new install. */
    suspend fun restoreFromTelegram(onlyIfFresh: Boolean = false): Boolean {
        val b = bot
        val chat = userChatId
        if (b == null || chat == null) return false
        if (onlyIfFresh && !isFresh) return false
        return try {
            val backup = TelegramBackup(b, chat).latest() ?: return false
            backupMsgId = backup["_messageId"].long()
            applyBackup(backup)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun reloadFromPrefs() {
        val keepBot = bot
        val keepChat = userChatId
        favorites.clear(); recent.clear(); files.clear(); playlists.clear()
        trackPlays.clear(); artistPlays.clear(); downloads.clear()
        restore()
        if (bot == null) bot = keepBot
        if (userChatId == null) userChatId = keepChat
        TelegramFiles.bot = bot
        saveNow()
        scope.launch { loadHome() }
    }

    fun disconnectTelegram() {
        syncJob?.cancel()
        // Songs already saved stay on the phone and show up as phone music.
        downloads.clear()
        if (deviceScanEnabled) scope.launch { scanDevice(askPermission = false) }
        backupJob?.cancel()
        cancelImport = true
        bot = null
        TelegramFiles.bot = null
        channels.clear()
        forwarded.clear()
        unclaimed.clear()
        channelsChanged()
        save()
        changed()
    }

    /** Stops reading one channel. Its songs already saved on the phone stay there. */
    fun removeChannel(id: Long) {
        val c = channel(id) ?: return
        if (importingChannel == id) cancelImport = true
        channels.remove(c)
        channelsChanged()
        val still = channelTracks.map { it.id }.toSet()
        val dropped = downloads.keys.filter { it !in still }
        dropped.forEach { downloads.remove(it) }
        if (dropped.isNotEmpty() && deviceScanEnabled) scope.launch { scanDevice(askPermission = false) }
        if (channels.isEmpty()) syncJob?.cancel()
        buildForYou()
        save()
        changed()
    }

    // ---- Saving channel songs to the phone -----------------------------------------

    val canDownload get() = device.supported && channelConnected
    val notDownloaded get() = channelTracks.count { !downloads.containsKey(it.id) }

    /** "Artist - Title.mp3", safe for every file system. */
    private fun fileName(t: Track): String {
        val ext = when (t.mime) {
            "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> "m4a"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> "mp3"
        }
        val base = (if (t.artist == "Unknown artist") t.title else "${t.artist} - ${t.title}")
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "").trim()
        return "${if (base.isEmpty()) t.id.substring(3) else base.substring(0, min(base.length, 120))}.$ext"
    }

    fun setAutoDownload(on: Boolean) {
        autoDownload = on
        save()
        changed()
        if (on) scope.launch { downloadSongs(channelTracks) }
    }

    /** Saves channel songs into Music/Musicly, one at a time. Songs already there are linked instead. */
    suspend fun downloadSongs(tracks: List<Track>) {
        val b = bot
        if (!canDownload || b == null) return
        val todo = tracks.filter { it.source == TrackSource.telegram && !downloads.containsKey(it.id) }.toMutableList()
        if (todo.isEmpty()) return
        if (downloading) {
            pendingDownloads += todo
            return
        }
        if (requestMusicPermission?.invoke() != true) {
            downloadError = "Allow access to music to save songs on this phone."
            changed()
            return
        }
        downloading = true
        downloadError = null
        downloadDone = 0
        downloadTotal = todo.size
        var failed = 0
        var i = 0
        while (i < todo.size) {
            val t = todo[i]
            if (bot == null) break // disconnected
            if (downloads.containsKey(t.id)) {
                downloadDone++
                i++
                continue
            }
            try {
                val name = fileName(t)
                val copy = device.findAudio(name, t) ?: device.saveAudio(b.fileUrl(t.uri!!), name, t, t.mime ?: "audio/mpeg")
                if (copy != null) downloads[t.id] = copy
            } catch (_: Exception) {
                failed++
            }
            downloadDone++
            if (i % 3 == 0 || i == todo.size - 1) {
                changed()
                saveDownloads()
            }
            if (pendingDownloads.isNotEmpty()) {
                todo += pendingDownloads.filter { it !in todo }
                downloadTotal = todo.size
                pendingDownloads.clear()
            }
            i++
        }
        downloading = false
        if (failed > 0) downloadError = "$failed ${if (failed == 1) "song" else "songs"} couldn't be saved. They'll be retried next sync."
        saveNow()
        changed()
    }

    private fun saveDownloads() {
        store.put("downloads", JsonObject(downloads.mapValues { it.value.toJson() }).toString())
    }

    // ---- Music on the device ---------------------------------------------------------

    suspend fun scanDevice(askPermission: Boolean = true): Boolean {
        if (!device.supported) return false
        scanning = true
        scanError = null
        try {
            val ok = if (askPermission) requestMusicPermission?.invoke() == true else device.hasPermission()
            if (!ok) {
                scanError = "Permission needed to read your music."
                return false
            }
            deviceTracks = device.scan()
            buildForYou()
            deviceScanEnabled = true
            saveNow()
            return true
        } catch (_: Exception) {
            scanError = "Could not read music on this phone."
            return false
        } finally {
            scanning = false
            changed()
        }
    }

    /** Phone songs grouped by album: (album name, artist, tracks). */
    val deviceAlbums: List<Triple<String, String, List<Track>>>
        get() = deviceTracks.groupBy { it.albumId ?: it.album ?: "?" }.values
            .map { Triple(it.first().album ?: "Unknown album", it.first().artist, it) }
            .sortedBy { it.first.lowercase() }

    /** Adds songs picked with the system file picker. Returns how many were new. */
    fun addPickedFiles(picked: List<Pair<String, String>>): Int { // (uri, display name)
        var added = 0
        for ((uri, name) in picked) {
            val id = "file:$uri"
            if (files.any { it.id == id }) continue
            val (artist, title) = splitName(name)
            files += Track(id = id, title = title, artist = artist, source = TrackSource.file, uri = uri)
            added++
        }
        saveNow()
        changed()
        return added
    }

    fun removeFile(t: Track) {
        files.removeAll { it.id == t.id }
        save()
        changed()
    }

    /** "Artist - Title.mp3" → (Artist, Title). */
    private fun splitName(n: String): Pair<String, String> {
        val dot = n.lastIndexOf('.')
        val base = (if (dot > 0) n.substring(0, dot) else n).replace('_', ' ').trim()
        val dash = base.indexOf(" - ")
        if (dash > 0) return base.substring(0, dash).trim() to base.substring(dash + 3).trim()
        return "Unknown artist" to base
    }

    fun setPlayerStyle(style: PlayerStyle) {
        if (style == playerStyle) return
        playerStyle = style
        save()
    }

    fun toggleTheme() {
        theme = if (theme == ThemeChoice.dark) ThemeChoice.light else ThemeChoice.dark
        save()
    }

    // ---- Persistence ---------------------------------------------------------------------

    private var saveJob: Job? = null

    /** Writes everything soon (changes are batched). */
    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            saveNow()
        }
    }

    fun saveNow() {
        writeAll()
        scheduleBackup()
    }

    /** Writes everything to storage right now, without scheduling a backup. */
    private fun writeAll() {
        saveJob?.cancel()
        try {
            store.put("files", files.toJson().toString())
            store.put("favorites", favorites.toJson().toString())
            store.put("recent", recent.toJson().toString())
            store.put("playlists", JsonArray(playlists.map { it.toJson() }).toString())
            store.put("plays", jsonOf("tracks" to trackPlays, "artists" to artistPlays).toString())
            saveDownloads()
            store.put("autoDownload", autoDownload)
            store.put("deviceScan", deviceScanEnabled)
            val b = bot
            if (b == null) {
                store.remove("telegram")
            } else {
                store.put(
                    "telegram",
                    jsonOf(
                        "token" to b.token, "bot" to botUsername, "offset" to updateOffset,
                        "userChat" to userChatId, "tooBig" to tooBigSkipped,
                        "channels" to channels.map { it.toJson() },
                        "forwarded" to forwarded.map { it.toJson() },
                        "unclaimed" to unclaimed.entries.associate { it.key.toString() to it.value },
                    ).toString(),
                )
            }
            store.put("theme", theme.name)
            store.put("playerStyle", playerStyle.name)
            store.put("feeds", JsonArray(feeds.map { it.toJson() }).toString())
        } catch (_: Exception) {
        }
    }

    private fun restore() {
        try {
            backupMsgId = store.long("backup_msg")
            lastBackup = store.string("backup_at")
            lastLocalBackup = store.string("local_backup_at")
            fun dec(k: String) = Track.listFromJson(parseJson(store.string(k) ?: "[]"))
            files += dec("files")
            favorites += dec("favorites")
            recent += dec("recent")
            playlists += (parseJson(store.string("playlists") ?: "[]").arr() ?: JsonArray(emptyList())).mapNotNull { UserPlaylist.fromJson(it) }
            val plays = parseJson(store.string("plays") ?: "{}").obj()
            plays?.get("tracks").obj()?.forEach { (k, v) -> trackPlays[k] = v.int() ?: 0 }
            plays?.get("artists").obj()?.forEach { (k, v) -> artistPlays[k] = v.int() ?: 0 }
            parseJson(store.string("downloads") ?: "{}").obj()?.forEach { (k, v) -> Track.fromJson(v)?.let { downloads[k] = it } }
            autoDownload = store.bool("autoDownload") ?: true
            deviceScanEnabled = store.bool("deviceScan") ?: false
            store.string("telegram")?.let { tg ->
                val j = parseJson(tg).obj()!!
                bot = TelegramBot(j["token"].str()!!)
                TelegramFiles.bot = bot
                botUsername = j["bot"].str()
                updateOffset = j["offset"].long() ?: 0
                userChatId = j["userChat"].long()
                tooBigSkipped = j["tooBig"].int() ?: 0
                channels.clear()
                channels += TelegramChannel.listFromSettings(j)
                forwarded.clear()
                forwarded += Track.listFromJson(j["forwarded"])
                unclaimed.clear()
                j["unclaimed"].obj()?.forEach { (k, v) ->
                    unclaimed[k.toLong()] = (v.arr() ?: JsonArray(emptyList())).mapNotNull { it.obj() }.toMutableList()
                }
                channelsChanged()
            }
            theme = if (store.string("theme") == "dark") ThemeChoice.dark else ThemeChoice.light
            playerStyle = PlayerStyle.entries.firstOrNull { it.name == store.string("playerStyle") } ?: PlayerStyle.classic
            feeds.clear()
            feeds += (parseJson(store.string("feeds") ?: "[]").arr() ?: JsonArray(emptyList())).mapNotNull { RssFeed.fromJson(it) }
            changed()
        } catch (_: Exception) {
        }
    }
}
