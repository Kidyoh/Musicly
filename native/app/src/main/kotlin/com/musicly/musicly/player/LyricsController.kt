package com.musicly.musicly.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.util.UnstableApi
import com.musicly.musicly.data.Store
import com.musicly.musicly.data.Track
import com.musicly.musicly.net.LyricsApi
import com.musicly.musicly.net.Lyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class LyricsStatus { idle, loading, found, notFound, error }

/**
 * Fetches and caches lyrics for whatever is playing. Users can pick a
 * different match and nudge the timing; both choices are remembered per track.
 */
@UnstableApi
class LyricsController(private val player: PlayerController, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api = LyricsApi()
    private val cache = mutableMapOf<String, Lyrics?>()
    private var trackId: String? = null

    var status by mutableStateOf(LyricsStatus.idle)
        private set
    var lyrics by mutableStateOf<Lyrics?>(null)
        private set

    /** Timing nudge in milliseconds. */
    var offset by mutableStateOf(0L)
        private set

    init {
        scope.launch {
            snapshotFlow { player.current?.id }.distinctUntilChanged().collect {
                val t = player.current ?: return@collect
                if (t.id != trackId) {
                    trackId = t.id
                    load(t)
                }
            }
        }
    }

    private fun load(t: Track) {
        scope.launch {
            if (t.isRadio) {
                lyrics = null
                status = LyricsStatus.idle
                return@launch
            }
            offset = store.long("lyr_off:${t.id}") ?: 0
            if (cache.containsKey(t.id)) {
                lyrics = cache[t.id]
                status = if (lyrics == null) LyricsStatus.notFound else LyricsStatus.found
                return@launch
            }
            lyrics = null
            status = LyricsStatus.loading
            try {
                val chosen = store.long("lyr_id:${t.id}")
                val artist = if (t.artist == "Unknown artist") "" else t.artist
                val result = if (chosen != null) api.byId(chosen) else api.find(t.title, artist, t.duration)
                if (trackId != t.id) return@launch
                cache[t.id] = result
                lyrics = result
                status = if (result == null) LyricsStatus.notFound else LyricsStatus.found
            } catch (_: Exception) {
                if (trackId != t.id) return@launch
                status = LyricsStatus.error
            }
        }
    }

    fun retry() {
        val t = player.current ?: return
        cache.remove(t.id)
        load(t)
    }

    /** Use a lyrics result the user picked from search. */
    fun choose(l: Lyrics) {
        val t = player.current ?: return
        cache[t.id] = l
        lyrics = l
        status = LyricsStatus.found
        store.put("lyr_id:${t.id}", l.id)
    }

    fun nudge(ms: Long) {
        offset += ms
        val t = player.current ?: return
        store.put("lyr_off:${t.id}", offset)
    }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun lineAt(positionMs: Long): Int {
        val lines = lyrics?.synced ?: return -1
        val p = positionMs + offset
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].time <= p) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
