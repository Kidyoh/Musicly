package com.musicly.musicly.player

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.musicly.musicly.data.AppJson
import com.musicly.musicly.data.Store
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.data.arr
import com.musicly.musicly.data.bool
import com.musicly.musicly.data.get
import com.musicly.musicly.data.jsonOf
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ln

enum class EqPreset(val label: String, val curve: List<Double>) {
    flat("Flat", listOf(0.0, 0.0, 0.0, 0.0, 0.0)),
    bassBoost("Bass boost", listOf(7.0, 4.5, 0.0, 0.0, 0.0)),
    vocal("Vocal", listOf(-2.0, 0.0, 4.0, 3.0, 0.0)),
    treble("Treble", listOf(0.0, 0.0, 0.0, 4.0, 7.0)),
    electronic("Electronic", listOf(5.0, 2.0, -1.0, 2.0, 5.0)),
    rock("Rock", listOf(4.0, 2.0, -2.0, 2.0, 4.0)),
    acoustic("Acoustic", listOf(3.0, 1.0, 1.0, 2.0, 3.0)),
    custom("Custom", listOf(0.0, 0.0, 0.0, 0.0, 0.0)),
}

enum class LoopMode { off, all, one }

/** One equalizer band: centre frequency and gain range in dB. */
data class EqBand(val centerHz: Double, val minDb: Double, val maxDb: Double)

/**
 * Everything about what is playing: queue, shuffle/repeat, speed, sleep
 * timer, equalizer, volume fade and recovery from failed songs.
 */
@UnstableApi
class PlayerController(private val context: android.content.Context, private val engine: AudioEngine, private val store: Store) {
    val player: ExoPlayer = engine.player
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    /** The downloaded phone copy of a channel song, if any. */
    var localCopy: ((Track) -> Track?)? = null

    /** Called whenever a new song starts, for history and recommendations. */
    var onTrackStarted: ((Track) -> Unit)? = null

    /** Asks for the notification permission the first time something plays. */
    var requestNotifications: (() -> Unit)? = null

    var playError by mutableStateOf<String?>(null)

    /** "Artist - Song" announced by a live radio stream, when it sends one. */
    var nowOnAir by mutableStateOf<String?>(null)

    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var loopMode by mutableStateOf(LoopMode.off)
        private set
    @set:JvmName("assignSpeed")
    var speed by mutableStateOf(1.0f)
        private set

    // Sound
    var eqEnabled by mutableStateOf(false)
        private set
    var eqPreset by mutableStateOf(EqPreset.flat)
        private set
    var customGains by mutableStateOf(listOf(0.0, 0.0, 0.0, 0.0, 0.0))
        private set
    var loudnessOn by mutableStateOf(false)
        private set
    var loudnessGain by mutableStateOf(4.0)
        private set
    var smoothFade by mutableStateOf(true)
        private set
    var fadeSeconds by mutableStateOf(3.0)
        private set

    // Sleep timer
    private var sleepJob: Job? = null
    var sleepEndsAt by mutableStateOf<Long?>(null)
        private set
    var sleepAtTrackEnd by mutableStateOf(false)
        private set
    private var sleepFading = false
    val sleepActive get() = sleepEndsAt != null || sleepAtTrackEnd

    val current: Track? get() = queue.getOrNull(currentIndex)
    val positionMs: Long get() = player.currentPosition
    val effectsSupported = true

    private var equalizer: Equalizer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var retries = 0
    private var askedNotifications = false

    init {
        restoreSettings()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = player.currentMediaItemIndex
                val changed = i != currentIndex
                if (changed && sleepAtTrackEnd) {
                    sleepAtTrackEnd = false
                    player.pause()
                }
                currentIndex = i
                if (changed) {
                    nowOnAir = null
                    retries = 0
                }
                if (changed) current?.let { onTrackStarted?.invoke(it) }
                prefetchNext()
                syncDuration()
            }

            override fun onIsPlayingChanged(playing: Boolean) = refreshState()
            override fun onPlaybackStateChanged(state: Int) {
                refreshState()
                syncDuration()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshState()
            override fun onAudioSessionIdChanged(audioSessionId: Int) = rebuildEffects(audioSessionId)
            override fun onPlayerError(error: PlaybackException) {
                recover()
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata[i]
                    if (e is IcyInfo) {
                        val title = e.title?.trim()
                        if (!title.isNullOrEmpty() && title != nowOnAir) nowOnAir = title
                    }
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = syncDuration()
        })
        handler.post(object : Runnable {
            override fun run() {
                fade()
                handler.postDelayed(this, 200)
            }
        })
        rebuildEffects(player.audioSessionId)
    }

    private fun refreshState() {
        isPlaying = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        buffering = player.playbackState == Player.STATE_BUFFERING
    }

    private fun syncDuration() {
        val d = player.duration
        durationMs = if (d == androidx.media3.common.C.TIME_UNSET || current?.isRadio == true) 0 else d
    }

    // ---- Playback ----------------------------------------------------------------

    private fun prefetchNext() {
        val next = queue.getOrNull(player.nextMediaItemIndex.takeIf { it >= 0 } ?: return) ?: return
        if (next.source == TrackSource.telegram && localCopy?.invoke(next) == null) {
            scope.launch(Dispatchers.IO) { TelegramLinks.warm(next.uri!!) }
        }
    }

    private fun item(t: Track): MediaItem {
        val copy = if (t.source == TrackSource.telegram) localCopy?.invoke(t) else null
        return if (copy != null) AudioEngine.mediaItem(copy.copy(title = t.title, artist = t.artist, album = t.album, artworkUrl = t.artworkUrl), copy)
        else AudioEngine.mediaItem(t)
    }

    fun playQueue(tracks: List<Track>, index: Int, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        if (!askedNotifications) {
            askedNotifications = true
            requestNotifications?.invoke()
        }
        queue = tracks.toList()
        currentIndex = index
        retries = 0
        playError = null
        nowOnAir = null
        onTrackStarted?.invoke(queue[index])
        try {
            shuffle = shuffled
            player.shuffleModeEnabled = shuffled
            player.setMediaItems(queue.map(::item), index, 0)
            if (shuffled) {
                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(queue.size, System.nanoTime()))
                val first = player.currentTimeline.getFirstWindowIndex(true)
                player.seekTo(first, 0)
                currentIndex = first
            }
            player.prepare()
            player.play()
            startService()
        } catch (_: Exception) {
            playError = "Could not play this song"
        }
    }

    fun playShuffled(tracks: List<Track>) = playQueue(tracks, 0, shuffled = true)

    /** Plays one Jam song from [positionMs], starting it only if [playing]. Returns an error message, or null. */
    suspend fun playJam(t: Track, positionMs: Long, playing: Boolean): String? {
        queue = listOf(t)
        currentIndex = 0
        retries = 0
        playError = null
        nowOnAir = null
        return try {
            shuffle = false
            player.shuffleModeEnabled = false
            player.setMediaItems(listOf(item(t)), 0, positionMs)
            player.prepare()
            // Wait for the song to load (or fail) so errors reach the Jam screen.
            kotlinx.coroutines.withTimeout(25_000) {
                while (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE) {
                    player.playerError?.let { throw it }
                    delay(100)
                }
            }
            player.playWhenReady = playing
            startService()
            null
        } catch (e: Exception) {
            playError = "Could not play this song"
            e.message ?: e.toString()
        }
    }

    private var controller: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    /**
     * Connects to the playback service. That starts it, so the notification,
     * lock-screen player and background playback work.
     */
    private fun startService() {
        if (controller != null) return
        try {
            val token = androidx.media3.session.SessionToken(
                context, android.content.ComponentName(context, PlaybackService::class.java),
            )
            controller = androidx.media3.session.MediaController.Builder(context, token).buildAsync()
        } catch (_: Exception) {
        }
    }

    fun togglePlay() {
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun next() = player.seekToNextMediaItem()

    fun previous() {
        if (player.currentPosition > 3000) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun seek(ms: Long) = player.seekTo(ms)
    fun jumpTo(index: Int) = player.seekTo(index, 0)

    fun toggleShuffle() {
        shuffle = !shuffle
        if (shuffle) player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(queue.size, System.nanoTime()))
        player.shuffleModeEnabled = shuffle
    }

    fun cycleLoop() {
        loopMode = when (loopMode) {
            LoopMode.off -> LoopMode.all
            LoopMode.all -> LoopMode.one
            LoopMode.one -> LoopMode.off
        }
        player.repeatMode = when (loopMode) {
            LoopMode.off -> Player.REPEAT_MODE_OFF
            LoopMode.all -> Player.REPEAT_MODE_ALL
            LoopMode.one -> Player.REPEAT_MODE_ONE
        }
    }

    fun setSpeed(s: Float) {
        speed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (queue.isEmpty()) return playQueue(tracks, 0)
        queue = queue + tracks
        player.addMediaItems(tracks.map(::item))
    }

    fun playNext(t: Track) {
        if (queue.isEmpty()) return playQueue(listOf(t), 0)
        val at = currentIndex + 1
        queue = queue.toMutableList().apply { add(at, t) }
        player.addMediaItem(at, item(t))
    }

    fun removeFromQueue(i: Int) {
        if (i == currentIndex) return
        queue = queue.toMutableList().apply { removeAt(i) }
        if (i < currentIndex) currentIndex--
        player.removeMediaItem(i)
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from == to) return
        val l = queue.toMutableList()
        l.add(to, l.removeAt(from))
        queue = l
        currentIndex = when {
            from == currentIndex -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        player.moveMediaItem(from, to)
    }

    /** Stops playback and clears the queue (used when leaving a Jam). */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
        currentIndex = 0
        refreshState()
    }

    // ---- Recovery ----------------------------------------------------------------

    /**
     * A song that fails to load (expired link, network blip) is retried with a
     * fresh link; after two failures playback moves on instead of stopping.
     */
    private fun recover() {
        val t = current ?: return
        if (t.source == TrackSource.telegram) TelegramLinks.forget(t.uri!!)
        retries++
        scope.launch {
            delay(600L * retries)
            if (retries <= 2) {
                player.seekTo(player.currentMediaItemIndex, player.currentPosition)
                player.prepare()
                player.play()
            } else if (player.hasNextMediaItem()) {
                retries = 0
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                playError = "Could not play this song"
            }
        }
    }

    // ---- Fade + sleep timer --------------------------------------------------------

    /** Fades each song in at the start and out at the end. */
    private fun fade() {
        if (sleepFading) return
        var v = 1.0
        if (smoothFade && player.isPlaying) {
            val total = player.duration
            val f = fadeSeconds * 1000
            val p = player.currentPosition.toDouble()
            if (p < f * 0.6) v = (p / (f * 0.6)).coerceIn(0.15, 1.0)
            if (total != androidx.media3.common.C.TIME_UNSET && total > f * 3 && current?.isRadio != true) {
                val left = total - p
                if (left < f) v = (left / f).coerceIn(0.0, 1.0)
            }
        }
        if (kotlin.math.abs(player.volume - v) > 0.03 || (v == 1.0 && player.volume != 1f)) player.volume = v.toFloat()
    }

    fun setSleepTimer(durationMs: Long?, atTrackEnd: Boolean = false) {
        sleepJob?.cancel()
        sleepEndsAt = null
        sleepAtTrackEnd = atTrackEnd
        if (durationMs != null) {
            sleepEndsAt = System.currentTimeMillis() + durationMs
            sleepJob = scope.launch {
                delay(durationMs)
                sleepEndsAt = null
                sleepFading = true
                try {
                    var v = player.volume
                    while (v > 0) {
                        player.volume = v.coerceIn(0f, 1f)
                        delay(300)
                        v -= 0.08f
                    }
                    player.pause()
                } finally {
                    player.volume = 1f
                    sleepFading = false
                }
            }
        }
    }

    // ---- Sound effects ---------------------------------------------------------------

    private fun rebuildEffects(session: Int) {
        try {
            equalizer?.release()
            enhancer?.release()
            equalizer = null
            enhancer = null
            if (session == 0) return
            equalizer = Equalizer(0, session)
            enhancer = LoudnessEnhancer(session)
            applyEffects()
        } catch (_: Exception) {
        }
    }

    /** Device bands. Empty if the phone has no equalizer. */
    fun eqBands(): List<EqBand> {
        val e = equalizer ?: return emptyList()
        return try {
            val range = e.bandLevelRange
            (0 until e.numberOfBands).map { b ->
                EqBand(e.getCenterFreq(b.toShort()) / 1000.0, range[0] / 100.0, range[1] / 100.0)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val activeCurve: List<Double> get() = if (eqPreset == EqPreset.custom) customGains else eqPreset.curve

    private fun applyEffects() {
        try {
            equalizer?.let { e ->
                e.enabled = eqEnabled
                if (eqEnabled) {
                    val curve = activeCurve
                    val range = e.bandLevelRange
                    for (b in 0 until e.numberOfBands) {
                        val hz = e.getCenterFreq(b.toShort()) / 1000.0
                        val g = (curveAt(curve, hz) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                        e.setBandLevel(b.toShort(), g.toShort())
                    }
                }
            }
            enhancer?.let {
                it.enabled = loudnessOn
                if (loudnessOn) it.setTargetGain((loudnessGain * 100).toInt())
            }
        } catch (_: Exception) {
        }
    }

    fun setEq(enabled: Boolean? = null, preset: EqPreset? = null, custom: List<Double>? = null) {
        enabled?.let { eqEnabled = it }
        preset?.let { eqPreset = it }
        if (custom != null) {
            customGains = custom
            eqPreset = EqPreset.custom
        }
        applyEffects()
        saveSettings()
    }

    fun setLoudness(on: Boolean? = null, gain: Double? = null) {
        on?.let { loudnessOn = it }
        gain?.let { loudnessGain = it }
        applyEffects()
        saveSettings()
    }

    fun setFade(on: Boolean? = null, seconds: Double? = null) {
        on?.let { smoothFade = it }
        seconds?.let { fadeSeconds = it }
        saveSettings()
    }

    private fun saveSettings() {
        store.put(
            "sound",
            jsonOf(
                "eqEnabled" to eqEnabled, "eqPreset" to eqPreset.name, "customGains" to customGains,
                "loudnessOn" to loudnessOn, "loudnessGain" to loudnessGain,
                "smoothFade" to smoothFade, "fadeSeconds" to fadeSeconds,
            ).toString(),
        )
    }

    /** Re-reads sound settings, e.g. after a library restore. */
    fun restoreSettings() {
        try {
            val j = parseJson(store.string("sound") ?: "{}").obj() ?: return
            eqEnabled = j["eqEnabled"].bool() ?: false
            eqPreset = EqPreset.entries.firstOrNull { it.name == j["eqPreset"].str() } ?: EqPreset.flat
            customGains = j["customGains"].arr()?.map { (it as JsonPrimitive).content.toDouble() }
                ?: listOf(0.0, 0.0, 0.0, 0.0, 0.0)
            loudnessOn = j["loudnessOn"].bool() ?: false
            loudnessGain = (j["loudnessGain"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 4.0
            smoothFade = j["smoothFade"].bool() ?: true
            fadeSeconds = (j["fadeSeconds"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 3.0
            applyEffects()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Interpolates a 5-point curve (log-spaced) onto the device's bands. */
        fun curveAt(curve: List<Double>, hz: Double): Double {
            val anchors = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
            if (hz <= anchors.first()) return curve.first()
            if (hz >= anchors.last()) return curve.last()
            for (i in 0 until anchors.size - 1) {
                if (hz <= anchors[i + 1]) {
                    val a = ln(anchors[i]); val b = ln(anchors[i + 1]); val x = ln(hz)
                    return curve[i] + (curve[i + 1] - curve[i]) * ((x - a) / (b - a))
                }
            }
            return 0.0
        }
    }
}
