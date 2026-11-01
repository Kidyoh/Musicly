package com.musicly.musicly.jam

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/** Someone in the Jam other than the host. */
class JamPerson(val id: String, val name: String) {
    /** Last time we heard from them, ms since epoch (online Jams drop people who go quiet). */
    @Volatile
    var seen: Long = System.currentTimeMillis()
}

/**
 * A song a friend added from their own phone: a file saved on the host
 * (same room) or a download link (online).
 */
class JamUpload(
    /** File path or https link. */
    val source: String,
    val title: String,
    val artist: String,
    /** Cover: file path or https link. */
    val art: String? = null,
    val durationMs: Long? = null,
)

/** What the host's side needs from the app. */
class JamHostCallbacks(
    /** The Jam right now (people are filled in by the host). */
    val state: () -> JamState,
    /** A friend picked one of the host's songs (by track id). */
    val onAdd: suspend (trackId: String, by: JamPerson, next: Boolean) -> Unit,
    /** A friend sent a song from their phone. */
    val onUpload: suspend (song: JamUpload, by: JamPerson, next: Boolean) -> Unit,
    /** toggle, next, previous or seek (with ms); only called when friends may control. */
    val onControl: (action: String, ms: Long?, by: JamPerson) -> Unit,
    /** The host's songs matching a search (all when empty). */
    val search: (query: String) -> List<JamTrack>,
    /** Cover image bytes for a track id. */
    val art: suspend (trackId: String) -> ByteArray?,
)

/** Hosting a Jam, in the same room or online. */
interface JamHosting {
    val guests: List<JamPerson>
    var guestsControl: Boolean
    var onPeopleChanged: (() -> Unit)?

    /** Tells everyone what's going on now. */
    fun pushState()
    fun remove(guestId: String)
    suspend fun stop(deleteUploads: Boolean = true)
}

/** Being in someone else's Jam, in the same room or online. */
interface JamSession {
    val jamName: String
    val hostName: String
    val state: JamState

    /** Every update from the host (no replay; [state] holds the latest). */
    val states: SharedFlow<JamState>

    /** Completes with why the Jam ended for us: "ended", "removed", "lost" or "left". */
    val closed: Deferred<String>

    /** Where the host's song is right now, in ms. */
    val positionMs: Long

    suspend fun search(query: String): List<JamTrack>
    fun add(trackId: String, next: Boolean = false)
    fun control(action: String, ms: Long? = null)

    /** Cover image link for a song, if there is one. */
    fun artFor(t: JamTrack): String?

    /** Sends one of this phone's songs and adds it to the Jam. */
    suspend fun sendSong(
        audio: File,
        ext: String,
        title: String,
        artist: String,
        art: ByteArray? = null,
        durationMs: Long? = null,
        next: Boolean = false,
        onProgress: ((Double) -> Unit)? = null,
    )

    suspend fun leave()
}

class JamError(private val text: String) : Exception(text) {
    override val message: String get() = text
    override fun toString(): String = text
}
