package com.opentune.data.lyrics

import com.opentune.data.Http
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Better Lyrics' lyrics service: word-by-word TTML for songs someone has
 * already looked up. Anything it hasn't seen needs the extension's own API
 * key, which isn't ours to use, so those come back empty and the other
 * sources answer.
 *
 * Its Portato route answers the same query from QQ Music's karaoke files,
 * timed word by word too, as the web player asks it.
 */
internal object BetterLyrics {
    private const val API = "https://lyrics-api.boidu.dev/getLyrics"
    /** Better Lyrics' Portato: QQ Music's karaoke timings, by the same query. */
    private const val PORTATO = "https://lyrics-api.boidu.dev/qq/getLyrics"
    const val PORTATO_SOURCE = "Better Lyrics Portato"
    private const val USER_AGENT = "OpenTune (+https://github.com/justshivv/Opentune)"
    /** A recording more than this much longer or shorter than the track is another version. */
    private const val TOLERANCE_MS = 4_000L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        return get(API, cleaned, durationMs)?.let { parse(it, durationMs) }
    }

    /** The Portato answer for the song: QQ Music's QRC, read word by word (see [Qrc]). */
    suspend fun portato(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        return get(PORTATO, cleaned, durationMs)?.let { parsePortato(it, durationMs, cleaned.title, cleaned.artist) }
    }

    private suspend fun get(endpoint: String, cleaned: TrackNameCleaner.Cleaned, durationMs: Long): String? {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("s", cleaned.title)
            .addQueryParameter("a", cleaned.artist)
            .apply { if (durationMs > 0) addQueryParameter("d", (durationMs / 1000).toString()) }
            .build()
        return runInterruptible(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use {
                if (!it.isSuccessful) null else it.body?.string()
            }
        }
    }

    internal fun parse(body: String, durationMs: Long): Lyrics? {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val ttml = (obj["ttml"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        // The recording's own length, from <body dur>: another version's timing would be off from the first line.
        val length = Regex("""<body\b[^>]*\bdur="([^"]+)"""").find(ttml)?.groupValues?.get(1)?.let(Ttml::time)
        if (durationMs > 0 && length != null && length > 0 && abs(length - durationMs) > TOLERANCE_MS) return null
        val lines = Ttml.parse(ttml)
        if (lines.isEmpty()) return null
        return Lyrics.Synced(lines, "Better Lyrics", recordingMs = length?.takeIf { it > 0 } ?: 0)
    }

    /**
     * A Portato answer: `{"lyrics":"<QrcInfos…>","provider":"qq"}`, or TTML as the main
     * service sends. QQ's files open with a "Title - Artist" line and the credits, stamped
     * like lyrics; they're dropped as KuGou's are.
     */
    internal fun parsePortato(body: String, durationMs: Long, title: String, artist: String): Lyrics? {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (obj["ttml"] != null) return (parse(body, durationMs) as? Lyrics.Synced)?.copy(source = PORTATO_SOURCE)
        val raw = (obj["lyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val lines = KuGou.clean(Qrc.parse(raw), title, artist)
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, PORTATO_SOURCE)
    }
}

/**
 * QQ Music's QRC karaoke lyrics (and NetEase's YRC, which puts each stamp before its word):
 * `[lineStart,lineLength]word(start,length)word(start,length)…`, all in ms, inside a
 * `<QrcInfos>` document's `LyricContent` attribute or on their own.
 */
internal object Qrc {
    private val LINE = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val SUFFIXED = Regex("""([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)""")
    private val PREFIXED = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
    private val STAMP = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private const val CONTENT = "LyricContent=\""

    fun parse(raw: String): List<LyricLine> {
        val text = content(raw)
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return text.lineSequence().mapNotNull { row ->
            val m = LINE.find(row.trim()) ?: return@mapNotNull null
            val start = (m.groupValues[1].toLong() - offset).coerceAtLeast(0)
            val length = m.groupValues[2].toLong()
            val body = m.groupValues[3]
            val suffixed = words(SUFFIXED.findAll(body).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }, offset)
            val prefixed = words(PREFIXED.findAll(body).map { Triple(it.groupValues[3], it.groupValues[1], it.groupValues[2]) }, offset)
            // Read in the other format, a line loses a word at one end: the longer reading is right.
            val words = if (prefixed.sumOf { it.text.length } > suffixed.sumOf { it.text.length }) prefixed else suffixed
            val lineText = (if (words.isNotEmpty()) words.joinToString("") { it.text } else STAMP.replace(body, ""))
                .replace(Regex("\\s+"), " ").trim()
            if (lineText.isEmpty()) return@mapNotNull null
            val end = if (length > 0) start + length else words.lastOrNull()?.endMs ?: (start + 4_000)
            if (words.size >= 2) LyricLine(minOf(start, words.first().startMs), end, lineText, words, wordSynced = true)
            else LyricLine(start, end, lineText, LrcParser.spreadWords(lineText, start, end), wordSynced = false)
        }.sortedBy { it.startMs }.toList()
    }

    /**
     * One line's words from its (text, start, length) pieces. A piece that's only a space
     * (QQ times the spaces too) goes onto the word before it, the way the other parsers keep
     * a word's trailing space.
     */
    private fun words(pieces: Sequence<Triple<String, String, String>>, offset: Long): List<LyricWord> {
        val out = mutableListOf<LyricWord>()
        for ((text, at, length) in pieces) {
            if (text.isEmpty()) continue
            if (text.isBlank()) {
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().let { it.copy(text = it.text.trimEnd() + " ") }
                continue
            }
            val start = (at.toLong() - offset).coerceAtLeast(0)
            out += LyricWord(text, start, start + length.toLong())
        }
        return out
    }

    /**
     * The lyrics inside a `<QrcInfos>` document. Quotes in them are often left unescaped
     * (a nickname in quotes in the credits), so the value runs to the document's last
     * `"/>`, not to its next quote.
     */
    internal fun content(raw: String): String {
        val at = raw.indexOf(CONTENT)
        if (at < 0) return raw
        val from = at + CONTENT.length
        val to = raw.lastIndexOf("\"/>").takeIf { it >= from } ?: raw.lastIndexOf('"').takeIf { it >= from } ?: raw.length
        return raw.substring(from, to)
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
