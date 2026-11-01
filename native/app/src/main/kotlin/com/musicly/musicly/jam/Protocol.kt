package com.musicly.musicly.jam

import com.musicly.musicly.data.arr
import com.musicly.musicly.data.bool
import com.musicly.musicly.data.get
import com.musicly.musicly.data.jsonOf
import com.musicly.musicly.data.long
import com.musicly.musicly.data.obj
import com.musicly.musicly.data.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Locale

/*
 * What Musicly phones say to each other in a Jam on the same Wi-Fi.
 *
 * The host runs a small web server; guests talk to it over one WebSocket
 * and find it through a broadcast "beacon" on [BEACON_PORT].
 */

const val BEACON_PORT = 47474
const val BEACON_TAG = "musicly-jam"

/** Like Dart's `'$x'` on a decoded JSON value. */
internal fun JsonElement?.text(): String = when (this) {
    null, JsonNull -> "null"
    is JsonPrimitive -> content
    else -> toString()
}

/** A song as guests see it: enough to show it, plus who added it. */
data class JamTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
    /** Name of the person who added it to the Jam, if not the host. */
    val by: String? = null,
    val hasArt: Boolean = false,
    /** Online Jams: where the song and its cover can be downloaded. */
    val url: String? = null,
    val artUrl: String? = null,
) {
    fun toJson(): JsonObject = jsonOf(
        "id" to id,
        "title" to title,
        "artist" to artist,
        "dur" to durationMs,
        "by" to by,
        "art" to (if (hasArt) true else null),
        "url" to url,
        "artUrl" to artUrl,
    )

    companion object {
        fun fromJson(j: JsonObject): JamTrack = JamTrack(
            id = j["id"].str() ?: throw IllegalArgumentException("track without id"),
            title = j["title"].str() ?: "Unknown",
            artist = j["artist"].str() ?: "Unknown artist",
            durationMs = j["dur"].long(),
            by = j["by"].str(),
            hasArt = j["art"].bool() == true,
            url = j["url"].str(),
            artUrl = j["artUrl"].str(),
        )
    }
}

/** The Jam as the host last described it. */
data class JamState(
    val now: JamTrack? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** Host clock (ms since epoch) when [positionMs] was read. */
    val at: Long = 0,
    val queue: List<JamTrack> = emptyList(),
    /** Everyone in the Jam, host first. */
    val people: List<String> = emptyList(),
    val guestsControl: Boolean = false,
    /** The latest thing that happened, e.g. "Abel added Blinding Lights". */
    val event: String? = null,
    /** Online Jams: link to the host's song list, for friends to browse. */
    val library: String? = null,
) {
    fun toJson(): JsonObject = jsonOf(
        "t" to "state",
        "now" to now?.toJson(),
        "playing" to playing,
        "pos" to positionMs,
        "at" to at,
        "queue" to JsonArray(queue.map { it.toJson() }),
        "people" to JsonArray(people.map { JsonPrimitive(it) }),
        "control" to guestsControl,
        "event" to event,
        "lib" to library,
    )

    companion object {
        fun fromJson(j: JsonObject): JamState = JamState(
            now = j["now"].obj()?.let { JamTrack.fromJson(it) },
            playing = j["playing"].bool() == true,
            positionMs = j["pos"].long() ?: 0,
            at = j["at"].long() ?: 0,
            queue = j["queue"].arr()?.map { JamTrack.fromJson(it as JsonObject) } ?: emptyList(),
            people = j["people"].arr()?.map { it.text() } ?: emptyList(),
            guestsControl = j["control"].bool() == true,
            event = j["event"].str(),
            library = j["lib"].str(),
        )
    }
}

private const val ABC = "23456789ABCDEFGHJKMNPQRSTUVWXYZ" // 31 symbols
private val SEPARATORS = Regex("[\\s-]")

private fun dashed(s: String) = "${s.substring(0, 4)}-${s.substring(4, 8)}-${s.substring(8)}"

/**
 * Join codes: the host's IPv4 address and port packed into 10 letters and
 * digits, e.g. "B8M2-K4Q9-XA". Look-alike characters (0/O, 1/I/L) aren't used.
 */
object JamCode {
    private const val LEN = 10

    fun encode(ip: String, port: Int): String {
        val parts = ip.split('.').map { it.toInt() }
        require(parts.size == 4) { "IPv4 only" }
        var n = BigInteger.ZERO
        for (p in parts) n = n.shiftLeft(8).or(BigInteger.valueOf(p.toLong()))
        n = n.shiftLeft(16).or(BigInteger.valueOf(port.toLong()))
        val base = BigInteger.valueOf(ABC.length.toLong())
        val out = StringBuilder()
        repeat(LEN) {
            val qr = n.divideAndRemainder(base)
            out.append(ABC[qr[1].toInt()])
            n = qr[0]
        }
        return dashed(out.reverse().toString())
    }

    /** The address in a code (ip to port), or null if it isn't a valid code. */
    fun decode(code: String): Pair<String, Int>? {
        val s = code.uppercase(Locale.ROOT).replace(SEPARATORS, "")
        if (s.length != LEN) return null
        var n = BigInteger.ZERO
        val base = BigInteger.valueOf(ABC.length.toLong())
        for (ch in s) {
            val v = ABC.indexOf(ch)
            if (v < 0) return null
            n = n.multiply(base).add(BigInteger.valueOf(v.toLong()))
        }
        val port = n.and(BigInteger.valueOf(0xFFFF)).toInt()
        n = n.shiftRight(16)
        if (n >= BigInteger.ONE.shiftLeft(32) || port == 0) return null
        val ip = (24 downTo 0 step 8).joinToString(".") { shift ->
            n.shiftRight(shift).and(BigInteger.valueOf(0xFF)).toInt().toString()
        }
        return ip to port
    }
}

/**
 * Codes for online Jams: 12 random letters and digits, e.g.
 * "K7QM-2XRB-9TFA". They name the Jam's channel on the relay, so they're
 * long enough that nobody stumbles onto it.
 */
object OnlineJamCode {
    private val random = SecureRandom()

    fun create(): String =
        dashed(String(CharArray(12) { ABC[random.nextInt(ABC.length)] }))

    /** The code in its standard form, or null if it isn't an online code. */
    fun normalise(code: String): String? {
        val s = code.uppercase(Locale.ROOT).replace(SEPARATORS, "")
        if (s.length != 12 || s.any { it !in ABC }) return null
        return dashed(s)
    }

    /** The relay channel for a code. */
    fun topic(code: String): String = "musicly-jam-" + code.replace("-", "").lowercase(Locale.ROOT)
}
