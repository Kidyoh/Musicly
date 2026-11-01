package com.musicly.musicly.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * radio: live stations · telegram: your channel · device/file: music on the
 * phone · feed: songs streamed from an RSS feed.
 */
enum class TrackSource { radio, telegram, device, file, feed }

data class Track(
    /** `rb:` radio, `tg:` Telegram, `rss:` RSS feed, `ms:` phone library, `file:` picked files. */
    val id: String,
    val title: String,
    val artist: String,
    val source: TrackSource,
    /** File path or content:// URI for phone songs, the stream link for radio and feeds, the Telegram file id for channel songs. */
    val uri: String? = null,
    val artworkUrl: String? = null,
    val duration: Long? = null, // milliseconds
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    /** MediaStore id, used to load embedded album art on Android. */
    val mediaId: Long? = null,
    /** Web page (radio station homepage). */
    val link: String? = null,
    /** File type, e.g. audio/mpeg (Telegram songs; used when saving them). */
    val mime: String? = null,
) {
    val isLocal get() = source == TrackSource.device || source == TrackSource.file
    val isRadio get() = source == TrackSource.radio

    /** Songs that only exist during a Jam are never saved into the library. */
    val isJam get() = id.startsWith("jam:") || id.startsWith("jamview:")
    val isPersistable get() = !isJam

    fun toJson(): JsonObject = jsonOf(
        "id" to id, "title" to title, "artist" to artist, "source" to source.name,
        "uri" to uri, "artworkUrl" to artworkUrl, "durationMs" to duration,
        "artistId" to artistId, "album" to album, "albumId" to albumId,
        "mediaId" to mediaId, "link" to link, "mime" to mime,
    )

    companion object {
        /** Null for songs from sources Musicly no longer has (old 30s previews). */
        fun fromJson(e: JsonElement?): Track? {
            val j = e.obj() ?: return null
            val source = TrackSource.entries.firstOrNull { it.name == j["source"].str() } ?: return null
            return try {
                Track(
                    id = j["id"].str()!!, title = j["title"].str()!!, artist = j["artist"].str()!!,
                    source = source, uri = j["uri"].str(), artworkUrl = j["artworkUrl"].str(),
                    duration = j["durationMs"].long(), artistId = j["artistId"].str(),
                    album = j["album"].str(), albumId = j["albumId"].str(),
                    mediaId = j["mediaId"].long(), link = j["link"].str(), mime = j["mime"].str(),
                )
            } catch (_: Exception) {
                null
            }
        }

        fun listFromJson(e: JsonElement?): List<Track> = e.arr()?.mapNotNull { fromJson(it) } ?: emptyList()
    }
}

fun List<Track>.toJson() = kotlinx.serialization.json.JsonArray(filter { it.isPersistable }.map { it.toJson() })

/** A podcast or music RSS feed the user added. */
data class RssFeed(val url: String, val title: String, val imageUrl: String? = null) {
    fun toJson() = jsonOf("url" to url, "title" to title, "imageUrl" to imageUrl)

    companion object {
        fun fromJson(e: JsonElement?): RssFeed? {
            val j = e.obj() ?: return null
            return RssFeed(j["url"].str() ?: return null, j["title"].str() ?: "Feed", j["imageUrl"].str())
        }
    }
}

/** A playlist the user made. Stored on the device (and in backups). */
data class UserPlaylist(
    val id: String,
    var name: String,
    val tracks: MutableList<Track> = mutableListOf(),
    val created: String = java.time.Instant.now().toString(),
) {
    fun toJson() = jsonOf("id" to id, "name" to name, "created" to created, "tracks" to tracks.toJson())

    companion object {
        fun fromJson(e: JsonElement?): UserPlaylist? {
            val j = e.obj() ?: return null
            return UserPlaylist(
                id = j["id"].str() ?: return null, name = j["name"].str() ?: return null,
                tracks = Track.listFromJson(j["tracks"]).toMutableList(),
                created = j["created"].str() ?: java.time.Instant.now().toString(),
            )
        }
    }
}

/** A Telegram channel the bot reads songs from. */
class TelegramChannel(
    val id: Long,
    var name: String,
    /** Newest first. */
    val tracks: MutableList<Track> = mutableListOf(),
    /** Highest post id seen, so "Import older songs" knows where to stop. */
    var latestPostId: Long = 0,
) {
    fun toJson() = jsonOf("id" to id, "name" to name, "latest" to latestPostId, "tracks" to tracks.toJson())

    companion object {
        fun fromJson(e: JsonElement?): TelegramChannel? {
            val j = e.obj() ?: return null
            return TelegramChannel(
                id = j["id"].long() ?: return null, name = j["name"].str() ?: "Telegram",
                tracks = Track.listFromJson(j["tracks"]).toMutableList(), latestPostId = j["latest"].long() ?: 0,
            )
        }

        /**
         * Channels from the saved Telegram settings. Versions before 1.1 kept a
         * single channel in top-level fields; that one becomes the first channel.
         */
        fun listFromSettings(j: JsonObject): List<TelegramChannel> {
            j["channels"].arr()?.let { list -> return list.mapNotNull { fromJson(it) } }
            val id = j["channelId"].long() ?: return emptyList()
            return listOf(
                TelegramChannel(
                    id = id, name = j["name"].str() ?: "Telegram",
                    tracks = Track.listFromJson(j["tracks"]).toMutableList(), latestPostId = j["latest"].long() ?: 0,
                ),
            )
        }

        /**
         * Combines a backed-up channel list with the one on this phone: every
         * current channel keeps its place and gains the songs the backup knew
         * about. Channels only in the backup come back too when [sameBot].
         */
        fun merge(current: List<TelegramChannel>, backup: List<TelegramChannel>, sameBot: Boolean): List<TelegramChannel> {
            val out = mutableListOf<TelegramChannel>()
            val old = backup.associateBy { it.id }.toMutableMap()
            for (c in current) {
                val b = old.remove(c.id)
                if (b == null) {
                    out += c
                    continue
                }
                val seen = c.tracks.map { it.id }.toSet()
                out += TelegramChannel(
                    c.id, c.name,
                    (c.tracks + b.tracks.filter { it.id !in seen }).toMutableList(),
                    maxOf(c.latestPostId, b.latestPostId),
                )
            }
            if (sameBot) out += old.values
            return out
        }
    }
}
