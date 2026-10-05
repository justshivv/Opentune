package com.opentune.data.lyrics

import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Lyrics from LRCLIB (lrclib.net): free, keyless, and mostly line-synced, with
 * word sync for some tracks.
 *
 * Lookup order: an exact match on title, artist and duration; then a search
 * on title and artist, preferring synced results whose duration is closest;
 * then a free-text search on the cleaned title.
 */
object LyricsRepository {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "OpenTune (https://github.com/justshivv/Opentune)"
    private const val TAG = "Lyrics"

    /** A search hit further than this from the track's duration is a different recording. */
    private const val DURATION_TOLERANCE_S = 8.0

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Lyrics?>()

    suspend fun lyricsFor(videoId: String, title: String, artist: String, durationMs: Long): Lyrics? {
        if (cache.containsKey(videoId)) return cache[videoId]
        val result = withContext(Dispatchers.IO) {
            runCatching { lookup(title, artist, durationMs) }
                .onFailure { Log.w(TAG, "Lyrics lookup failed for $videoId", it) }
                .getOrNull()
        }
        // Only remember answers; a failed request is worth retrying next time.
        if (result != null) cache[videoId] = result
        return result
    }

    private fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val durationS = durationMs / 1000.0

        if (durationMs > 0) {
            val exact = request(
                "get",
                "track_name" to cleaned.title,
                "artist_name" to cleaned.artist,
                "duration" to durationS.toLong().toString(),
            ) as? JsonObject
            exact?.let { toLyrics(it, durationMs) }?.let { return it }
        }

        val byFields = request("search", "track_name" to cleaned.title, "artist_name" to cleaned.artist)
        pick(byFields, durationS)?.let { toLyrics(it, durationMs) }?.let { return it }

        val byQuery = request("search", "q" to "${cleaned.artist} ${cleaned.title}")
        return pick(byQuery, durationS)?.let { toLyrics(it, durationMs) }
    }

    private fun pick(results: JsonElement?, durationS: Double): JsonObject? {
        val candidates = (results as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { durationS <= 0 || abs((it.num("duration") ?: durationS) - durationS) <= DURATION_TOLERANCE_S }
        return candidates.firstOrNull { !it.str("syncedLyrics").isNullOrBlank() }
            ?: candidates.firstOrNull { !it.str("plainLyrics").isNullOrBlank() }
    }

    private fun toLyrics(obj: JsonObject, durationMs: Long): Lyrics? {
        obj.str("syncedLyrics")?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lines = LrcParser.parse(lrc, durationMs)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines, "LRCLIB")
        }
        return obj.str("plainLyrics")?.takeIf { it.isNotBlank() }?.let { Lyrics.Plain(it.trim(), "LRCLIB") }
    }

    private fun request(path: String, vararg params: Pair<String, String>): JsonElement? {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val call = Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        return call.execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw java.io.IOException("LRCLIB answered ${response.code}")
            response.body?.string()?.let(json::parseToJsonElement)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
}
