package com.opentune.data.lyrics

import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.PlayerClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

/**
 * Lyrics from a music video's own captions, the way Better Lyrics finds
 * them: many official videos carry hand-made subtitles timed line by line
 * to the video. Only those are used, never YouTube's automatic ones, and
 * only in the song's own language (the one its automatic captions are in).
 * They're timed to that video, so they fit when the video is what's playing.
 */
object YouTubeCaptions {
    private val NOTES = setOf('♪', '♫', '♬', '♩')

    suspend fun lookup(videoId: String, durationMs: Long = 0): Lyrics? {
        val player = Innertube.player(videoId, PlayerClient.ANDROID_VR)
        val tracks = ((((player["captions"] as? JsonObject)?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks")) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val track = pick(tracks) ?: return null
        val url = (track["baseUrl"] as? JsonPrimitive)?.contentOrNull ?: return null
        val body = Http.client.newCall(Request.Builder().url(url).header("User-Agent", PlayerClient.ANDROID_VR.userAgent).build()).execute().use {
            if (!it.isSuccessful) return null
            it.body?.string().orEmpty()
        }
        val lines = parse(body)
        // Timed to this very video, so as long as the track by definition.
        return if (looksLikeLyrics(lines, durationMs)) Lyrics.Synced(lines, "YouTube captions", recordingMs = durationMs.coerceAtLeast(0)) else null
    }

    /**
     * Subtitles that read like a song's words rather than a skit, a
     * narration or a few credits: enough short lines, spread over a good
     * part of the song.
     */
    internal fun looksLikeLyrics(lines: List<LyricLine>, durationMs: Long): Boolean {
        if (lines.size < 6) return false
        if (lines.sumOf { it.text.length } / lines.size > 80) return false
        if (durationMs > 0 && lines.last().startMs - lines.first().startMs < durationMs * 0.3) return false
        return true
    }

    /** Lines that credit or point somewhere instead of being sung: "Subtitles by …", "Translated by …", a web address. */
    private val CREDIT = Regex("""\b(?:subtitle[sd]?|captions?|captioned|transcri\w*|translat\w*|lyrics)\s*(?:by|:)|amara\.org|https?://|www\.|©""", RegexOption.IGNORE_CASE)

    /** A hand-made track in the song's language: the one that matches the automatic track, or the only one there is. */
    internal fun pick(tracks: List<JsonObject>): JsonObject? {
        fun kind(t: JsonObject) = (t["kind"] as? JsonPrimitive)?.contentOrNull
        fun lang(t: JsonObject) = (t["languageCode"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val manual = tracks.filter { kind(it) != "asr" }
        if (manual.isEmpty()) return null
        val spoken = tracks.firstOrNull { kind(it) == "asr" }?.let(::lang)
        if (spoken != null) {
            return manual.firstOrNull { lang(it) == spoken } ?: manual.firstOrNull { lang(it).substringBefore('-') == spoken.substringBefore('-') }
        }
        return manual.singleOrNull()
    }

    /** Reads YouTube's timed text, in its XML (`<p t= d=>`) or JSON (`events`) form. */
    internal fun parse(body: String): List<LyricLine> {
        val raw = mutableListOf<Triple<Long, Long, String>>()
        if (body.trimStart().startsWith("{")) {
            Regex(""""tStartMs"\s*:\s*(\d+)\s*,\s*"dDurationMs"\s*:\s*(\d+)[^}]*?"segs"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
                val text = Regex(""""utf8"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(m.groupValues[3]).joinToString("") { unescapeJson(it.groupValues[1]) }
                raw += Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), text)
            }
        } else {
            Regex("""<p\s+t="(\d+)"\s+d="(\d+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
                raw += Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), unescapeXml(m.groupValues[3].replace(Regex("<[^>]+>"), "")))
            }
        }
        val cleaned = raw.mapNotNull { (start, dur, text) ->
            var t = text.replace('\n', ' ').trim()
            // "♪ We're no strangers ♪" and bracketed cues like "[♪♪♪]" or "[Music]".
            t = t.trim { it.isWhitespace() || it in NOTES }
            if (t.startsWith("[") && t.endsWith("]") || t.startsWith("(") && t.endsWith(")")) return@mapNotNull null
            if (CREDIT.containsMatchIn(t)) return@mapNotNull null
            t = t.replace(Regex("\\s+"), " ")
            if (t.isEmpty()) null else Triple(start, dur, t)
        }
        // Captions in capitals throughout read better in sentence case.
        val shouting = cleaned.isNotEmpty() && cleaned.all { it.third == it.third.uppercase() }
        return cleaned.map { (start, dur, t) ->
            val text = if (shouting) t.lowercase().replaceFirstChar { it.uppercase() } else t
            val end = start + dur
            LyricLine(start, end, text, LrcParser.spreadWords(text, start, end), wordSynced = false)
        }
    }

    private fun unescapeXml(s: String) = s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun unescapeJson(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\u0026", "&").replace("\\\\", "\\")
}

/**
 * Better Lyrics' Unison: a community database of lyrics people have
 * written and timed, voted on by its users, looked up by video id. Entries
 * hidden by votes or marked low-confidence without any votes are passed
 * over. Lines can be timed word by word (TTML or enhanced LRC).
 */
object Unison {
    fun lookup(videoId: String, title: String, artist: String, durationMs: Long): Lyrics? {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("unison.betterlyrics.org").addPathSegment("lyrics")
            .addQueryParameter("v", videoId)
            .addQueryParameter("song", title)
            .addQueryParameter("artist", artist)
            .apply { if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000).toString()) }
            .build()
        val text = Http.client.newCall(Request.Builder().url(url).header("User-Agent", "OpenTune (+https://github.com/justshivv/Opentune)").build()).execute().use {
            if (it.code == 404 || !it.isSuccessful) return null
            it.body?.string().orEmpty()
        }
        return parse(text, durationMs)
    }

    internal fun parse(json: String, durationMs: Long): Lyrics? {
        val data = (kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject)?.get("data") as? JsonObject ?: return null
        fun str(k: String) = (data[k] as? JsonPrimitive)?.contentOrNull
        fun num(k: String) = (data[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
        if (str("hidden") == "true") return null
        if (str("confidence") == "low" && num("effectiveScore") <= 0 && num("voteCount") <= 0) return null
        val body = str("lyrics")?.takeIf { it.isNotBlank() } ?: return null
        return when (str("format")) {
            "lrc" -> LrcParser.parse(body, durationMs).takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it, "Unison") }
            "ttml" -> Ttml.parse(body).takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it, "Unison") }
            "plain" -> Lyrics.Plain(body.trim(), "Unison")
            else -> null
        }
    }
}

/** The TTML timed-lyrics format (as Apple Music writes it): `<p begin end>` lines, `<span begin end>` words. */
internal object Ttml {
    fun parse(xml: String): List<LyricLine> {
        val voices = voicesOf(xml)
        // Direction is inherited: from the document, then each <div>, then the <p> itself.
        val doc = directionOf(Regex("""<(?:tt|body)\b([^>]*)>""").findAll(xml).map { it.groupValues[1] }.joinToString(" "))
        val divs = Regex("""<div\b([^>]*)>""").findAll(xml).map { it.range.first to directionOf(it.groupValues[1]) }.toList()
        return Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { p ->
            val rtl = directionOf(p.groupValues[1]) ?: divs.lastOrNull { it.first < p.range.first }?.second ?: doc
            val start = time(attr(p.groupValues[1], "begin")) ?: return@mapNotNull null
            val end = time(attr(p.groupValues[1], "end")) ?: start + 4_000
            // Backing vocals come wrapped in a span of their own; they're shown under the line.
            var background: String? = null
            val inner = BACKING.replace(p.groupValues[2]) { m ->
                val said = unescape(m.groupValues[1].replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
                if (said.isNotEmpty()) background = listOfNotNull(background, said).joinToString(" ")
                ""
            }
            val voice = voices[attr(p.groupValues[1], "ttm:agent")] ?: VOICE_LEAD
            val spans = Regex("""<span\b([^>]*)>([^<]*)</span>(\s*)""").findAll(inner).mapNotNull { s ->
                val b = time(attr(s.groupValues[1], "begin")) ?: return@mapNotNull null
                val e = time(attr(s.groupValues[1], "end")) ?: b
                LyricWord(unescape(s.groupValues[2]) + s.groupValues[3].ifEmpty { "" }, b, e)
            }.filter { it.text.isNotBlank() }.toList()
            val text = if (spans.isNotEmpty()) spans.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
            else unescape(inner.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return@mapNotNull null
            if (spans.size >= 2) LyricLine(start, end, text, spans, wordSynced = true, voice = voice, background = background, rtl = rtl)
            else LyricLine(start, end, text, LrcParser.spreadWords(text, start, end), wordSynced = false, voice = voice, background = background, rtl = rtl)
        }.toList()
    }

    private val BACKING = Regex("""<span\b[^>]*ttm:role="x-bg"[^>]*>((?:\s*<span\b[^>]*>[^<]*</span>\s*)*)</span>""")

    /** The singers declared in the head (`<ttm:agent type xml:id>`), mapped as [LyricsPlus.voicesOf] does. */
    private fun voicesOf(xml: String): Map<String, Int> {
        var person = 0
        return Regex("""<ttm:agent\b([^>]*)>""").findAll(xml).mapNotNull { m ->
            val id = attr(m.groupValues[1], "xml:id") ?: return@mapNotNull null
            id to if (attr(m.groupValues[1], "type") == "group") VOICE_TOGETHER else if (person++ % 2 == 0) VOICE_LEAD else VOICE_SECOND
        }.toMap()
    }

    /** True for `tts:direction="rtl"` or a right-to-left `xml:lang`, false for `ltr`, null when neither says. */
    internal fun directionOf(attrs: String): Boolean? {
        when (attr(attrs, "tts:direction")) {
            "rtl" -> return true
            "ltr" -> return false
        }
        val lang = attr(attrs, "xml:lang")?.substringBefore('-')?.lowercase() ?: return null
        return lang in RTL_LANGUAGES
    }

    private val RTL_LANGUAGES = setOf("ar", "he", "iw", "fa", "ur", "yi", "ps", "sd", "ug", "dv", "ku", "ckb")

    private fun attr(attrs: String, name: String) = Regex("""\b$name="([^"]+)"""").find(attrs)?.groupValues?.get(1)

    /** "1:02.345", "00:01:02.345", "62.345s" or "62345ms". */
    internal fun time(t: String?): Long? {
        t ?: return null
        if (t.endsWith("ms")) return t.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (t.endsWith("s")) return t.removeSuffix("s").toDoubleOrNull()?.times(1000)?.toLong()
        val parts = t.split(':')
        val sec = parts.last().toDoubleOrNull() ?: return null
        val min = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0
        val hr = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0
        return ((hr * 3600 + min * 60) * 1000) + (sec * 1000).toLong()
    }

    private fun unescape(s: String) = s.replace("&apos;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
