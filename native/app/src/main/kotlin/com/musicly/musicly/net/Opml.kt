package com.musicly.musicly.net

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/** Reads OPML files (the standard way podcast apps list feeds). */
object Opml {
    /**
     * Every feed in the file. The group is the folder (outer outline) the feed
     * sits in, or the file's title when it has none.
     */
    fun parse(text: String): List<RssSuggestion> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            for ((f, v) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            )) try { setFeature(f, v) } catch (_: Exception) { }
        }
        val doc = try {
            factory.newDocumentBuilder().parse(text.trim().byteInputStream())
        } catch (_: Exception) {
            throw Exception("That file is not an OPML list.")
        }
        val root = doc.documentElement
        if (!root.tagName.equals("opml", ignoreCase = true)) throw Exception("That file is not an OPML list.")
        val title = firstChild(root, "head")?.let { firstChild(it, "title")?.textContent?.trim() }
            ?.takeIf { it.isNotEmpty() } ?: "Imported feeds"
        val out = mutableListOf<RssSuggestion>()
        val seen = mutableSetOf<String>()
        fun walk(e: Element, group: String) {
            var n: Node? = e.firstChild
            while (n != null) {
                if (n is Element && n.tagName.equals("outline", ignoreCase = true)) {
                    val url = n.getAttribute("xmlUrl").trim()
                    val name = n.getAttribute("text").ifBlank { n.getAttribute("title") }.trim()
                    if (url.isNotEmpty()) {
                        if (seen.add(url)) out += RssSuggestion(group, name.ifEmpty { url }, "", url)
                    } else {
                        walk(n, name.ifEmpty { group })
                    }
                }
                n = n.nextSibling
            }
        }
        firstChild(root, "body")?.let { walk(it, title) }
        return out
    }

    private fun firstChild(e: Element, tag: String): Element? {
        var n: Node? = e.firstChild
        while (n != null) {
            if (n is Element && n.tagName.equals(tag, ignoreCase = true)) return n
            n = n.nextSibling
        }
        return null
    }
}
