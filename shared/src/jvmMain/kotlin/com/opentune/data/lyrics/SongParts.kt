package com.opentune.data.lyrics

import java.util.Locale

/**
 * Where a song's choruses are, for the marks on the seek bar and "Skip to
 * chorus". Some sources name the parts; otherwise the chorus is found from
 * the lyrics: the line sung most often (at least twice), each time with the
 * lines just before it that come back as often.
 */
object SongParts {
    /** A chorus this close after another is the same one. */
    private const val SAME_MS = 15_000L
    /** Lines further apart than this aren't one stretch of singing. */
    private const val GAP_MS = 8_000L
    /** How long a chorus is taken to last when the source doesn't say. */
    const val CHORUS_MS = 20_000L

    fun choruses(lyrics: Lyrics.Synced): List<Long> {
        val named = lyrics.parts.filter { it.name.contains("chorus", ignoreCase = true) && !it.name.contains("pre", ignoreCase = true) }
        if (named.isNotEmpty()) return named.map { it.startMs }.distinct().sorted()
        return inferred(lyrics.lines)
    }

    /** Where the parts begin, for the seek bar: the source's own, else the choruses. */
    fun marks(lyrics: Lyrics.Synced): List<Long> =
        if (lyrics.parts.size >= 2) lyrics.parts.map { it.startMs }.filter { it > 0 }.distinct().sorted() else choruses(lyrics)

    /** The chorus in [starts] after [positionMs], unless one is playing now or it's about to start anyway. */
    fun nextChorus(starts: List<Long>, positionMs: Long): Long? {
        if (starts.any { it <= positionMs && positionMs < it + CHORUS_MS }) return null
        return starts.firstOrNull { it > positionMs + 3_000 }
    }

    fun inferred(lines: List<LyricLine>): List<Long> {
        val keys = lines.map { key(it.text) }
        val counts = keys.filter { it.length >= MIN_KEY }.groupingBy { it }.eachCount()
        val top = counts.values.maxOrNull() ?: return emptyList()
        if (top < 2) return emptyList()
        val hook = keys.first { it.length >= MIN_KEY && counts[it] == top }
        val starts = mutableListOf<Long>()
        keys.forEachIndexed { i, k ->
            if (k != hook) return@forEachIndexed
            var first = i
            // Back over the lines that lead into the hook every time, up to a few.
            while (first > 0 && i - first < 4) {
                val prev = first - 1
                if (keys[prev] == hook || (counts[keys[prev]] ?: 0) < top) break
                if (lines[first].startMs - lines[prev].endMs > GAP_MS) break
                first = prev
            }
            val at = lines[first].startMs
            if (starts.none { kotlin.math.abs(it - at) < SAME_MS }) starts += at
        }
        return if (starts.size >= 2) starts.sorted() else emptyList()
    }

    private const val MIN_KEY = 6

    private fun key(text: String) = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
