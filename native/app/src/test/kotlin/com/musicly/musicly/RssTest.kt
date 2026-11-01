package com.musicly.musicly

import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import com.musicly.musicly.net.RssApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RssTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>Night Mixes</title>
    <itunes:author>DJ Example</itunes:author>
    <itunes:image href="http://img.example.com/show.jpg"/>
    <item>
      <title>Mix 1</title>
      <enclosure url="http://cdn.example.com/mix1.mp3" type="audio/mpeg" length="1"/>
      <itunes:duration>1:02:03</itunes:duration>
      <itunes:image href="https://img.example.com/mix1.jpg"/>
    </item>
    <item>
      <title>Mix 2</title>
      <itunes:author>Guest</itunes:author>
      <enclosure url="https://cdn.example.com/mix2.m4a?x=1" type="application/octet-stream"/>
      <itunes:duration>754</itunes:duration>
    </item>
    <item><title>Video</title><enclosure url="https://cdn.example.com/v.mp4" type="video/mp4"/></item>
    <item><title>Dupe</title><enclosure url="https://cdn.example.com/mix2.m4a?x=1" type="audio/mp4"/></item>
    <item><title>No file</title></item>
  </channel>
</rss>"""

    @Test
    fun readsAudioItemsFromAnRssFeed() {
        val r = RssApi.parse(feed, "https://example.com/feed.xml")
        assertEquals("Night Mixes", r.feed.title)
        assertEquals("https://img.example.com/show.jpg", r.feed.imageUrl)
        assertEquals(listOf("Mix 1", "Mix 2"), r.tracks.map { it.title })
        val a = r.tracks[0]
        assertEquals("https://cdn.example.com/mix1.mp3", a.uri)
        assertEquals(TrackSource.feed, a.source)
        assertEquals("DJ Example", a.artist)
        assertEquals("Night Mixes", a.album)
        assertEquals((1 * 3600 + 2 * 60 + 3) * 1000L, a.duration)
        assertEquals("https://img.example.com/mix1.jpg", a.artworkUrl)
        val b = r.tracks[1]
        assertEquals("Guest", b.artist)
        assertEquals(754_000L, b.duration)
        assertEquals("https://img.example.com/show.jpg", b.artworkUrl)
    }

    @Test
    fun tracksSurviveBeingSaved() {
        val t = RssApi.parse(feed, "u").tracks.first()
        val back = Track.fromJson(t.toJson())!!
        assertEquals(t.id, back.id)
        assertEquals(TrackSource.feed, back.source)
    }

    private fun failure(body: String): Exception {
        try {
            RssApi.parse(body, "u")
        } catch (e: Exception) {
            return e
        }
        fail("expected an exception")
        throw AssertionError()
    }

    @Test
    fun saysWhatIsWrongWithABadLink() {
        failure("<html></html>")
        failure("not xml")
        assertTrue(failure("<rss><channel><title>x</title></channel></rss>").toString().contains("no audio"))
    }

    @Test
    fun tidiesPastedLinks() {
        assertEquals("https://a.com/x", RssApi.normalizeUrl(" feed://a.com/x "))
        assertEquals("https://a.com/x", RssApi.normalizeUrl("a.com/x"))
        assertEquals("Https://a.com/X", RssApi.normalizeUrl("Https://a.com/X"))
    }
}
