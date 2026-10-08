package com.opentune.data.reco

import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.spotify.PlaylistImport
import com.opentune.data.spotify.Spotify
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * JioSaavn's recommendations: the songs its own app suggests after a song,
 * strong on Indian music. The song is found with JioSaavn's public search
 * and its recommendations read from the same public endpoint the JioSaavn
 * app uses. Only names are read; nothing is streamed from JioSaavn, and the
 * songs play from YouTube Music.
 */
object JioSaavnRadio {
    private const val TAG = "JioSaavnRadio"
    private const val API = "https://www.jiosaavn.com/api.php"
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = ConcurrentHashMap<String, String>()

    /** Covers, edits and mash-ups that crowd the list without being new songs. */
    private val EDITS = Regex("""\b(slowed|reverb|lofi|lo-fi|sped up|8d|mashup|mash-up|nightcore|karaoke|instrumental)\b""", RegexOption.IGNORE_CASE)

    data class Pick(val id: String, val track: Spotify.Track)

    /** JioSaavn's picks after the song called [title] by [artist], or empty when JioSaavn doesn't have it. */
    suspend fun radio(title: String, artist: String): List<Spotify.Track> {
        val seed = songId(title, artist) ?: return emptyList()
        val first = recommended(seed)
        // JioSaavn lists about sixteen; the first pick's own list tops it up.
        val more = first.firstOrNull()?.let { runCatching { recommended(it.id) }.getOrDefault(emptyList()) }.orEmpty()
        val seedKey = PlaylistImport.key(title)
        return (first + more)
            .filter { it.id != seed }
            .filterNot { EDITS.containsMatchIn(it.track.title) }
            // The same song again under another uploader or as "Husn x Something".
            .filterNot { seedKey.isNotEmpty() && PlaylistImport.key(it.track.title).contains(seedKey) }
            .distinctBy { it.id }
            .map { it.track }
    }

    /** JioSaavn's id for a song, or null when its search has nothing close. */
    suspend fun songId(title: String, artist: String): String? {
        val key = "${title.lowercase()}|${artist.lowercase()}"
        ids[key]?.let { return it }
        val body = call("search.getResults", "q" to "$title $artist".trim(), "n" to "8", "p" to "1")
        val found = bestMatch(parseSearch(body), title, artist)
        if (found != null) ids[key] = found else Log.d(TAG, "no JioSaavn match for $artist – $title")
        return found
    }

    suspend fun recommended(id: String): List<Pick> = parseReco(call("reco.getreco", "pid" to id), id)

    private suspend fun call(method: String, vararg params: Pair<String, String>): String = withContext(Dispatchers.IO) {
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("__call", method)
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("ctx", "android")
            .addQueryParameter("api_version", "4")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("JioSaavn answered ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    /** The songs in a search answer. */
    internal fun parseSearch(body: String): List<Pick> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        return (root["results"] as? JsonArray).orEmpty().mapNotNull(::pick)
    }

    /** The songs in a recommendations answer, which is keyed by the seed's id. */
    internal fun parseReco(body: String, id: String): List<Pick> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val list = when (root) {
            is JsonObject -> root[id] as? JsonArray
            is JsonArray -> root
            else -> null
        }
        return list.orEmpty().mapNotNull(::pick)
    }

    /** The search result whose title and artist match, by the same rules as importing a playlist. */
    internal fun bestMatch(results: List<Pick>, title: String, artist: String): String? {
        val wantTitle = PlaylistImport.key(title)
        val wantArtist = PlaylistImport.key(artist)
        return results.firstOrNull { p ->
            val t = PlaylistImport.key(p.track.title)
            val titleOk = t == wantTitle || (t.isNotEmpty() && wantTitle.isNotEmpty() && (t.contains(wantTitle) || wantTitle.contains(t)))
            val artistOk = wantArtist.isEmpty() || p.track.artists.any { a -> PlaylistImport.key(a).let { it.isNotEmpty() && (wantArtist.contains(it) || it.contains(wantArtist)) } }
            titleOk && artistOk
        }?.id
    }

    private fun pick(e: JsonElement): Pick? {
        val o = e as? JsonObject ?: return null
        if ((o.str("type") ?: "song") != "song") return null
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = o.str("title")?.let(::unescape)?.takeIf { it.isNotBlank() } ?: return null
        val info = o["more_info"] as? JsonObject
        val artists = ((info?.get("artistMap") as? JsonObject)?.get("primary_artists") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("name")?.let(::unescape) }
        val seconds = info?.str("duration")?.toLongOrNull() ?: 0
        return Pick(id, Spotify.Track(title, artists, info?.str("album")?.let(::unescape), seconds * 1000))
    }

    /** JioSaavn sends names HTML-escaped: "Satranga (From &quot;ANIMAL&quot;)". */
    internal fun unescape(s: String): String =
        if ('&' !in s) s else s.replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
