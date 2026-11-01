package com.musicly.musicly.net

/** A ready-made RSS feed the user can add with one tap. */
data class RssSuggestion(val group: String, val title: String, val note: String, val url: String)

/**
 * Feeds that carry music or music shows. Every link was checked to be a
 * working RSS feed with audio files in it.
 */
val rssCatalog = listOf(
    RssSuggestion("Music shows", "Tiny Desk Concerts", "Live sets from NPR Music, audio only", "https://feeds.npr.org/510306/podcast.xml"),
    RssSuggestion("Music shows", "NPR Music", "New music and artist talk (All Songs Considered)", "https://feeds.npr.org/510019/podcast.xml"),
    RssSuggestion("Music shows", "Resident Advisor", "Electronic music DJ mixes", "https://ra.co/xml/podcast.xml"),
    RssSuggestion("Free music (Internet Archive)", "Free Music Archive", "Free songs shared by their artists", "https://archive.org/services/collection-rss.php?collection=freemusicarchive"),
    RssSuggestion("Free music (Internet Archive)", "Netlabels", "Independent releases, many under Creative Commons", "https://archive.org/services/collection-rss.php?collection=netlabels"),
    RssSuggestion("Free music (Internet Archive)", "Music & albums", "Albums and recordings people uploaded", "https://archive.org/services/collection-rss.php?collection=audio_music"),
    RssSuggestion("Free music (Internet Archive)", "Live concerts", "Concert recordings that bands allow fans to trade", "https://archive.org/services/collection-rss.php?collection=etree"),
    RssSuggestion("Old records", "78 rpm records", "Songs digitised from old shellac records", "https://archive.org/services/collection-rss.php?collection=78rpm"),
    RssSuggestion("Old records", "Great 78 Project", "More digitised 78s from the George Blood collection", "https://archive.org/services/collection-rss.php?collection=georgeblood"),
)
