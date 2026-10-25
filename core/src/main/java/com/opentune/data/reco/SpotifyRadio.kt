package com.opentune.data.reco

import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.spotify.Spotify
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Spotify's own recommendations. With Spotify connected, [personal] gives
 * Spotify's song radio for the signed-in listener; otherwise [radio] gives
 * the "Recommended" songs on public song pages, with no account or key.
 *
 * A song is first found on Spotify through ListenBrainz's public lookup,
 * which maps a title and artist to Spotify track ids. Every public song
 * page on open.spotify.com then lists five songs Spotify recommends after
 * it; reading the seed's page and the pages of its five gives a radio of
 * up to thirty. Only names are read; the songs play from YouTube Music.
 */
object SpotifyRadio {
    private const val TAG = "SpotifyRadio"
    private const val LOOKUP = "https://labs.api.listenbrainz.org/spotify-id-from-metadata/json"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val ID = Regex("^[A-Za-z0-9]{22}$")
    private val INITIAL_STATE = Regex("""<script id="initialState" type="text/plain">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val json = Json { ignoreUnknownKeys = true }

    /** Seeds already looked up, so a queue that keeps extending asks once per song. */
    private val ids = ConcurrentHashMap<String, String>()

    /** A recommended song and its own Spotify id, for following on. */
    data class Pick(val id: String, val track: Spotify.Track)

    private class Mix(val tracks: List<Spotify.Track>, val at: Long)
    /** Radios already read, by seed, for a while: a queue that keeps extending asks once. */
    private val mixes = ConcurrentHashMap<String, Mix>()
    private const val MIX_TTL_MS = 30 * 60_000L

    /**
     * Spotify's song radio for the listener signed in to Spotify (fifty songs,
     * each with its length), or null when Spotify isn't connected or the
     * song can't be found there for certain. The seed is found with Spotify's
     * own search on title, artist and, when known, length.
     */
    suspend fun personal(title: String, artist: String, album: String?, durationMs: Long): List<Spotify.Track>? {
        val cookie = com.opentune.data.spotify.SpotifyWebPlayer.account() ?: return null
        val account = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cookie.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val key = "$account|${title.lowercase()}|${artist.lowercase()}|${album.orEmpty().lowercase()}|${durationMs / 1000}"
        mixes[key]?.takeIf { System.currentTimeMillis() - it.at < MIX_TTL_MS }?.let { return it.tracks }
        return try {
            val seed = com.opentune.data.spotify.SpotifyWebPlayer.findTrack(title, artist, album, durationMs, cookie) ?: return null
            val playlist = com.opentune.data.spotify.SpotifyWebPlayer.songRadio(seed.uri, cookie) ?: return null
            val (items, _) = com.opentune.data.spotify.SpotifyLibrary.tracks(cookie, playlist)
            // The radio opens with the seed itself.
            items.filter { it.uri != seed.uri }.map { it.track }
                .also { tracks ->
                    if (tracks.isNotEmpty()) mixes[key] = Mix(tracks, System.currentTimeMillis())
                    mixes.entries.removeIf { System.currentTimeMillis() - it.value.at >= MIX_TTL_MS }
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "personal radio for $artist – $title failed", e)
            null
        }
    }

    fun clearPersonalCache() = mixes.clear()

    /** Spotify's picks after the song called [title] by [artist], or empty when Spotify doesn't know it. */
    suspend fun radio(title: String, artist: String, album: String?): List<Spotify.Track> = coroutineScope {
        val seed = spotifyId(title, artist, album) ?: return@coroutineScope emptyList()
        val first = recommended(seed)
        // The second ring comes in order of the first, so the closest picks lead.
        val second = first.map { p -> async { runCatching { recommended(p.id) }.getOrDefault(emptyList()) } }.awaitAll()
        (first + second.flatten())
            .filter { it.id != seed }
            .distinctBy { it.id }
            .map { it.track }
    }

    /** The Spotify id for a song, or null when ListenBrainz has no match. */
    suspend fun spotifyId(title: String, artist: String, album: String?): String? {
        val key = "${title.lowercase()}|${artist.lowercase()}"
        ids[key]?.let { return it }
        val found = withContext(Dispatchers.IO) {
            val body = buildJsonArray {
                // With the album first, which narrows it, then without, which finds more.
                if (!album.isNullOrBlank()) addJsonObject { put("artist_name", artist); put("release_name", album); put("track_name", title) }
                addJsonObject { put("artist_name", artist); put("release_name", ""); put("track_name", title) }
                // And without "(From …)", "(Official Video)" and the like, which YouTube titles often carry.
                bare(title).takeIf { it != title && it.isNotEmpty() }?.let { t ->
                    addJsonObject { put("artist_name", artist); put("release_name", ""); put("track_name", t) }
                }
            }.toString()
            val request = Request.Builder().url(LOOKUP)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("ListenBrainz lookup answered ${r.code}")
                firstId(r.body?.string().orEmpty())
            }
        }
        if (found != null) ids[key] = found else Log.d(TAG, "no Spotify id for $artist – $title")
        return found
    }

    /** [title] with bracketed parts and anything after " - " or " | " taken off. */
    internal fun bare(title: String): String =
        title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").substringBefore(" - ").substringBefore(" | ").trim()

    /** The first Spotify id in a ListenBrainz lookup answer. */
    internal fun firstId(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonArray.asSequence()
            .flatMap { (it.jsonObject["spotify_track_ids"] as? JsonArray).orEmpty().asSequence() }
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { ID.matches(it) }
    }.getOrNull()

    /** The songs Spotify recommends on the public page for track [id]. */
    suspend fun recommended(id: String): List<Pick> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://open.spotify.com/track/$id").header("User-Agent", UA).build()
        val html = Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Spotify answered ${r.code}")
            r.body?.string().orEmpty()
        }
        parsePage(html)
    }

    /** The recommended songs on a Spotify song page. */
    internal fun parsePage(html: String): List<Pick> {
        val raw = INITIAL_STATE.find(html)?.groupValues?.get(1)?.trim() ?: return emptyList()
        // The state is base64 JSON; older pages had it as plain JSON.
        val text = if (raw.startsWith("{")) raw else runCatching { String(java.util.Base64.getMimeDecoder().decode(raw), Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val state = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val data = state.o("internalLinkRecommender")?.o("tracks")?.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            val id = t.str("id")?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            val name = t.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artists = (t.o("artists")?.get("items") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.o("profile")?.str("name") }
            Pick(id, Spotify.Track(name, artists, t.o("albumOfTrack")?.str("name"), 0))
        }
    }

    private fun JsonObject?.o(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
