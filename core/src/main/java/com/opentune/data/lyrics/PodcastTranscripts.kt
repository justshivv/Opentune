package com.opentune.data.lyrics

import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.PlayerClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request

/**
 * What's said in a podcast episode, shown where a song's lyrics would be.
 * The song lyrics sources have nothing for an episode, but its video's
 * captions do: hand-made ones in the language spoken when there are any,
 * else YouTube's automatic ones, which nearly every episode has and which
 * time each word. Those come a few words at a time; they're put back
 * together into sentences.
 */
object PodcastTranscripts {
    const val SOURCE = "Transcript"
    const val SOURCE_AUTO = "Transcript · auto-generated"

    /** A sentence ends here once a line has this many characters. */
    private const val MIN_CHARS = 20
    /** No line longer than this, sentence or not. */
    private const val MAX_CHARS = 90
    /** A silence this long starts a new line. */
    private const val PAUSE_MS = 1_500L

    suspend fun lookup(videoId: String, durationMs: Long = 0): Lyrics? {
        val player = Innertube.player(videoId, PlayerClient.ANDROID_VR)
        val renderer = (player["captions"] as? JsonObject)?.get("playerCaptionsTracklistRenderer") as? JsonObject ?: return null
        val track = pick(renderer) ?: return null
        val url = (track["baseUrl"] as? JsonPrimitive)?.contentOrNull ?: return null
        val body = Http.client.newCall(Request.Builder().url(url).header("User-Agent", PlayerClient.ANDROID_VR.userAgent).build()).execute().use {
            if (!it.isSuccessful) return null
            it.body?.string().orEmpty()
        }
        val lines = lines(body)
        if (lines.isEmpty()) return null
        return Lyrics.Synced(lines, if (kind(track) == "asr") SOURCE_AUTO else SOURCE, recordingMs = durationMs.coerceAtLeast(0))
    }

    /**
     * The captions to read: hand-made ones in the language spoken, else the automatic ones of
     * that language. YouTube names the spoken one: the first caption track of the video's
     * own sound (an episode can list automatic tracks in several languages, the spoken one
     * not first).
     */
    internal fun pick(renderer: JsonObject): JsonObject? {
        val tracks = (renderer["captionTracks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (tracks.isEmpty()) return null
        val audio = (renderer["audioTracks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val main = audio.getOrNull((renderer["defaultAudioTrackIndex"] as? JsonPrimitive)?.intOrNull ?: 0) ?: audio.firstOrNull()
        val spokenIndex = ((main?.get("captionTrackIndices") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.intOrNull
        val spokenTrack = spokenIndex?.let { tracks.getOrNull(it) } ?: tracks.firstOrNull { kind(it) == "asr" }
        val manual = tracks.filter { kind(it) != "asr" }
        val spoken = spokenTrack?.let(::lang)
        if (spoken != null) {
            manual.firstOrNull { lang(it) == spoken }?.let { return it }
            manual.firstOrNull { lang(it).substringBefore('-') == spoken.substringBefore('-') }?.let { return it }
        }
        return spokenTrack ?: manual.firstOrNull()
    }

    /** A piece of the captions: a word of the automatic ones, a caption of hand-made ones. */
    private class Piece(val startMs: Long, var endMs: Long, val text: String)

    /** YouTube's timed text (`<p t d>`, with `<s t>` words in automatic captions) as lines of whole sentences. */
    internal fun lines(body: String): List<LyricLine> {
        val pieces = mutableListOf<Piece>()
        var wordTimed = false
        P.findAll(body).forEach { p ->
            val start = attr(p.groupValues[1], "t")?.toLongOrNull() ?: return@forEach
            val end = start + (attr(p.groupValues[1], "d")?.toLongOrNull() ?: 0)
            val inner = p.groupValues[2]
            val words = S.findAll(inner).toList()
            if (words.isNotEmpty()) {
                wordTimed = true
                words.forEach { s ->
                    val text = clean(s.groupValues[2])
                    if (text.isNotEmpty()) pieces += Piece(start + (attr(s.groupValues[1], "t")?.toLongOrNull() ?: 0), end, text)
                }
            } else {
                val text = clean(inner.replace(TAG, ""))
                // "[Music]", "(laughs)": not something said.
                if (text.isNotEmpty() && !(text.startsWith("[") && text.endsWith("]")) && !(text.startsWith("(") && text.endsWith(")")))
                    pieces += Piece(start, end, text)
            }
        }
        pieces.sortBy { it.startMs }
        // Automatic captions show two lines at once, so their times overlap: each piece ends where the next begins.
        for (i in 0 until pieces.size - 1) {
            val next = pieces[i + 1].startMs
            if (next >= pieces[i].startMs) pieces[i].endMs = minOf(pieces[i].endMs, next).coerceAtLeast(pieces[i].startMs)
        }
        val lines = mutableListOf<LyricLine>()
        var current = mutableListOf<Piece>()
        fun flush() {
            if (current.isEmpty()) return
            val text = current.joinToString(" ") { it.text }
            val words = if (wordTimed) current.mapIndexed { i, w -> LyricWord(if (i < current.size - 1) w.text + " " else w.text, w.startMs, w.endMs) }
            else current.flatMapIndexed { i, c ->
                LrcParser.spreadWords(c.text, c.startMs, c.endMs).let { ws ->
                    if (i < current.size - 1 && ws.isNotEmpty()) ws.dropLast(1) + ws.last().let { it.copy(text = it.text.trimEnd() + " ") } else ws
                }
            }
            lines += LyricLine(current.first().startMs, current.last().endMs, text, words, wordSynced = wordTimed)
            current = mutableListOf()
        }
        for (piece in pieces) {
            val last = current.lastOrNull()
            if (last != null) {
                val length = current.sumOf { it.text.length + 1 }
                if (piece.startMs - last.endMs > PAUSE_MS ||
                    (endsSentence(last.text) && length >= MIN_CHARS) ||
                    length + piece.text.length > MAX_CHARS
                ) flush()
            }
            current += piece
        }
        flush()
        return lines
    }

    private fun endsSentence(text: String) = text.trimEnd('"', '\'', '”', '’', ')').let { t ->
        t.endsWith('.') || t.endsWith('?') || t.endsWith('!') || t.endsWith('…')
    }

    private fun clean(s: String) = s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    private fun kind(t: JsonObject) = (t["kind"] as? JsonPrimitive)?.contentOrNull
    private fun lang(t: JsonObject) = (t["languageCode"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun attr(attrs: String, name: String) = Regex("""\b$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)

    private val P = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val S = Regex("""<s\b([^>]*)>([^<]*)</s>""")
    private val TAG = Regex("<[^>]+>")
}
