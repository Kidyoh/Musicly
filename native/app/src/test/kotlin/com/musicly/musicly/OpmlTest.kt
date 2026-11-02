package com.musicly.musicly

import com.musicly.musicly.net.Opml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpmlTest {
    private val sample = """<?xml version="1.0"?>
<opml version="2.0"><head><title>My shows</title></head><body>
  <outline text="Radio"><outline type="rss" text="One" xmlUrl="https://a.example/one.xml"/>
    <outline type="rss" title="Two" xmlUrl="https://a.example/two.xml"/></outline>
  <outline type="rss" text="Loose" xmlUrl="https://a.example/loose.xml"/>
  <outline type="rss" text="Dupe" xmlUrl="https://a.example/one.xml"/>
</body></opml>"""

    @Test
    fun readsFeedsAndFolders() {
        val l = Opml.parse(sample)
        assertEquals(listOf("One", "Two", "Loose"), l.map { it.title })
        assertEquals(listOf("Radio", "Radio", "My shows"), l.map { it.group })
        assertEquals("https://a.example/two.xml", l[1].url)
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(Exception::class.java) { Opml.parse("<html/>") }
        assertThrows(Exception::class.java) { Opml.parse("nope") }
    }
}

class BundledOpmlTest {
    @Test
    fun bundledFileParsesWhenPresent() {
        val f = java.io.File("src/main/assets/default_feeds.opml")
        org.junit.Assume.assumeTrue(f.exists())
        val l = Opml.parse(f.readText())
        assertEquals(31, l.size)
        assertEquals(31, l.map { it.url }.toSet().size)
    }
}
