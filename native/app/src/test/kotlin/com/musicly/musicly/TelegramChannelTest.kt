package com.musicly.musicly

import com.musicly.musicly.data.TelegramChannel
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.data.jsonOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramChannelTest {
    private fun song(id: String) = Track(
        id = "tg:$id", title = "Song $id", artist = "Artist", source = TrackSource.telegram, uri = "FILE$id",
    ).toJson()

    @Test
    fun singleChannelFromV10BecomesFirstChannel() {
        val list = TelegramChannel.listFromSettings(
            jsonOf(
                "token" to "1:x", "channelId" to -100, "name" to "Vault", "latest" to 42,
                "tracks" to listOf(song("a"), song("b")),
            ),
        )
        assertEquals(1, list.size)
        assertEquals(-100L, list.first().id)
        assertEquals("Vault", list.first().name)
        assertEquals(42L, list.first().latestPostId)
        assertEquals(listOf("tg:a", "tg:b"), list.first().tracks.map { it.id })
    }

    @Test
    fun severalChannelsRoundTrip() {
        val saved = listOf(TelegramChannel(-1, "One", latestPostId = 3), TelegramChannel(-2, "Two"))
        val back = TelegramChannel.listFromSettings(jsonOf("channels" to saved.map { it.toJson() }))
        assertEquals(listOf("One", "Two"), back.map { it.name })
        assertEquals(3L, back.first().latestPostId)
        assertTrue(TelegramChannel.listFromSettings(jsonOf()).isEmpty())
    }

    @Test
    fun restoringABackupMergesChannelsAndSongs() {
        val current = TelegramChannel.listFromSettings(
            jsonOf(
                "channels" to listOf(
                    jsonOf("id" to -1, "name" to "One", "latest" to 5, "tracks" to listOf(song("new"))),
                ),
            ),
        )
        val backup = TelegramChannel.listFromSettings(
            jsonOf(
                "channels" to listOf(
                    jsonOf("id" to -1, "name" to "One", "latest" to 9, "tracks" to listOf(song("old"), song("new"))),
                    jsonOf("id" to -2, "name" to "Two", "tracks" to listOf(song("x"))),
                ),
            ),
        )
        val same = TelegramChannel.merge(current, backup, sameBot = true)
        assertEquals(listOf(-1L, -2L), same.map { it.id })
        assertEquals(listOf("tg:new", "tg:old"), same.first().tracks.map { it.id })
        assertEquals(9L, same.first().latestPostId)

        val other = TelegramChannel.merge(current, backup, sameBot = false)
        assertEquals(listOf(-1L), other.map { it.id })
    }
}
