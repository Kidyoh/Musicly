package com.musicly.musicly.tg

import com.musicly.musicly.data.obj
import com.musicly.musicly.data.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TdTextTest {
    private fun msg(content: String, id: Long = 7) =
        parseJson("""{"@type":"message","id":$id,"chat_id":-1001,"date":1700000000,"content":$content}""").obj()!!

    private fun audio(size: Long = 5_000_000, title: String = "", performer: String = "", name: String = "A_B - C.mp3") =
        """{"@type":"messageAudio","audio":{"duration":201,"title":"$title","performer":"$performer","file_name":"$name",
        "mime_type":"audio/mpeg","audio":{"id":9,"size":$size,"expected_size":$size,"local":{"path":""}}}}"""

    @Test
    fun floodAndLoginErrors() {
        assertEquals(30, TdText.floodSeconds(TdError(429, "Too Many Requests: retry after 30")))
        assertEquals(300, TdText.floodSeconds(TdError(420, "FLOOD_WAIT_300")))
        assertNull(TdText.floodSeconds(TdError(400, "PHONE_CODE_INVALID")))
        assertEquals("Too many tries. Wait 30 seconds.", TdText.human(TdError(429, "Too Many Requests: retry after 30")))
        assertTrue(TdText.human(TdError(400, "PHONE_NUMBER_INVALID")).contains("phone number"))
        assertEquals("That code is not right.", TdText.human(TdError(400, "PHONE_CODE_INVALID")))
        assertEquals("Wrong password.", TdText.human(TdError(400, "PASSWORD_HASH_INVALID")))
    }

    @Test
    fun songFromAudioMessage() {
        val s = TdText.song(msg(audio(title = "Song", performer = "Band")))!!
        assertEquals("Song", s.title)
        assertEquals("Band", s.artist)
        assertEquals(201, s.durationSec)
        assertEquals(-1001L, s.chatId)
        assertEquals(7L, s.messageId)
        assertEquals(5_000_000L, s.sizeBytes)
        assertEquals(1700000000L, s.dateSec)
    }

    @Test
    fun songTagsFallBackToTheFileName() {
        val s = TdText.song(msg(audio(name = "Some_Artist - Some_Title.mp3")))!!
        assertEquals("Some Artist", s.artist)
        assertEquals("Some Title", s.title)
    }

    @Test
    fun hugeFilesAndOtherMessagesAreSkipped() {
        assertNull(TdText.song(msg(audio(size = 3L * 1024 * 1024 * 1024))))
        assertNull(TdText.song(msg("""{"@type":"messageText","text":{"text":"hi"}}""")))
    }

    @Test
    fun audioSentAsDocument() {
        val doc = """{"@type":"messageDocument","document":{"file_name":"x.flac","mime_type":"audio/flac","document":{"id":3,"size":900,"expected_size":900}}}"""
        assertNotNull(TdText.song(msg(doc)))
        assertEquals(3, TdText.audioFile(msg(doc)["content"].obj())!!.let { com.musicly.musicly.data.parseJson(it.toString()).obj()!!["id"].toString().toInt() })
        val pdf = """{"@type":"messageDocument","document":{"file_name":"x.pdf","mime_type":"application/pdf","document":{"id":3,"size":900}}}"""
        assertNull(TdText.song(msg(pdf)))
    }

    @Test
    fun jsonClientHelpers() {
        assertEquals("updateFile", TdJsonClient.typeOf("""{"@type":"updateFile","file":{}}"""))
        assertEquals("m12", TdJsonClient.extraOf("""{"@type":"ok","@extra":"m12"}"""))
        assertNull(TdJsonClient.extraOf("""{"@type":"updateX","text":"a"}"""))
    }
}
