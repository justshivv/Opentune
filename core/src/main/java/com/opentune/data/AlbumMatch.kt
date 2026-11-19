package com.opentune.data

/**
 * How a song played from a video is matched to its album's track (see
 * [MusicRepository.albumIdFor]). Video titles carry extras the album's track
 * doesn't, `Roj Roj Aankhon Tale (From "Jeeva")` for `Roj Roj Aankhon Tale`,
 * and the artist line is written differently (`Asha Bhosle, Rahul Dev Burman`
 * for `Asha Bhosle & R. D. Burman`).
 */
internal object AlbumMatch {
    private val brackets = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val dashTail = Regex("""\s+[-–|]\s+.*$""")
    private val notWord = Regex("""[^\p{L}\p{N}]+""")
    private val fromFilm = Regex("""[(\[]\s*from\s+(?:the\s+)?(?:film\s+|movie\s+|album\s+)?["“'‘]?([^"”'’)\]]+?)["”'’]?\s*[)\]]""", RegexOption.IGNORE_CASE)

    /** The title without anything in brackets, nor what follows a spaced dash: what a search should look for. */
    fun bareTitle(title: String): String =
        title.replace(brackets, "").replace(dashTail, "").trim().ifEmpty { title.trim() }

    /** Lower case, letters and digits only, single spaces. */
    private fun key(s: String): String = s.lowercase().replace(notWord, " ").trim()

    /** The same title once brackets, a dash's tail, case and punctuation are set aside. */
    fun sameTitle(a: String, b: String): Boolean {
        val x = key(bareTitle(a))
        return x.isNotEmpty() && x == key(bareTitle(b))
    }

    /** The film or album a title names, as in `Song (From "Film")`. */
    fun fromTitle(title: String): String? = fromFilm.find(title)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A name in common between two artist lines. Each name on [ours] counts by its last word
     * (a surname, so `Rahul Dev Burman` finds `R. D. Burman`), and only words of three letters
     * or more, so an initial alone never matches.
     */
    fun sharesArtist(theirs: String, ours: String): Boolean {
        val words = key(theirs).split(' ').toSet()
        return ours.split(",", "&", " and ", " x ", " feat. ", " ft. ")
            .mapNotNull { name -> key(name).split(' ').lastOrNull()?.takeIf { it.length >= 3 } }
            .any { it in words }
    }
}
