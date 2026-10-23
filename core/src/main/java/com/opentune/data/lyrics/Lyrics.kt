package com.opentune.data.lyrics

import java.util.Locale
import kotlinx.serialization.Serializable

/** One timed word (or syllable) inside a synced line. */
@Serializable
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

@Serializable
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
    /** Who sings it, where the source says: 0 the lead, 1 a second voice in a duet, 2 everyone together. */
    val voice: Int = VOICE_LEAD,
    /** Backing vocals sung under this line, shown smaller beneath it. */
    val background: String? = null,
    /**
     * Right to left (Arabic, Hebrew, Persian, Urdu), as the source marks it;
     * null when it doesn't say, and the text's own script decides. Kept with
     * downloaded lyrics like the rest of the line.
     */
    val rtl: Boolean? = null,
)

/** Whether [line] reads right to left: as its source says, else by its first letter with a direction. */
fun readsRightToLeft(line: LyricLine): Boolean = line.rtl ?: run {
    for (c in line.text) {
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return@run true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return@run false
        }
    }
    false
}

const val VOICE_LEAD = 0
const val VOICE_SECOND = 1
const val VOICE_TOGETHER = 2

/** A named stretch of a song (verse, chorus, bridge) as the lyrics source marks it. */
@Serializable
data class SongPart(val name: String, val startMs: Long, val endMs: Long)

@Serializable
sealed interface Lyrics {
    val source: String

    @Serializable
    data class Synced(
        val lines: List<LyricLine>,
        override val source: String,
        /** The song's verse, chorus and so on, when the source marks them; see [SongParts]. */
        val parts: List<SongPart> = emptyList(),
        /** How long the recording the source timed these to is, in ms; 0 when it doesn't say. */
        val recordingMs: Long = 0,
        /**
         * A shift found by comparing the sources' timing, added to the
         * position as the per-song offset is; that offset, once set, is used
         * instead. See [lyricsOffset].
         */
        val autoOffsetMs: Long = 0,
    ) : Lyrics

    @Serializable
    data class Plain(val text: String, override val source: String) : Lyrics
}

/** What to add to the playback position for [lyrics]: the listener's own offset for the song ([manual]), else the one found automatically. */
fun lyricsOffset(manual: Long?, lyrics: Lyrics?): Long = manual ?: (lyrics as? Lyrics.Synced)?.autoOffsetMs ?: 0L

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
     * full gap to the next line, so the sweep finishes a little early, and
     * never takes much longer than the words take to sing: a short line
     * before an instrumental break used to crawl on through the break, and
     * the break only showed as one once the crawl was done.
     */
    fun spreadWords(text: String, start: Long, end: Long): List<LyricWord> {
        val tokens = Regex("""\S+\s*""").findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return emptyList()
        val span = minOf(((end - start) * 0.85).toLong(), singingMs(text) * 3 / 2).coerceIn(300L, 8_000L)
        val totalChars = tokens.sumOf { it.trim().length.coerceAtLeast(1) }
        var cursor = start
        return tokens.map { token ->
            val share = span * token.trim().length.coerceAtLeast(1) / totalChars
            LyricWord(token, cursor, cursor + share).also { cursor += share }
        }
    }

    /**
     * About how long [text] takes to sing at an ordinary pace: a syllable's worth
     * of time for each Chinese, Japanese or Korean character, less for each letter
     * of an alphabet, where several letters make a syllable.
     */
    internal fun singingMs(text: String): Long {
        var ms = 400L
        for (c in text) {
            if (!Character.isLetterOrDigit(c)) continue
            val script = Character.UnicodeScript.of(c.code)
            ms += when (script) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL,
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> 330L
                else -> 160L
            }
        }
        return ms
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

/** A stretch with no singing between two lines, long enough to show as an instrumental break. */
data class LyricGap(val afterIndex: Int, val startMs: Long, val endMs: Long)

/**
 * When the singing in this line actually stops: the end of its last word, or
 * the line's end when it carries no word timing. For a line-synced lyric the
 * spread words finish a little before the next line, so a long tail of dead
 * air after a short line shows up here as time with nothing being sung.
 */
fun LyricLine.sungUntilMs(): Long = words.maxOfOrNull { it.endMs } ?: endMs

/**
 * Instrumental breaks between lines: a line stops being sung and the next
 * doesn't start for at least [minMs]. Used to show breathing dots in the gap
 * instead of leaving the last line lit through a passage with no vocals.
 */
fun List<LyricLine>.instrumentalGaps(minMs: Long = 5_000L): List<LyricGap> {
    val out = mutableListOf<LyricGap>()
    for (i in indices) {
        val next = getOrNull(i + 1) ?: break
        val from = this[i].sungUntilMs()
        val to = next.startMs
        if (to - from >= minMs) out += LyricGap(i, from, to)
    }
    return out
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
        """\s*[(\[](?:[^)\]]*\b(?:official|lyrics?|lyric video|audio|video|visuali[sz]er|mv|m/v|hd|hq|4k|remaster(?:ed)?|explicit|clean|color coded|deluxe|bonus|mono|stereo|radio edit|(?:single|album) version)\b[^)\]]*)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("""\s*[(\[]?\s*\b(?:feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    /** A release note after a dash, as catalogues write them: "Song - 2011 Remaster", "Song - Radio Edit". */
    private val RELEASE_SUFFIX = Regex(
        """\s+[-–—]\s+(?:(?:\d{4}\s+)?remaster(?:ed)?(?:\s+(?:version|\d{4}))?|radio edit|(?:single|album|mono|stereo) version|mono|stereo|bonus track|deluxe(?:\s+edition)?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val CHANNEL_SUFFIX = Regex("""\s*(?:VEVO|- Topic|Official|Music)$""", RegexOption.IGNORE_CASE)

    data class Cleaned(val title: String, val artist: String)

    fun clean(title: String, artist: String): Cleaned {
        var t = RELEASE_SUFFIX.replace(NOISE.replace(title, ""), "")
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
