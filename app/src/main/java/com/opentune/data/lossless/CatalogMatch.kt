package com.opentune.data.lossless

import java.text.Normalizer
import java.util.Locale

/** Conservative cross-catalog identity: no substring matches or removal of edit/version names. */
internal data class CatalogIdentity(val title: String, val artist: String, val album: String?, val durationMs: Long) {
    fun matches(title: String, artists: List<String>, album: String?, durationMs: Long): Boolean =
        key(this.title).isNotEmpty() && key(this.artist).isNotEmpty() &&
            key(this.title) == key(title) && artists.any { key(it) == key(this.artist) } &&
            (this.album.isNullOrBlank() || key(this.album) == key(album.orEmpty())) &&
            LosslessSource.durationMatches(this.durationMs, durationMs)

    companion object {
        fun key(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
    }
}
