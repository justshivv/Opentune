package com.opentune.data.model

import java.util.Locale

/** Compilation and missing-credit labels are not a recording's performing artist. */
fun isPlaceholderArtist(name: String?): Boolean {
    val key = name.orEmpty().trim().replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    return key in setOf("", "variousartist", "variousartists", "va", "unknownartist", "unknown", "artist")
}

/** Prefer explicit credits without inventing a name if the source only has a placeholder. */
fun preferredArtist(vararg names: String?): String =
    names.firstOrNull { !isPlaceholderArtist(it) }?.trim()
        ?: names.firstOrNull { !it.isNullOrBlank() }?.trim()
        ?: "Unknown artist"
