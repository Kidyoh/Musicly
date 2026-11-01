package com.musicly.musicly

import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.data.jsonOf
import com.musicly.musicly.net.TelegramBot
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotTest {
    @Test
    fun channelReferences() {
        assertEquals("@mychannel", TelegramBot.normaliseChat("@mychannel"))
        assertEquals("@mychannel", TelegramBot.normaliseChat("mychannel"))
        assertEquals("@mychannel", TelegramBot.normaliseChat("https://t.me/mychannel"))
        assertEquals("@mychannel", TelegramBot.normaliseChat("t.me/s/mychannel/123"))
        assertEquals(-1001234567890L, TelegramBot.normaliseChat(" -1001234567890 "))
    }

    private fun audio(
        title: String? = null,
        performer: String? = null,
        name: String? = null,
        size: Long = 5000000,
        asDocument: Boolean = false,
        mime: String = "audio/mpeg",
    ): JsonObject {
        val file = jsonOf(
            "file_id" to "FID", "file_unique_id" to "UID", "duration" to 200,
            "title" to title, "performer" to performer, "file_name" to name,
            "mime_type" to mime, "file_size" to size,
            "thumbnail" to jsonOf("file_id" to "TH"),
        )
        return jsonOf((if (asDocument) "document" else "audio") to file)
    }

    @Test
    fun readsTagsThenArtistTitleFileNames() {
        var t = TelegramBot.trackFrom(audio(title = "Ye", performer = "Burna Boy"), "Vault")!!
        assertEquals("Ye", t.title)
        assertEquals("Burna Boy", t.artist)
        assertEquals(TrackSource.telegram, t.source)
        assertEquals("FID", t.uri)
        assertEquals("tgthumb:TH", t.artworkUrl)
        assertEquals("Vault", t.album)
        assertEquals(200_000L, t.duration)
        t = TelegramBot.trackFrom(audio(name = "Asake - Lonely_At_The_Top.mp3", asDocument = true), "Vault")!!
        assertEquals("Lonely At The Top", t.title)
        assertEquals("Asake", t.artist)
    }

    @Test
    fun skipsNonAudioAndFilesBotsCannotDownload() {
        assertNull(TelegramBot.trackFrom(jsonOf("text" to "hi"), "V"))
        assertNull(TelegramBot.trackFrom(audio(asDocument = true, mime = "application/pdf"), "V"))
        val big = audio(title = "Long mix", size = 30L * 1024 * 1024)
        assertNull(TelegramBot.trackFrom(big, "V"))
        assertTrue(TelegramBot.isTooBig(big))
        assertFalse(TelegramBot.isTooBig(audio(title = "x")))
    }

    private fun added(id: Long, title: String, status: String, type: String = "channel") = jsonOf(
        "my_chat_member" to jsonOf(
            "chat" to jsonOf("id" to id, "title" to title, "type" to type),
            "new_chat_member" to jsonOf("status" to status),
        ),
    )

    @Test
    fun findsChannelsTheBotWasMadeAdminOf() {
        val found = TelegramBot.channelsFrom(
            listOf(
                added(-1001, "Old", "administrator"),
                added(-1002, "Removed", "administrator"),
                jsonOf("channel_post" to jsonOf()),
                added(-1003, "A group", "administrator", type = "supergroup"),
                added(-1002, "Removed", "left"),
                added(-1004, "New", "administrator"),
            ),
        )
        assertEquals(listOf("New", "Old"), found.map { it.title })
        assertEquals(-1004L, found.first().id)
        assertNotNull(found.firstOrNull())
    }
}
