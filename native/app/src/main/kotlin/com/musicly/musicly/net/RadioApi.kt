package com.musicly.musicly.net

import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.data.arr
import com.musicly.musicly.data.get
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import com.musicly.musicly.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Radio Browser: a free, community directory of 50,000+ live internet
 * radio stations (https://www.radio-browser.info). No key needed.
 */
class RadioApi {
    @Volatile
    private var server = 0

    private suspend fun get(path: String, q: Map<String, String>): List<JsonObject> {
        // Only secure streams: phones block plain-http audio by default.
        val query = linkedMapOf("hidebroken" to "true", "is_https" to "true") + q
        var lastError: Throwable? = null
        for (i in servers.indices) {
            val base = servers[(server + i) % servers.size]
            try {
                val url = "$base$path".toHttpUrl().newBuilder().apply {
                    query.forEach { (k, v) -> addQueryParameter(k, v) }
                }.build().toString()
                val body = parseJson(Http.getText(url, timeoutSeconds = 12))
                val list = body.arr() ?: throw IllegalStateException("Unexpected answer")
                server = (server + i) % servers.size
                return list.mapNotNull { it.obj() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw Exception("Radio directory unreachable: $lastError")
    }

    private fun stations(l: List<JsonObject>): List<Track> {
        val seen = HashSet<String>()
        return l
            .filter { (it["url_resolved"].str() ?: "").startsWith("https://") }
            .filter { seen.add((it["name"].str() ?: "").trim().lowercase()) }
            .map { m ->
                val tags = (m["tags"].str() ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .take(2).joinToString(" · ")
                val country = (m["country"].str() ?: "").trim()
                val fav = (m["favicon"].str() ?: "").trim()
                val home = m["homepage"].str()
                Track(
                    id = "rb:${m["stationuuid"].str()}",
                    title = (m["name"].str() ?: "Radio").trim(),
                    artist = listOfNotNull(tags.ifEmpty { null }, country.ifEmpty { null }).joinToString(" • "),
                    source = TrackSource.radio,
                    uri = m["url_resolved"].str()!!,
                    artworkUrl = if (fav.startsWith("https://")) fav else null,
                    link = if (home != null && home.startsWith("http")) home else null,
                )
            }
    }

    private fun ranked(extra: Map<String, String>, limit: Int) =
        extra + mapOf("order" to "clickcount", "reverse" to "true", "limit" to "$limit")

    suspend fun top(limit: Int = 40): List<Track> = stations(get("/json/stations/search", ranked(emptyMap(), limit)))

    suspend fun byTag(tag: String, limit: Int = 40): List<Track> =
        stations(get("/json/stations/search", ranked(mapOf("tag" to tag), limit)))

    suspend fun byCountry(code: String, limit: Int = 40): List<Track> =
        stations(get("/json/stations/search", ranked(mapOf("countrycode" to code), limit)))

    suspend fun search(name: String, limit: Int = 20): List<Track> =
        stations(get("/json/stations/search", ranked(mapOf("name" to name), limit)))

    /** Tells the directory a station was played (helps its popularity ranking). */
    suspend fun countClick(trackId: String) {
        try {
            Http.getText("${servers[server]}/json/url/${trackId.substring(3)}", timeoutSeconds = 12)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        private val servers = listOf(
            "https://de1.api.radio-browser.info",
            "https://de2.api.radio-browser.info",
            "https://fi1.api.radio-browser.info",
        )

        /** (tag, label) pairs for the genre chips. */
        val tags = listOf(
            "pop" to "Pop", "hiphop" to "Hip hop", "afrobeats" to "Afrobeats", "rnb" to "R&B",
            "dance" to "Dance", "rock" to "Rock", "jazz" to "Jazz", "lofi" to "Lo-fi",
            "reggae" to "Reggae", "gospel" to "Gospel", "classical" to "Classical",
            "country" to "Country", "80s" to "80s", "news" to "News",
        )
    }
}
