package com.musicly.musicly.net

import com.musicly.musicly.data.RssFeed
import com.musicly.musicly.data.Track
import com.musicly.musicly.data.TrackSource
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** A feed with its songs/episodes. */
class RssResult(val feed: RssFeed, val tracks: List<Track>)

/**
 * Reads RSS feeds whose items carry an audio file (`<enclosure>`), such as
 * podcasts and music or mix feeds, so they can be streamed.
 */
class RssApi {
    suspend fun load(input: String): RssResult {
        val url = normalizeUrl(input)
        if (url.toHttpUrlOrNull() == null) throw Exception("That does not look like a web address.")
        val res = try {
            Http.get(url, timeoutSeconds = 20)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw Exception("Could not reach that feed. Check the link and your connection.")
        }
        val body = res.use {
            if (it.code != 200) throw Exception("The feed answered with error ${it.code}.")
            try {
                it.body.string()
            } catch (_: java.io.IOException) {
                throw Exception("Could not reach that feed. Check the link and your connection.")
            }
        }
        return parse(body, url)
    }

    companion object {
        private val audioExt = listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac")

        /** Accepts "example.com/feed", "feed://..." and "https://...". */
        fun normalizeUrl(input: String): String {
            var u = input.trim()
            val lower = u.lowercase()
            if (lower.startsWith("feed://") || lower.startsWith("itpc://")) {
                u = "https://${u.substring(7)}"
            } else if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                u = "https://$u"
            }
            return u
        }

        private fun parseXml(body: String): Element {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            f.isValidating = false
            f.isExpandEntityReferences = false
            for ((feature, value) in listOf(
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            )) {
                try {
                    f.setFeature(feature, value)
                } catch (_: Exception) {
                }
            }
            val b = f.newDocumentBuilder()
            b.setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
            b.setErrorHandler(object : ErrorHandler {
                override fun warning(e: SAXParseException) {}
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
            return b.parse(InputSource(StringReader(body.trimStart()))).documentElement
        }

        private fun local(e: Node): String = e.nodeName.substringAfter(':')

        private fun children(e: Element?): List<Element> {
            val out = ArrayList<Element>()
            var n = e?.firstChild
            while (n != null) {
                if (n is Element) out += n
                n = n.nextSibling
            }
            return out
        }

        private fun descendants(e: Element, name: String, out: MutableList<Element> = ArrayList()): List<Element> {
            for (c in children(e)) {
                if (local(c) == name) out += c
                descendants(c, name, out)
            }
            return out
        }

        private fun child(e: Element?, name: String): Element? = children(e).firstOrNull { local(it) == name }

        private fun text(e: Element?, name: String): String? {
            val t = child(e, name)?.textContent?.trim()
            return if (t.isNullOrEmpty()) null else t
        }

        private fun attr(e: Element, name: String, attr: String): String? {
            val v = child(e, name)?.getAttribute(attr)?.trim()
            return if (v.isNullOrEmpty()) null else v
        }

        private fun hostOf(u: String): String? = try {
            URI(u).host
        } catch (_: Exception) {
            null
        }

        /** Phones block plain-http audio by default, so prefer https links. */
        private fun https(u: String?): String? {
            if (u.isNullOrEmpty()) return null
            val host = hostOf(u)
            if (host == "localhost" || host == "127.0.0.1") return u
            if (u.startsWith("http://")) return "https://${u.substring(7)}"
            return if (u.startsWith("http")) u else null
        }

        private fun audioUrl(item: Element): String? {
            for (e in children(item)) {
                val n = local(e)
                if (n != "enclosure" && n != "content") continue
                val url = e.getAttribute("url").trim()
                if (url.isEmpty()) continue
                val type = e.getAttribute("type").lowercase()
                val path = (try {
                    URI(url).path
                } catch (_: Exception) {
                    null
                } ?: url).lowercase()
                if (type.startsWith("audio/") ||
                    (type == "application/octet-stream" && audioExt.any { path.endsWith(it) }) ||
                    (type.isEmpty() && audioExt.any { path.endsWith(it) })
                ) {
                    return https(url)
                }
            }
            return null
        }

        /** "1:02:03", "12:30" or "754" (seconds), as milliseconds. */
        private fun duration(s: String?): Long? {
            if (s == null) return null
            val parts = s.split(':').map { it.trim().toDoubleOrNull() }
            if (parts.isEmpty() || parts.any { it == null } || parts.size > 3) return null
            var secs = 0.0
            for (p in parts) secs = secs * 60 + p!!
            return if (secs <= 0) null else Math.round(secs * 1000)
        }

        /** Parses RSS 2.0 text. Throws a readable error when it is not a feed or has nothing playable. */
        fun parse(body: String, url: String): RssResult {
            val root = try {
                parseXml(body)
            } catch (_: Exception) {
                throw Exception("That link is not an RSS feed.")
            }
            val channel = (if (local(root) == "channel") listOf(root) else emptyList()).plus(descendants(root, "channel"))
                .firstOrNull() ?: throw Exception("That link is not an RSS feed.")

            val title = text(channel, "title") ?: (hostOf(url) ?: "")
            val author = text(channel, "author") ?: title
            val image = attr(channel, "image", "href") ?: text(child(channel, "image"), "url")
            val feedImage = https(image)

            val tracks = ArrayList<Track>()
            val seen = HashSet<String>()
            for (item in children(channel).filter { local(it) == "item" }) {
                val audio = audioUrl(item) ?: continue
                if (!seen.add(audio)) continue
                tracks += Track(
                    id = "rss:$audio",
                    title = text(item, "title") ?: "Untitled",
                    artist = text(item, "author") ?: author,
                    album = title,
                    source = TrackSource.feed,
                    uri = audio,
                    artworkUrl = https(attr(item, "image", "href")) ?: feedImage,
                    duration = duration(text(item, "duration")),
                    link = https(text(item, "link")),
                )
            }
            if (tracks.isEmpty()) throw Exception("This feed has no audio to play.")
            return RssResult(RssFeed(url = url, title = title, imageUrl = feedImage), tracks)
        }
    }
}
