package com.opentune.data.lyrics

import com.opentune.data.settings.LyricsSource
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * What every source's answer goes through before it can be shown: tidying
 * (gap markers, doubled lines, ends that run into the next line, words with
 * no end), checks that it belongs to this recording, the choice between the
 * answers that pass, and a timing shift when the other sources agree that
 * the chosen one runs early or late.
 */
internal object LyricsQuality {
    /** One source's answer, [order] being the source's place in Settings › Lyrics sources. */
    class Candidate(val order: Int, val source: LyricsSource, val lyrics: Lyrics)

    /** Lines starting this close together start at the same moment. */
    private const val SAME_MS = 30L
    /** The same words again this soon are the line written down twice. */
    private const val REPEAT_MS = 300L
    /** A line-synced line with no end and nothing after it lasts this long. */
    private const val LONE_LINE_MS = 4_000L
    /** A word with no end lasts at most this long. */
    private const val WORD_FILL_MS = 2_000L

    /** Further than this from the track's length, the source timed another recording. */
    private const val RECORDING_TOLERANCE_MS = 8_000L
    /** A last line starting this far past the end is timed for a longer cut. */
    private const val LATE_END_MS = 1_500L
    /** A first line later than this share of the song isn't this song's. */
    private const val LATE_START_SHARE = 0.6

    /** A source whose recording is this close to the track's length is timed to the same cut. */
    private const val ANCHOR_MS = 1_500L
    /** Shifts smaller than this are the usual disagreement between sources, not an offset. */
    private const val MIN_SHIFT_MS = 700L
    private const val MAX_SHIFT_MS = 10_000L
    /** Matched lines may differ from the typical shift by this much and still count as a constant one. */
    private const val MAX_SPREAD_MS = 350L
    private const val MIN_MATCHED = 5

    // ---- Tidying ----

    fun tidy(lyrics: Lyrics, durationMs: Long): Lyrics = when (lyrics) {
        is Lyrics.Plain -> lyrics.copy(text = tidyPlain(lyrics.text))
        is Lyrics.Synced -> lyrics.copy(lines = tidy(lyrics.lines, durationMs))
    }

    /** Plain text without stray LRC timestamps (some sources leave them in) and with at most one blank line in a row. */
    internal fun tidyPlain(text: String): String =
        text.lines().joinToString("\n") { it.replace(TIME_TAG, "").replace(SPACES, " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim()

    /**
     * Lines in time order, with gap markers ("♪", "[Instrumental]") taken
     * out, a line written down twice kept once, a second text for the same
     * moment (often a translation) put under the line instead of replacing
     * it, line-synced lines ended where the next one starts, and words with
     * no end given one.
     */
    fun tidy(lines: List<LyricLine>, durationMs: Long): List<LyricLine> {
        val sorted = lines.sortedBy { it.startMs }
        val out = ArrayList<LyricLine>(sorted.size)
        for ((i, raw) in sorted.withIndex()) {
            val text = raw.text.replace(SPACES, " ").trim()
            if (isCue(text)) continue
            // Where this line has to give way: the next line starting later, gap markers included.
            var next: Long? = null
            for (j in i + 1 until sorted.size) if (sorted[j].startMs > raw.startMs + SAME_MS) { next = sorted[j].startMs; break }
            val prev = out.lastOrNull()
            if (prev != null && raw.startMs - prev.startMs <= SAME_MS) {
                if (key(prev.text) == key(text)) continue
                if (!prev.wordSynced && !raw.wordSynced) {
                    out[out.lastIndex] = prev.copy(background = listOfNotNull(prev.background, text).joinToString(" "))
                    continue
                }
            }
            if (prev != null && key(prev.text) == key(text) && raw.startMs - prev.startMs < REPEAT_MS) continue
            out += timed(raw.copy(text = text), next, durationMs)
        }
        return out
    }

    private fun timed(line: LyricLine, next: Long?, durationMs: Long): LyricLine {
        val start = line.startMs
        if (line.wordSynced && line.words.isNotEmpty()) {
            val lineEnd = if (line.endMs > start) line.endMs else next ?: (start + LONE_LINE_MS)
            val words = fillWordEnds(line.words, lineEnd)
            // Duets and backing vocals overlap on purpose; only the line's own span is fixed.
            return line.copy(
                startMs = minOf(start, words.first().startMs),
                endMs = maxOf(lineEnd, words.last().endMs),
                words = words,
            )
        }
        var end = line.endMs
        if (next != null && (end > next || end <= start)) end = next
        if (end <= start) end = start + LONE_LINE_MS
        if (durationMs > start && end > durationMs) end = durationMs
        if (end == line.endMs && line.words.isNotEmpty()) return line
        return line.copy(endMs = end, words = LrcParser.spreadWords(line.text, start, end), wordSynced = false)
    }

    /** A word with no end (or one before its start) ends where the next starts, or the line ends, within [WORD_FILL_MS]. */
    internal fun fillWordEnds(words: List<LyricWord>, lineEnd: Long): List<LyricWord> = words.mapIndexed { i, w ->
        if (w.endMs > w.startMs) return@mapIndexed w
        val next = words.getOrNull(i + 1)?.startMs?.takeIf { it > w.startMs }
        val until = next ?: lineEnd.takeIf { it > w.startMs } ?: (w.startMs + 250)
        w.copy(endMs = minOf(until, w.startMs + WORD_FILL_MS))
    }

    private val CUE = Regex(
        """(?:instrumental(?: break)?|music(?: playing)?|intro|outro|interlude|(?:guitar |piano |sax |drum )?solo|applause|instrumental music)""",
        RegexOption.IGNORE_CASE,
    )

    /** A line that marks a gap or names a part rather than being sung: "♪", "...", "[Chorus]", "(Instrumental)". */
    internal fun isCue(text: String): Boolean {
        if (text.none { it.isLetterOrDigit() }) return true
        if (text.startsWith("[") && text.endsWith("]")) return true
        return text.startsWith("(") && text.endsWith(")") && CUE.matches(text.substring(1, text.length - 1).trim())
    }

    // ---- Checks ----

    private val PLACEHOLDER = Regex(
        """^(?:instrumental|this (?:song|track) is (?:an )?instrumental|no lyrics(?: available| found)?|lyrics (?:are )?not (?:available|found)|(?:sorry,? )?we (?:don'?t|do not) have (?:the )?lyrics.*|lyrics (?:will be|coming)(?: available)? soon|纯音乐[，,]?\s*请欣赏|此歌曲为没有填词的纯音乐[，,]?\s*请您?欣赏|暂无歌词|没有歌词|纯音乐)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Text that stands in for lyrics: "Instrumental", "No lyrics available", NetEase's "纯音乐，请欣赏". */
    internal fun isPlaceholder(text: String): Boolean =
        PLACEHOLDER.matches(text.trim { !it.isLetterOrDigit() && it != '\'' }.trim())

    /** Every line at (nearly) the same time: an unsynced text dressed up as LRC. */
    internal fun untimed(lyrics: Lyrics.Synced): Boolean {
        val lines = lyrics.lines
        return lines.size >= 4 && lines.map { it.startMs / 100 }.distinct().size * 2 < lines.size
    }

    /**
     * Why these (tidied) lyrics can't be this recording's, or null when they
     * can. [durationMs] is the track's length, 0 when unknown.
     */
    fun problem(lyrics: Lyrics, durationMs: Long): String? = when (lyrics) {
        is Lyrics.Plain -> {
            val lines = lyrics.text.lines().filter { it.isNotBlank() }
            when {
                lines.isEmpty() -> "No words in it"
                lines.all(::isPlaceholder) -> "Only says the song has no lyrics"
                else -> null
            }
        }
        is Lyrics.Synced -> {
            val lines = lyrics.lines
            when {
                lines.isEmpty() -> "No words in it"
                lines.count { isPlaceholder(it.text) } * 2 >= lines.size -> "Only says the song has no lyrics"
                durationMs <= 0 -> null
                lyrics.recordingMs > 0 && abs(lyrics.recordingMs - durationMs) > RECORDING_TOLERANCE_MS -> "Timed for another version of the song"
                lines.last().startMs > durationMs + LATE_END_MS -> "Runs past the end of the song"
                lines.first().startMs > durationMs * LATE_START_SHARE -> "Starts too late to fit the song"
                else -> null
            }
        }
    }

    // ---- Choosing ----

    /**
     * The answer to show: word by word over line by line over plain (no
     * higher than [target], so a line-synced answer earlier in the order
     * still wins when word sync isn't asked for), then the user's order.
     * An answer whose words have little in common with what the other
     * sources agree on is another song, or a translation, and is left out.
     */
    fun choose(candidates: List<Candidate>, target: Int): Candidate? =
        candidates.filterNot { outlier(it, candidates) }
            .minWithOrNull(compareByDescending<Candidate> { minOf(LyricsRepository.rank(it.lyrics), target) }.thenBy { it.order })

    private const val MIN_TOKENS = 12

    internal fun outlier(candidate: Candidate, all: List<Candidate>): Boolean {
        val mine = tokens(candidate.lyrics)
        if (mine.size < MIN_TOKENS) return false
        val latin = isLatin(mine)
        val rest = all.filter { it !== candidate }.map { tokens(it.lyrics) }.filter { it.size >= MIN_TOKENS }
        val others = rest.filter { isLatin(it) == latin }
        // English, where the song's words come from elsewhere in another script (Gurmukhi,
        // Devanagari, Hangul…): a translation, not what's sung. Captions need only one such
        // source to say so, others two that agree; English that a source in its own script
        // shares stays, and so do romanized lyrics, which don't read as English.
        val foreign = rest.filter { isLatin(it) != latin }
        val foreignAgree = foreign.indices.any { i -> (i + 1 until foreign.size).any { j -> overlap(foreign[i], foreign[j]) >= 0.5 } }
        if (latin && readsEnglish(mine) && others.none { overlap(mine, it) >= 0.25 } &&
            (foreignAgree || (candidate.source == LyricsSource.YOUTUBE_CAPTIONS && foreign.isNotEmpty()))) return true
        if (others.isEmpty()) return false
        val fits = others.map { overlap(mine, it) }
        // A video's subtitles are often a translation, a skit or credits: they must agree with someone.
        if (candidate.source == LyricsSource.YOUTUBE_CAPTIONS) return fits.max() < 0.25
        val othersAgree = others.indices.any { i -> (i + 1 until others.size).any { j -> overlap(others[i], others[j]) >= 0.5 } }
        return othersAgree && fits.all { it < 0.2 }
    }

    /** The words of [lyrics] (single characters for Chinese, Japanese and Korean), lower case. */
    internal fun tokens(lyrics: Lyrics): Set<String> {
        val text = when (lyrics) {
            is Lyrics.Plain -> lyrics.text
            is Lyrics.Synced -> lyrics.lines.joinToString("\n") { it.text }
        }
        val out = HashSet<String>()
        val word = StringBuilder()
        fun flush() { if (word.length >= 2) out += word.toString(); word.clear() }
        for (c in text.lowercase(Locale.ROOT)) {
            when {
                isCjk(c) -> { flush(); out += c.toString() }
                // Vowel signs (ा, ੈ, ं…) are marks, not letters: counted as breaks, they cut every
                // Hindi or Punjabi word into pieces and no two sources of a song looked alike.
                c.isLetterOrDigit() || c == '\'' || isMark(c) -> word.append(c)
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** Shared words over the smaller set's size: 1 when one text is inside the other. */
    internal fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        return small.count { it in large }.toDouble() / small.size
    }

    private fun isLatin(tokens: Set<String>) = tokens.count { it.first().code < 0x250 } * 2 >= tokens.size

    /** Whether [tokens] read as English: enough of English's commonest little words among them. */
    internal fun readsEnglish(tokens: Set<String>): Boolean {
        val common = tokens.count { it in ENGLISH }
        return common >= 6 && common * 100 >= tokens.size * 12
    }

    private val ENGLISH = setOf(
        "the", "you", "and", "to", "my", "me", "it", "that", "of", "in", "is", "on", "for", "be", "your",
        "all", "can", "just", "what", "with", "this", "so", "but", "we", "they", "don't", "i'm", "it's",
        "know", "like", "when", "never", "want", "love", "got", "are", "was", "how", "if", "now", "baby",
    )

    private fun isMark(c: Char): Boolean = Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
    }

    private fun isCjk(c: Char): Boolean = Character.isIdeographic(c.code) ||
        Character.UnicodeBlock.of(c).let { it == Character.UnicodeBlock.HIRAGANA || it == Character.UnicodeBlock.KATAKANA || it == Character.UnicodeBlock.HANGUL_SYLLABLES }

    // ---- Timing ----

    /**
     * How much later [reference] puts the lines both share than [target]
     * does, when that's a constant shift: at least [MIN_MATCHED] lines
     * matched in order, nearly all within [MAX_SPREAD_MS] of the middle
     * value. Null when the two don't line up well enough to say.
     */
    internal fun shiftBetween(target: Lyrics.Synced, reference: Lyrics.Synced): Long? {
        val diffs = matchedDiffs(target, reference) ?: return null
        val middle = median(diffs)
        return middle.takeIf { median(diffs.map { abs(it - middle) }) <= MAX_SPREAD_MS }
    }

    /** How much later [reference] puts each line both share, matched in order; null when too few match to say. */
    private fun matchedDiffs(target: Lyrics.Synced, reference: Lyrics.Synced): List<Long>? {
        val ref = reference.lines.map { key(it.text) to it.startMs }
        val diffs = mutableListOf<Long>()
        var from = 0
        for (line in target.lines) {
            val k = key(line.text)
            if (k.length < 4) continue
            // In order, and only a few lines ahead, so a chorus matches its own repeat.
            val hit = (from until minOf(ref.size, from + 8)).firstOrNull { ref[it].first == k } ?: continue
            diffs += ref[hit].second - line.startMs
            from = hit + 1
        }
        return diffs.takeIf { it.size >= MIN_MATCHED && it.size * 4 >= target.lines.size }
    }

    /**
     * How unevenly [reference] times the lines it shares with [target], once
     * any steady shift between them is taken out: the typical distance of a
     * line from that shift. Null when too few lines match to say.
     */
    internal fun spreadAgainst(target: Lyrics.Synced, reference: Lyrics.Synced): Long? {
        val diffs = matchedDiffs(target, reference) ?: return null
        val middle = median(diffs)
        return median(diffs.map { abs(it - middle) })
    }

    /**
     * Whether [chosen]'s word timings can be trusted word by word on this
     * track. Not when its recording is a little off this one's length (the
     * lines may fit, the words drift), when many of its lines have words out
     * of order or spread evenly (made up from the line, not timed), or when
     * every other source timed to this cut places its lines unevenly against
     * these. Lit a line at a time instead, an error is far harder to see.
     */
    fun wordsTrusted(chosen: Lyrics.Synced, references: List<Lyrics.Synced>, durationMs: Long): Boolean {
        if (durationMs > 0 && chosen.recordingMs > 0 && abs(chosen.recordingMs - durationMs) > WORD_CUT_MS) return false
        val worded = chosen.lines.filter { it.wordSynced && it.words.size >= 2 }
        if (worded.isEmpty()) return true
        if (worded.count(::wordsShaky) * 10 > worded.size * 3) return false
        val sameCut = references.filter { durationMs <= 0 || it.recordingMs <= 0 || abs(it.recordingMs - durationMs) <= ANCHOR_MS }
        val spreads = sameCut.mapNotNull { spreadAgainst(chosen, it) }
        return spreads.isEmpty() || spreads.any { it <= WORD_SPREAD_MS }
    }

    /** Words out of order, one held impossibly long mid-line, or all the same length: not really timed. */
    private fun wordsShaky(line: LyricLine): Boolean {
        val w = line.words
        if (w.zipWithNext().any { (a, b) -> b.startMs < a.startMs - OUT_OF_ORDER_MS }) return true
        if (w.dropLast(1).any { it.endMs - it.startMs > LONG_WORD_MS }) return true
        if (w.size >= 3) {
            val lengths = w.map { it.endMs - it.startMs }
            if (lengths.max() - lengths.min() <= EVEN_MS) return true
        }
        return false
    }

    /** [lyrics] lit a line at a time: each word-timed line keeps its own start and end, its words spread across them. */
    fun asLines(lyrics: Lyrics.Synced): Lyrics.Synced = lyrics.copy(
        lines = lyrics.lines.map { l ->
            if (!l.wordSynced) l else l.copy(words = LrcParser.spreadWords(l.text, l.startMs, l.endMs), wordSynced = false)
        },
    )

    /** A recording further than this from the track's length has lines that fit and words that drift. */
    private const val WORD_CUT_MS = 2_000L
    /** Lines placed further than this from the steady shift, typically, aren't timed closely enough for words. */
    private const val WORD_SPREAD_MS = 450L
    private const val OUT_OF_ORDER_MS = 50L
    private const val LONG_WORD_MS = 5_000L
    /** Word lengths all within this of each other: spread from the line, not timed. */
    private const val EVEN_MS = 15L

    /**
     * The offset to play [chosen] at (added to the position, as the
     * per-song offset is), from the other sources' timing: only those timed
     * to a recording as long as this track count, and when [chosen] is too,
     * two of them have to agree before it's moved. 0 when nothing's clear.
     */
    fun autoOffset(chosen: Lyrics.Synced, references: List<Lyrics.Synced>, durationMs: Long): Long {
        if (durationMs <= 0) return 0
        fun anchored(l: Lyrics.Synced) = l.recordingMs > 0 && abs(l.recordingMs - durationMs) <= ANCHOR_MS
        val votes = references.filter(::anchored).mapNotNull { shiftBetween(chosen, it) }
        if (votes.isEmpty()) return 0
        val shift = median(votes)
        if (abs(shift) < MIN_SHIFT_MS || abs(shift) > MAX_SHIFT_MS) return 0
        val agreeing = votes.count { abs(it - shift) <= 400 }
        if (agreeing * 2 <= votes.size || agreeing < if (anchored(chosen)) 2 else 1) return 0
        // Lines that should come [shift] later: the clock runs that much behind.
        return -shift
    }

    private fun median(values: List<Long>): Long {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    // ---- Names ----

    /** Whether a source's title [got] is the song asked for as [want]; titles in different scripts can't be told apart and pass. */
    fun sameTitle(want: String, got: String?): Boolean {
        if (got == null) return true
        val a = titleKey(want)
        val b = titleKey(got)
        if (a.isEmpty() || b.isEmpty() || a.contains(b) || b.contains(a)) return true
        if (latinText(a) != latinText(b)) return true
        val wa = words(want)
        val wb = words(got)
        if (wa.isEmpty() || wb.isEmpty()) return true
        return overlap(wa, wb) >= 0.5
    }

    /** Whether one of the artists in [got] is [want], loosely; names in different scripts pass. */
    fun sameArtist(want: String, got: String?): Boolean {
        if (got == null) return true
        val a = looseKey(want)
        if (a.length < 2) return true
        val parts = got.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|\bx\b|\band\b|、)\s*""", RegexOption.IGNORE_CASE)).map(::looseKey).filter { it.length >= 2 }
        if (parts.isEmpty()) return true
        if (parts.any { it.contains(a) || a.contains(it) }) return true
        return parts.none { latinText(it) == latinText(a) }
    }

    private fun titleKey(s: String) = looseKey(s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+(?:feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE), ""))

    private fun words(s: String) = looseText(s).split(' ').filter { it.length >= 2 }.toSet()

    private fun looseText(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()

    private fun looseKey(s: String) = looseText(s).filter { it.isLetterOrDigit() }

    private fun latinText(s: String) = s.count { it.code < 0x250 } * 2 >= s.length

    private fun key(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val TIME_TAG = Regex("""[\[<]\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?[\]>]""")
    private val SPACES = Regex("\\s+")
}
