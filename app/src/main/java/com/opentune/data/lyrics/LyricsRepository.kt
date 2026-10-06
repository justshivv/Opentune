package com.opentune.data.lyrics

import com.opentune.data.isYouTubeId
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import com.opentune.data.local.LocalMusic
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.opentune.data.download.Downloads
import com.opentune.data.settings.LyricsSource
import com.opentune.data.settings.AppSettings
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Lyrics from LRCLIB (lrclib.net): free, keyless, and mostly line-synced, with
 * word sync for some tracks.
 *
 * Sources are tried in the order set in Settings › Lyrics sources. Within
 * LRCLIB: an exact match on title, artist and duration; then a search on
 * title and artist, preferring synced results whose duration is closest;
 * then a free-text search on the cleaned title. YouTube Music's lyrics are
 * unsynced and never used for local files.
 */
object LyricsRepository {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "OpenTune (https://github.com/justshivv/Opentune)"
    private const val TAG = "Lyrics"

    /** A search hit further than this from the track's duration is a different recording. */
    private const val DURATION_TOLERANCE_S = 8.0
    /** Within this, a hit is the same recording. */
    private const val CLOSE_S = 2.0

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Lyrics?>()

    /** Lyrics saved with a download, so they show offline. */
    private fun offlineFile(videoId: String): File? = Downloads.lyricsFileFor(videoId)

    private fun readOffline(videoId: String): Lyrics? =
        offlineFile(videoId)?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString(Lyrics.serializer(), f.readText()) }.getOrNull()
        }

    /** Fetches and keeps the lyrics for a downloaded song; called by the download job. */
    suspend fun saveOffline(videoId: String, title: String, artist: String, durationMs: Long) {
        val f = offlineFile(videoId) ?: return
        val found = lyricsFor(videoId, title, artist, durationMs) ?: return
        withContext(Dispatchers.IO) { runCatching { f.writeText(json.encodeToString(Lyrics.serializer(), found)) } }
    }

    suspend fun lyricsFor(videoId: String, title: String, artist: String, durationMs: Long): Lyrics? {
        withContext(Dispatchers.IO) { readOffline(videoId) }?.let { return it }
        val settings = AppSettings.lyrics.value
        val key = "$videoId|${settings.ordered.filter { it.enabled }.joinToString(",") { it.source.name }}|${settings.preferWordSynced}"
        if (cache.containsKey(key)) return cache[key]
        val result = withContext(Dispatchers.IO) {
            var best: Lyrics? = null
            for (entry in settings.ordered) {
                if (!entry.enabled) continue
                val found = when (entry.source) {
                    LyricsSource.LRCLIB -> runCatching { lookup(title, artist, durationMs) }
                        .onFailure { Log.w(TAG, "LRCLIB lookup failed for $videoId", it) }
                        .getOrNull()
                    LyricsSource.YOUTUBE_MUSIC -> if (!isYouTubeId(videoId)) null else runCatching { youTubeMusic(videoId) }
                        .onFailure { Log.w(TAG, "YouTube Music lyrics failed for $videoId", it) }
                        .getOrNull()
                } ?: continue
                if (rank(found) > rank(best)) best = found
                // The first source with lyrics wins, unless word-by-word is
                // wanted and this isn't it.
                if (!settings.preferWordSynced || rank(found) == WORD_SYNCED) break
            }
            best
        }
        // Only remember answers, and only ones matched on the song's length:
        // without it any version's lyrics can match, timed for another cut.
        if (result != null && durationMs > 0) cache[key] = result
        return result
    }

    private fun rank(lyrics: Lyrics?): Int = when (lyrics) {
        null -> 0
        is Lyrics.Plain -> 1
        is Lyrics.Synced -> if (lyrics.lines.any { it.wordSynced }) WORD_SYNCED else 2
    }

    private const val WORD_SYNCED = 3

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

    /**
     * YouTube Music's own lyrics: unsynced text from LyricFind and others.
     * The watch queue links a Lyrics tab by browse id (`MPLYt…`); browsing
     * it returns the text in a description shelf.
     */
    private suspend fun youTubeMusic(videoId: String): Lyrics? {
        val browseId = findString(Innertube.next(videoId)) { key, value -> key == "browseId" && value.startsWith("MPLYt") }
            ?: return null
        val page = Innertube.browse(browseId)
        val shelf = findObject(page, "musicDescriptionShelfRenderer") ?: return null
        val text = (shelf["description"] as? JsonObject)?.runsText()?.takeIf { it.isNotBlank() } ?: return null
        return Lyrics.Plain(text.trim(), "YouTube Music")
    }

    private fun JsonObject.runsText(): String =
        (this["runs"] as? JsonArray).orEmpty().joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }

    private fun findString(node: JsonElement, match: (String, String) -> Boolean): String? = when (node) {
        is JsonObject -> node.entries.firstNotNullOfOrNull { (k, v) ->
            val str = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (str != null && match(k, str)) str else findString(v, match)
        }
        is JsonArray -> node.firstNotNullOfOrNull { findString(it, match) }
        else -> null
    }

    private fun findObject(node: JsonElement, key: String): JsonObject? = when (node) {
        is JsonObject -> (node[key] as? JsonObject) ?: node.values.firstNotNullOfOrNull { findObject(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { findObject(it, key) }
        else -> null
    }

    /**
     * The best search hit for a track [durationS] long: the synced result
     * closest in length within [CLOSE_S], else the closest synced one within
     * [DURATION_TOLERANCE_S], else the closest plain one. A recording a few
     * seconds longer (an intro, a radio edit) puts every line off by that
     * much, so length matters more than the order LRCLIB lists them in.
     */
    internal fun pick(results: JsonElement?, durationS: Double): JsonObject? {
        val all = (results as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        fun gap(o: JsonObject) = if (durationS <= 0) 0.0 else abs((o.num("duration") ?: durationS) - durationS)
        val near = all.filter { durationS <= 0 || gap(it) <= DURATION_TOLERANCE_S }.sortedBy(::gap)
        val synced = near.filter { !it.str("syncedLyrics").isNullOrBlank() }
        return synced.firstOrNull { gap(it) <= CLOSE_S }
            ?: synced.firstOrNull()
            ?: near.firstOrNull { !it.str("plainLyrics").isNullOrBlank() }
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

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
