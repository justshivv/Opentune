package com.opentune.data.model

import java.util.Locale

/** Where one printed credit ends and the next begins, in a byline. */
private val NAME_JOINS = Regex("""\s*,\s*|\s+&\s+|\s+x\s*|\s+and\s*|\s+(?:feat\.?|ft\.?|featuring|with)\s+""", RegexOption.IGNORE_CASE)

/**
 * Whether [line] prints more than one name: the only kind of credit [fullerArtist] ever
 * widens a shorter one to, so the only kind worth remembering by song.
 */
fun namesSeveral(line: String): Boolean = line.split(NAME_JOINS).count { it.isNotBlank() } > 1

/** Compilation and missing-credit labels are not a recording's performing artist. */
fun isPlaceholderArtist(name: String?): Boolean {
    val key = name.orEmpty().trim().replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    return key in setOf("", "variousartist", "variousartists", "va", "unknownartist", "unknown", "artist")
}

/**
 * The fullest honest credit for a row: [plain], the byline as the row prints
 * it, when it names the linked artist [linked] and more besides — a duet's
 * second singer is printed on the row but linked nowhere. A segment that
 * doesn't name the linked artist can't be trusted as the same credit (a band
 * billed as one run, "Simon & Garfunkel", splits into names that are not
 * it), so the linked name stands then, exactly as before.
 */
fun fullerArtist(linked: String?, plain: String?): String? {
    val segment = plain?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderArtist(it) } ?: return linked
    val anchor = linked?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderArtist(it) } ?: return segment
    val names = segment.split(NAME_JOINS).map { it.trim() }.filter { it.isNotEmpty() }
    return if (names.size > 1 && names.any { it.equals(anchor, ignoreCase = true) }) segment else anchor
}

/** Prefer explicit credits without inventing a name if the source only has a placeholder. */
fun preferredArtist(vararg names: String?): String =
    names.firstOrNull { !isPlaceholderArtist(it) }?.trim()
        ?: names.firstOrNull { !it.isNullOrBlank() }?.trim()
        ?: "Unknown artist"
