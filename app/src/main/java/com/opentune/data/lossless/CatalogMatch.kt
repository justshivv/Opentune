package com.opentune.data.lossless

import java.text.Normalizer
import java.util.Locale

/** Conservative cross-catalog identity: no substring matches or removal of edit/version names. */
internal data class CatalogIdentity(val title: String, val artist: String, val album: String?, val durationMs: Long) {
    fun matches(title: String, artists: List<String>, album: String?, durationMs: Long): Boolean =
        key(this.title).isNotEmpty() && key(this.artist).isNotEmpty() &&
            key(this.title) == key(title) && artistsMatch(this.artist, artists) &&
            (this.album.isNullOrBlank() || key(this.album) == key(album.orEmpty())) &&
            LosslessSource.durationMatches(this.durationMs, durationMs)

    companion object {
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
