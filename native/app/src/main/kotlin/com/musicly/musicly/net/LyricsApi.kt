package com.musicly.musicly.net

import com.musicly.musicly.data.arr
import com.musicly.musicly.data.get
import com.musicly.musicly.data.long
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import com.musicly.musicly.data.bool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import kotlin.math.abs

/** [time] is in milliseconds. */
data class LyricLine(val time: Long, val text: String)

/** [duration] is in milliseconds. */
data class Lyrics(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val duration: Long? = null,
    val synced: List<LyricLine> = emptyList(),
    val plain: String? = null,
    val instrumental: Boolean = false,
) {
    val isSynced get() = synced.isNotEmpty()

    companion object {
        private val tag = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")

        /** Parses LRC text. Lines may carry several timestamps. */
        fun parseLrc(lrc: String): List<LyricLine> {
            val out = ArrayList<LyricLine>()
            for (raw in lrc.lines()) {
                val tags = tag.findAll(raw).toList()
                if (tags.isEmpty()) continue
                val text = raw.substring(tags.last().range.last + 1).trim()
                for (t in tags) {
                    val ms = Math.round(t.groupValues[1].toLong() * 60000.0 + t.groupValues[2].toDouble() * 1000)
                    out += LyricLine(ms, text)
                }
            }
            return out.sortedBy { it.time }
        }

        fun fromJson(e: JsonElement?): Lyrics? {
            val j = e.obj() ?: return null
            val dur = (j["duration"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            return Lyrics(
                id = j["id"].long() ?: return null,
                trackName = j["trackName"].str() ?: "",
                artistName = j["artistName"].str() ?: "",
                duration = dur?.let { Math.round(it * 1000) },
                synced = j["syncedLyrics"].str()?.let { parseLrc(it) } ?: emptyList(),
                plain = j["plainLyrics"].str(),
                instrumental = j["instrumental"].bool() == true,
            )
        }

        private fun secs(ms: Long) = ms / 1000

        /**
         * Picks the best plausible result: prefers synced lyrics with a close
         * duration. Only trusts a match whose title and artist or length line
         * up; otherwise null, so the user can pick from search instead.
         */
        fun pick(results: List<Lyrics>, title: String, artist: String, duration: Long?): Lyrics? {
            fun score(l: Lyrics): Int {
                var s = 0
                if (l.isSynced) s += 10
                if (duration != null && l.duration != null) {
                    val diff = abs(secs(l.duration) - secs(duration))
                    if (diff <= 3) s += 6
                    if (diff > 20) s -= 6
                }
                if (l.artistName.lowercase() == artist.lowercase()) s += 4
                return s
            }

            fun plausible(l: Lyrics): Boolean {
                val a = artist.lowercase()
                val la = l.artistName.lowercase()
                val artistOk = a.isNotEmpty() && (la.contains(a) || a.contains(la))
                val durOk = duration != null && duration > 0 && l.duration != null &&
                    abs(secs(l.duration) - secs(duration)) <= 4
                val titleOk = l.trackName.lowercase().contains(title.lowercase()) ||
                    title.lowercase().contains(l.trackName.lowercase())
                return titleOk && (artistOk || durOk || (a.isEmpty() && duration == null))
            }
            return results.filter { plausible(it) }.sortedByDescending { score(it) }.firstOrNull()
        }
    }
}

/** Free synced-lyrics database (https://lrclib.net). */
class LyricsApi {
    private val headers = mapOf("User-Agent" to "Musicly/1.0 (https://github.com/kidyoh/musicly)")

    suspend fun search(query: String): List<Lyrics> {
        val url = "$HOST/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val text = Http.get(url, headers, 15).use {
            if (it.code != 200) throw Exception("Lyrics ${it.code}")
            it.body.string()
        }
        return parseJson(text).arr().orEmpty().mapNotNull { Lyrics.fromJson(it) }
    }

    suspend fun byId(id: Long): Lyrics? {
        val text = Http.get("$HOST/get/$id", headers, 15).use {
            if (it.code != 200) return null
            it.body.string()
        }
        return Lyrics.fromJson(parseJson(text))
    }

    /** Best guess for a track ([duration] in ms): prefers synced lyrics with a close duration. */
    suspend fun find(title: String, artist: String, duration: Long?): Lyrics? {
        val q = if (artist.isEmpty()) title else "$title $artist"
        var results = search(q)
        if (results.isEmpty() && artist.isNotEmpty()) results = search(title)
        if (results.isEmpty()) return null
        return Lyrics.pick(results, title, artist, duration)
    }

    private companion object {
        const val HOST = "https://lrclib.net/api"
    }
}
