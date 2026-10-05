package com.opentune.data.lyrics

import java.util.Locale

/** One timed word (or syllable) inside a synced line. */
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /**
     * Per-word timing. Exact when the source has word sync (enhanced LRC);
     * otherwise spread across the line by character count, which is close
     * enough to make the highlight sweep instead of jump.
     */
    val words: List<LyricWord>,
    val wordSynced: Boolean,
)

sealed interface Lyrics {
    val source: String

    data class Synced(val lines: List<LyricLine>, override val source: String) : Lyrics
    data class Plain(val text: String, override val source: String) : Lyrics
}

/** Parses LRC, including the enhanced `<mm:ss.xx>` word tags some sources carry. */
object LrcParser {
    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    /** A line with no following line is given this long before it ends. */
    private const val LAST_LINE_MS = 5_000L

    fun parse(lrc: String, durationMs: Long = 0L): List<LyricLine> {
        val offset = OFFSET_TAG.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        // A line can carry several timestamps when a chorus repeats.
        val raw = mutableListOf<Pair<Long, String>>()
        lrc.lineSequence().forEach { line ->
            val tags = LINE_TAG.findAll(line).toList()
            if (tags.isEmpty() || tags.first().range.first != 0) return@forEach
            val text = line.substring(tags.last().range.last + 1)
            tags.forEach { raw += (toMs(it) - offset).coerceAtLeast(0) to text }
        }
        raw.sortBy { it.first }

        val lines = mutableListOf<LyricLine>()
        raw.forEachIndexed { i, (start, body) ->
            val nextStart = raw.getOrNull(i + 1)?.first
            val end = nextStart
                ?: if (durationMs > start) minOf(durationMs, start + LAST_LINE_MS * 2) else start + LAST_LINE_MS
            val timedWords = parseWords(body, end, offset)
            val text = WORD_TAG.replace(body, "").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return@forEachIndexed // instrumental gap marker
            lines += LyricLine(
                startMs = start,
                endMs = end,
                text = text,
                words = timedWords ?: spreadWords(text, start, end),
                wordSynced = timedWords != null,
            )
        }
        return lines
    }

    private fun parseWords(body: String, lineEnd: Long, offset: Long): List<LyricWord>? {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.size < 2) return null
        val words = mutableListOf<LyricWord>()
        tags.forEachIndexed { i, tag ->
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: body.length
            val text = body.substring(tag.range.last + 1, textEnd)
            if (text.isBlank()) return@forEachIndexed
            val start = (toMs(tag) - offset).coerceAtLeast(0)
            val end = tags.getOrNull(i + 1)?.let { (toMs(it) - offset).coerceAtLeast(start) } ?: lineEnd
            words += LyricWord(text, start, end)
        }
        return words.takeIf { it.isNotEmpty() }
    }

    /**
     * Estimate word timing for a line-synced lyric. Singers rarely use the
     * full gap to the next line, so the sweep finishes a little early.
     */
    fun spreadWords(text: String, start: Long, end: Long): List<LyricWord> {
        val tokens = Regex("""\S+\s*""").findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return emptyList()
        val span = ((end - start) * 0.85).toLong().coerceIn(300L, 8_000L)
        val totalChars = tokens.sumOf { it.trim().length.coerceAtLeast(1) }
        var cursor = start
        return tokens.map { token ->
            val share = span * token.trim().length.coerceAtLeast(1) / totalChars
            LyricWord(token, cursor, cursor + share).also { cursor += share }
        }
    }

    private fun toMs(match: MatchResult): Long {
        val (min, sec, frac) = match.destructured
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs
    }
}

/** Index of the line playing at [positionMs], or -1 before the first one. */
fun List<LyricLine>.activeIndex(positionMs: Long): Int {
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/**
 * Turns a YouTube upload's title and channel into something a lyrics database
 * will match: "The Weeknd - Blinding Lights (Official Video)" by
 * "TheWeekndVEVO" becomes "Blinding Lights" by "The Weeknd".
 */
object TrackNameCleaner {
    private val NOISE = Regex(
        """\s*[(\[](?:[^)\]]*\b(?:official|lyrics?|lyric video|audio|video|visuali[sz]er|mv|m/v|hd|hq|4k|remaster(?:ed)?|explicit|clean|color coded)\b[^)\]]*)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("""\s*[(\[]?\s*\b(?:feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val CHANNEL_SUFFIX = Regex("""\s*(?:VEVO|- Topic|Official|Music)$""", RegexOption.IGNORE_CASE)

    data class Cleaned(val title: String, val artist: String)

    fun clean(title: String, artist: String): Cleaned {
        var t = NOISE.replace(title, "")
        var a = CHANNEL_SUFFIX.replace(artist.trim(), "").trim()
        // "Artist - Title" uploads: take the artist from the title when it
        // agrees with the channel (which is often the same name, unspaced).
        val dash = Regex("""\s+[-–—]\s+""").find(t)
        if (dash != null) {
            val left = t.substring(0, dash.range.first).trim()
            val right = t.substring(dash.range.last + 1).trim()
            val leftKey = left.lowercase(Locale.ROOT).replace(" ", "")
            val artistKey = a.lowercase(Locale.ROOT).replace(" ", "")
            if (a.isEmpty() || leftKey.contains(artistKey) || artistKey.contains(leftKey)) {
                a = left
                t = right
            }
        }
        t = FEAT.replace(t, "")
        a = FEAT.replace(a, "").substringBefore(",").substringBefore(" & ").trim()
        t = t.replace(Regex("""\s*["“”]\s*"""), " ").replace(Regex("\\s+"), " ").trim()
        return Cleaned(t.ifEmpty { title.trim() }, a.ifEmpty { artist.trim() })
    }
}
