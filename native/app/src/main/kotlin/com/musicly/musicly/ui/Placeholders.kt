package com.musicly.musicly.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.musicly.musicly.data.Library
import com.musicly.musicly.data.Track

// Placeholders: the screen agents replace this file with the real screens.

@Composable
fun CollectionScreen(
    title: String,
    owner: String,
    ownerArtistId: String? = null,
    kind: String = "Playlist",
    year: Int? = null,
    cover: Track? = null,
    load: (suspend () -> List<Track>)? = null,
    live: ((Library) -> List<Track>)? = null,
    removableFiles: Boolean = false,
    actions: List<@Composable () -> Unit> = emptyList(),
    onRefresh: (suspend () -> Unit)? = null,
    banner: (@Composable () -> Unit)? = null,
) {
    Text("$kind: $title")
}

/** A playlist the user made: editable, reorderable (Dart `CollectionScreen.user`). */
@Composable
fun CollectionScreen(playlistId: String) {
    Text("Playlist $playlistId")
}

@Composable
fun ArtistScreen(name: String) {
    Text("Artist: $name")
}
