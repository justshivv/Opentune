package com.opentune.data.lyrics

import com.opentune.data.Http
import java.util.Base64
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Line-synced lyrics from KuGou's public lyrics service, which needs no
 * account or key: a search by "artist - title" and length returns
 * candidates, each with its own access key, and a download returns the
 * chosen one as LRC. It's strong on Chinese, Japanese and Korean songs and
 * covers a lot of Western pop too.
 *
 * Only the plain LRC format is used. KuGou's word-by-word format (KRC) is
 * encrypted, and reading it would mean using a key taken from their apps.
 * The service only answers over plain HTTP; lyrics are public text, so
 * nothing private travels.
 */
object KuGou {
    private const val BASE = "http://lyrics.kugou.com"
    /** A candidate further than this from the track's length is a different recording. */
    private const val TOLERANCE_MS = 8_000.0
    /** Searching by title alone finds other songs too, so the length has to agree closely. */
    private const val TITLE_ONLY_TOLERANCE_MS = 2_000.0

    private val json = Json { ignoreUnknownKeys = true }

    fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        fun search(keyword: String) = get(
            "$BASE/search".toHttpUrl().newBuilder()
                .addQueryParameter("ver", "1")
                .addQueryParameter("man", "yes")
                .addQueryParameter("client", "mobi")
                .addQueryParameter("keyword", keyword)
                .apply { if (durationMs > 0) addQueryParameter("duration", durationMs.toString()) }
                .build(),
        )
        // The artist is often filed in its own script (米津玄師 for Kenshi
        // Yonezu), so a miss on "artist - title" tries the title alone,
        // with a tighter length match.
        val (id, key) = pick(search("${cleaned.artist} - ${cleaned.title}"), durationMs, cleaned.title)
            ?: durationMs.takeIf { it > 0 }?.let { pick(search(cleaned.title), it, cleaned.title, TITLE_ONLY_TOLERANCE_MS) }
            ?: return null
        val download = "$BASE/download".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "pc")
            .addQueryParameter("id", id)
            .addQueryParameter("accesskey", key)
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .build()
        val lrc = lrcOf(get(download)) ?: return null
        val lines = clean(LrcParser.parse(lrc, durationMs), cleaned.title, cleaned.artist)
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, "KuGou")
    }

    private val VERSION = Regex("""\b(remix|live|instrumental|karaoke|acoustic|cover|sped up|slowed|dj)\b|伴奏|翻唱|现场""", RegexOption.IGNORE_CASE)

    /**
     * The id and access key of the candidate closest to [durationMs], or null
     * if none is close. A remix, live or instrumental version only counts
     * when [title] is one too: otherwise one that happens to be the same
     * length would win over the song itself.
     */
    internal fun pick(search: JsonElement?, durationMs: Long, title: String = "", tolerance: Double = TOLERANCE_MS): Pair<String, String>? {
        val candidates = ((search as? JsonObject)?.get("candidates") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        fun gap(o: JsonObject) = if (durationMs <= 0) 0.0 else abs((o.num("duration") ?: Double.MAX_VALUE) - durationMs)
        val wantsVersion = VERSION.containsMatchIn(title)
        val best = candidates
            .filter { gap(it) <= tolerance }
            .filter { wantsVersion || !VERSION.containsMatchIn(it.str("song").orEmpty()) }
            .minByOrNull(::gap) ?: return null
        val id = best.str("id") ?: return null
        val key = best.str("accesskey") ?: return null
        return id to key
    }

    /** The LRC text inside a download answer (base64 in "content"). */
    internal fun lrcOf(download: JsonElement?): String? {
        val content = (download as? JsonObject)?.str("content")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { String(Base64.getDecoder().decode(content), Charsets.UTF_8) }.getOrNull()
    }

    private val CREDIT = Regex(
        """^\s*(作词|作曲|编曲|制作人|监制|混音|和声|吉他|贝斯|鼓|录音|母带|出品|发行|OP|SP|(lyrics|words|music|composed|composer|written|arranged|produced|producer|mixed|mastered|recorded|engineered|vocals?)( by| at)?)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Drops what KuGou puts before the song: a "Title - Artist" line and
     * credit lines (lyricist, composer, producer, mixing and the rest), which
     * would otherwise show as lyrics. Later in the song only the known credit
     * labels are dropped, so a lyric with a colon in it stays.
     */
    internal fun clean(lines: List<LyricLine>, title: String, artist: String): List<LyricLine> {
        val t = title.lowercase()
        val a = artist.lowercase()
        // "Title - Artist", "Artist - Title", "Title (Remix) - Artist (其他名)" and the like.
        fun isHeader(text: String) = text.contains(" - ") && text.lowercase().let { it.contains(t) || it.contains(a) }
        // KuGou writes its credits as "Role：Name" with a full-width colon, in
        // a block before the first line, whatever the role is called.
        fun isCredit(text: String) = CREDIT.containsMatchIn(text) || text.contains('：')
        val firstLyric = lines.indexOfFirst { l -> l.text.isNotBlank() && !isHeader(l.text) && !isCredit(l.text) }
        if (firstLyric < 0) return emptyList()
        return lines.drop(firstLyric).filterNot { CREDIT.containsMatchIn(it.text) }
    }

    private fun get(url: HttpUrl): JsonElement? {
        val call = Http.client.newCall(Request.Builder().url(url).header("User-Agent", "OpenTune").build())
        return call.execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("KuGou answered ${r.code}")
            r.body?.string()?.let(json::parseToJsonElement)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
