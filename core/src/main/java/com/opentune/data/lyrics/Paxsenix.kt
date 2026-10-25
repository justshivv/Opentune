package com.opentune.data.lyrics

import com.opentune.data.Http
import com.opentune.data.metadata.AppleWebToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Paxsenix's Apple Music lyrics: the word-by-word TTML Apple shows, with its
 * duets, backing vocals and text direction. The song is found in Apple's own
 * catalogue first, with the token Apple's web player uses (read from its
 * page, as BitChord does), and only a result with the same title, version,
 * an artist and a length within 3 s counts (see [LrcRed.best]). Paxsenix
 * then serves that track's TTML by its Apple id.
 */
internal object Paxsenix {
    private const val LYRICS = "https://lyrics.paxsenix.org/apple-music/lyrics"
    private const val APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search"
    /** Paxsenix says "busy, retry later" under load; one more try after this. */
    private const val BUSY_RETRY_MS = 1_500L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val id = appleId(cleaned.title, cleaned.artist, durationMs) ?: return null
        val url = LYRICS.toHttpUrl().newBuilder().addQueryParameter("id", id).addQueryParameter("ttml", "true").build().toString()
        var body = get(url)
        if (body != null && isBusy(body)) {
            delay(BUSY_RETRY_MS)
            body = get(url)
        }
        val ttml = body?.let(::ttmlOf) ?: return null
        val lines = Ttml.parse(ttml).takeIf { it.isNotEmpty() } ?: return null
        return Lyrics.Synced(lines, "Paxsenix")
    }

    /** The TTML in a Paxsenix answer: `{"type":"TTML","content":"<tt…"}`, or the document itself. */
    internal fun ttmlOf(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("<")) return trimmed.takeIf { it.contains("<tt", ignoreCase = true) }
        val o = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        return o["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.contains("<tt", ignoreCase = true) }
    }

    private fun isBusy(body: String) = body.contains("\"error\":true") && body.contains("busy", ignoreCase = true)

    private suspend fun appleId(title: String, artist: String, durationMs: Long): String? {
        val bearer = AppleWebToken.get() ?: return null
        val url = APPLE_SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("term", "$title $artist".trim())
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", "10")
            .build().toString()
        val body = get(url, bearer)
        if (body == null) {
            // A token Apple has since turned over: read a fresh one next time.
            AppleWebToken.forget()
            return null
        }
        val songs = appleSongs(body)
        val hit = LrcRed.best(songs.map { it.second }, title, artist, durationMs) ?: return null
        return songs.firstOrNull { it.second === hit }?.first
    }

    /** Apple's search results as (Apple id, what [LrcRed.best] compares). */
    internal fun appleSongs(body: String): List<Pair<String, LrcRed.Hit>> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val data = ((root["results"] as? JsonObject)?.get("songs") as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val a = o["attributes"] as? JsonObject ?: return@mapNotNull null
            val ms = a["durationInMillis"]?.jsonPrimitive?.longOrNull
            id to LrcRed.Hit(
                isrc = a["isrc"]?.jsonPrimitive?.contentOrNull,
                title = a["name"]?.jsonPrimitive?.contentOrNull,
                artist = a["artistName"]?.jsonPrimitive?.contentOrNull,
                durationS = ms?.takeIf { it > 0 }?.div(1000.0),
            )
        }
    }

    private suspend fun get(url: String, bearer: String? = null): String? = runInterruptible(Dispatchers.IO) {
        val request = if (bearer != null) AppleWebToken.request(url, bearer)
            else Request.Builder().url(url).header("User-Agent", AppleWebToken.USER_AGENT).build()
        Http.client.newCall(request).execute().use { if (it.isSuccessful || it.code == 503) it.body?.string() else null }
    }
}
