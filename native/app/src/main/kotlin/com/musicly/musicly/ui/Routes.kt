@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.musicly.musicly.ui

import com.musicly.musicly.data.Library
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.UserPlaylist

/**
 * Opens [content] as a page on top of the tabs, closing Now Playing first
 * (Flutter `openPage`).
 */
fun openPage(env: Env, key: String, content: @androidx.compose.runtime.Composable () -> Unit) {
    env.nowPlayingOpen = false
    env.nav.push(key, content)
}

fun openArtist(env: Env, name: String) =
    openPage(env, "artist:$name") { ArtistScreen(name) }

/** Album of a song on the phone. */
fun openAlbumOf(env: Env, t: Track) {
    val albumId = t.albumId ?: return
    openLive(
        env, t.album ?: "Album", t.artist,
        { l -> l.deviceTracks.filter { it.albumId == albumId } },
        kind = "Album", cover = t,
    )
}

fun openUserPlaylist(env: Env, p: UserPlaylist) =
    openPage(env, "playlist:${p.id}") { CollectionScreen(playlistId = p.id) }

fun openLive(
    env: Env,
    title: String,
    owner: String,
    live: (Library) -> List<Track>,
    kind: String = "Collection",
    cover: Track? = null,
    removableFiles: Boolean = false,
) = openPage(env, "live:$kind:$title:$owner") {
    CollectionScreen(
        title = title, owner = owner, kind = kind, cover = cover, live = live,
        removableFiles = removableFiles,
    )
}
