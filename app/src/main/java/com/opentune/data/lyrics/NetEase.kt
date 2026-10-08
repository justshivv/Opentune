package com.opentune.data.lyrics

import com.opentune.data.Http
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Line-synced lyrics from NetEase Cloud Music's public web endpoints, with
 * no account or key: a search by title and artist lists songs with their
 * lengths, and the lyric endpoint returns the chosen one as LRC. A large
 * catalogue of Chinese, Korean, Japanese and Western songs.
 */
object NetEase {
    private const val BASE = "https://music.163.com/api"
    /** A song further than this from the track's length is a different recording. */
    private const val TOLERANCE_MS = 5_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val search = get(
            "$BASE/search/get".toHttpUrl().newBuilder()
                .addQueryParameter("s", "${cleaned.title} ${cleaned.artist}")
                .addQueryParameter("type", "1")
                .addQueryParameter("limit", "10")
                .build(),
        )
        val id = pick(search, cleaned.title, durationMs) ?: return null
        val lrc = lrcOf(get("$BASE/song/lyric".toHttpUrl().newBuilder().addQueryParameter("id", id.toString()).addQueryParameter("lv", "1").build()))
            ?: return null
        val lines = KuGou.clean(LrcParser.parse(lrc, durationMs), cleaned.title, cleaned.artist)
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, "NetEase")
    }

    private val VERSION = Regex("""\b(remix|live|instrumental|karaoke|acoustic|cover|sped up|slowed|nightcore|8d)\b|伴奏|翻唱|现场""", RegexOption.IGNORE_CASE)

    /**
     * The song whose title matches and whose length is closest to
     * [durationMs], leaving out remixes, live cuts and sped-up edits unless
     * [title] is one.
     */
    internal fun pick(search: JsonElement?, title: String, durationMs: Long): Long? {
        val songs = (((search as? JsonObject)?.get("result") as? JsonObject)?.get("songs") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val want = key(title)
        val wantsVersion = VERSION.containsMatchIn(title)
        fun gap(o: JsonObject) = if (durationMs <= 0) 0L else abs(((o["duration"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE / 2) - durationMs)
        return songs
            .filter { o -> val name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(); key(name) == want || key(name).startsWith(want) }
            .filter { wantsVersion || !VERSION.containsMatchIn((it["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            .filter { gap(it) <= TOLERANCE_MS }
            .minByOrNull(::gap)
            ?.let { (it["id"] as? JsonPrimitive)?.longOrNull }
    }

    /** The LRC text in a lyric answer; null when the song has none or only "instrumental". */
    internal fun lrcOf(answer: JsonElement?): String? =
        (((answer as? JsonObject)?.get("lrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.contains('[') && it.lines().count { l -> l.contains(']') && l.substringAfter(']').isNotBlank() } >= 3 }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            .header("Referer", "https://music.163.com/")
            .build()
        return Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("NetEase answered ${r.code}")
            r.body?.string()?.let(json::parseToJsonElement)
        }
    }
}
