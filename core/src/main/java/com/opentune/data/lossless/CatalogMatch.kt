package com.opentune.data.lossless

import java.text.Normalizer
import java.util.Locale

/** Conservative cross-catalog identity: no substring matches or removal of edit/version names. */
internal data class CatalogIdentity(val title: String, val artist: String, val album: String?, val durationMs: Long) {
    fun matches(title: String, artists: List<String>, album: String?, durationMs: Long): Boolean =
        key(this.title).isNotEmpty() && key(this.artist).isNotEmpty() &&
            key(this.title) == key(title) && artistsMatch(this.artist, artists) &&
            (this.album.isNullOrBlank() || key(this.album) == key(album.orEmpty())) &&
            LosslessSource.durationMatches(this.durationMs, durationMs) &&
            !otherVersion(title, album)

    /**
     * An instrumental, karaoke or other version filed under the song's own title, given
     * away only by its album ("Tum Hi Ho" on "Aashiqui 2 (Instrumental)"): it played the
     * music without the vocals while the lyrics ran on. Words the wanted song has itself
     * don't count against it.
     */
    private fun otherVersion(title: String, album: String?): Boolean {
        val own = versionsIn("${this.title} ${this.album.orEmpty()}")
        return (versionsIn(title) + versionsIn(album.orEmpty())).any { it !in own }
    }

    companion object {
        private val VERSION = Regex(
            "\\b(instrumentals?|karaoke|backing track|minus one|without vocals|no vocals|music only|" +
                "acapella|a cappella|lofi|lo fi|slowed|reverb|sped up|8d|nightcore|remix|unplugged)\\b|伴奏",
        )

        private fun versionsIn(text: String): Set<String> =
            VERSION.findAll(key(text)).mapTo(HashSet()) { it.value.replace(" ", "").removeSuffix("s") }

        // Queue bylines join performers, while catalog APIs return an array.
        // Keep exact band names first (e.g. Simon & Garfunkel), then require
        // every named performer; a substring/one shared artist is not enough.
        private fun artistsMatch(credit: String, artists: List<String>): Boolean {
            if (artists.any { key(it) == key(credit) }) return true
            val separators = Regex("\\s*(?:,|&|;|\\u2022)\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)
            val wanted = credit.split(separators).map(::key).filter(String::isNotEmpty)
            val available = artists.flatMap { listOf(key(it)) + it.split(separators).map(::key) }.toSet()
            return wanted.size > 1 && wanted.all { it in available }
        }

        fun key(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
    }
}
