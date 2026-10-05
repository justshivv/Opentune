package com.opentune.data.subsonic

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.model.Song
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Your own music server, through the (Open)Subsonic API: Navidrome, Gonic,
 * Airsonic, Nextcloud Music, LMS and others. Songs stream untouched
 * (`format=raw`), so FLAC and hi-res files play as they are stored.
 *
 * Sign-in uses the API's token scheme: md5(password + salt) with a random
 * salt. The password itself is never stored; the token and salt are, in
 * app-private storage left out of backups, since together they let anyone
 * sign in to the server.
 *
 * Server songs are [Song]s whose id starts with [PREFIX]. Their cover is
 * stored as `subsonic://cover/<id>`, carrying no credentials, and turned
 * into a real URL only when an image is loaded ([resolveCover]), so the
 * token never ends up in history, playlists or an exported backup.
 */
object Subsonic {
    const val PREFIX = "subsonic:"
    private const val COVER_PREFIX = "subsonic://cover/"
    private const val TAG = "Subsonic"
    private const val API_VERSION = "1.16.1"
    private const val CLIENT = "OpenTune"

    data class Server(val url: String, val user: String, val token: String, val salt: String, val type: String?)

    data class Album(
        val id: String,
        val name: String,
        val artist: String,
        val artistId: String?,
        val cover: String?,
        val songCount: Int,
        val year: Int?,
    )

    data class Artist(val id: String, val name: String, val cover: String?, val albumCount: Int)

    data class Playlist(val id: String, val name: String, val songCount: Int, val cover: String?)

    data class Results(val artists: List<Artist>, val albums: List<Album>, val songs: List<Song>)

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null
    private val _server = MutableStateFlow<Server?>(null)
    val server: StateFlow<Server?> = _server.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("subsonic", Context.MODE_PRIVATE)
        prefs = p
        val url = p.getString(K_URL, null)
        val user = p.getString(K_USER, null)
        val token = p.getString(K_TOKEN, null)
        val salt = p.getString(K_SALT, null)
        if (url != null && user != null && token != null && salt != null) _server.value = Server(url, user, token, salt, p.getString(K_TYPE, null))
    }

    fun isSubsonic(id: String) = id.startsWith(PREFIX)

    /**
     * Signs in with [address] (a scheme is optional: https is tried first,
     * then http, for servers on a home network), then remembers the token.
     */
    suspend fun connect(address: String, user: String, password: String): Result<Server> = withContext(Dispatchers.IO) {
        runCatching {
            val salt = randomSalt()
            val token = md5(password + salt)
            val candidates = baseUrls(address)
            require(candidates.isNotEmpty()) { "That doesn't look like a server address" }
            var lastError: Throwable? = null
            for (base in candidates) {
                val trial = Server(base, user.trim(), token, salt, null)
                val pinged = runCatching { call(trial, "ping") }
                pinged.onSuccess { body ->
                    val server = trial.copy(type = body["type"].string())
                    prefs?.edit {
                        putString(K_URL, server.url)
                        putString(K_USER, server.user)
                        putString(K_TOKEN, server.token)
                        putString(K_SALT, server.salt)
                        putString(K_TYPE, server.type)
                    }
                    _server.value = server
                    return@runCatching server
                }
                lastError = pinged.exceptionOrNull()
            }
            throw lastError ?: IOException("Couldn't reach the server")
        }
    }

    fun disconnect() {
        prefs?.edit { clear() }
        _server.value = null
    }

    suspend fun albums(type: String = "newest", size: Int = 40): List<Album> =
        get("getAlbumList2", "type" to type, "size" to size.toString())["albumList2"].obj()["album"].list().map(::album)

    suspend fun album(id: String): Pair<Album, List<Song>> {
        val a = get("getAlbum", "id" to id)["album"].obj()
        return album(a) to a["song"].list().map(::song)
    }

    suspend fun artists(): List<Artist> =
        get("getArtists")["artists"].obj()["index"].list().flatMap { it.obj()["artist"].list() }.map(::artist)

    suspend fun artist(id: String): Pair<Artist, List<Album>> {
        val a = get("getArtist", "id" to id)["artist"].obj()
        return artist(a) to a["album"].list().map(::album)
    }

    suspend fun playlists(): List<Playlist> =
        get("getPlaylists")["playlists"].obj()["playlist"].list().map(::playlist)

    suspend fun playlist(id: String): Pair<Playlist, List<Song>> {
        val p = get("getPlaylist", "id" to id)["playlist"].obj()
        return playlist(p) to p["entry"].list().map(::song)
    }

    suspend fun search(query: String): Results {
        val r = get("search3", "query" to query, "artistCount" to "10", "albumCount" to "20", "songCount" to "50")["searchResult3"].obj()
        return Results(r["artist"].list().map(::artist), r["album"].list().map(::album), r["song"].list().map(::song))
    }

    suspend fun randomSongs(size: Int = 50): List<Song> =
        get("getRandomSongs", "size" to size.toString())["randomSongs"].obj()["song"].list().map(::song)

    /** Tells the server a song is playing ([submission] false) or was played. */
    suspend fun scrobble(songId: String, timeMs: Long, submission: Boolean) {
        val id = songId.removePrefix(PREFIX)
        runCatching { get("scrobble", "id" to id, "time" to timeMs.toString(), "submission" to submission.toString()) }
            .onFailure { Log.w(TAG, "scrobble failed", it) }
    }

    /** The song's file as stored: no transcoding, so lossless stays lossless. */
    fun streamUrl(songId: String): String? {
        val s = _server.value ?: return null
        return url(s, "stream", "id" to songId.removePrefix(PREFIX), "format" to "raw").toString()
    }

    /** A credential-free cover address for a [Song.thumbnailUrl]. */
    fun coverRef(coverId: String?): String? = coverId?.takeIf { it.isNotBlank() }?.let { "$COVER_PREFIX$it" }

    fun isCoverRef(url: String?) = url?.startsWith(COVER_PREFIX) == true

    /** The real cover URL for a [coverRef], at [size] pixels, or the input unchanged. */
    fun resolveCover(url: String?, size: Int = 600): String? {
        if (!isCoverRef(url)) return url
        val s = _server.value ?: return null
        return url(s, "getCoverArt", "id" to url!!.removePrefix(COVER_PREFIX), "size" to size.toString()).toString()
    }

    /** The configured server's host, the only one covers are fetched from besides YouTube's. */
    fun host(): String? = _server.value?.url?.toHttpUrlOrNull()?.host

    // ---- Requests ---------------------------------------------------------------

    private suspend fun get(method: String, vararg params: Pair<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val s = _server.value ?: throw IOException("No music server connected")
        call(s, method, *params)
    }

    private fun call(s: Server, method: String, vararg params: Pair<String, String>): JsonObject {
        val request = Request.Builder().url(url(s, method, *params)).build()
        Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The server answered ${r.code}")
            return parse(r.body?.string().orEmpty())
        }
    }

    /** The `subsonic-response` body, or an exception carrying the server's own error. */
    internal fun parse(text: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IOException("That isn't a Subsonic server")
        val body = root["subsonic-response"].obj()
        if (body["status"].string() != "ok") {
            val error = body["error"].obj()
            val message = error["message"].string() ?: "Request failed"
            throw IOException(if (error["code"].int() == 40) "Wrong username or password" else message)
        }
        return body
    }

    internal fun url(s: Server, method: String, vararg params: Pair<String, String>): HttpUrl {
        val base = s.url.toHttpUrlOrNull() ?: throw IOException("Bad server address")
        return base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(method)
            .addQueryParameter("u", s.user)
            .addQueryParameter("t", s.token)
            .addQueryParameter("s", s.salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT)
            .addQueryParameter("f", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
    }

    /** Addresses to try for what was typed: as given, or https then http without a scheme. */
    internal fun baseUrls(address: String): List<String> {
        val trimmed = address.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        val tries = if ("://" in trimmed) listOf(trimmed) else listOf("https://$trimmed", "http://$trimmed")
        return tries.mapNotNull { it.toHttpUrlOrNull()?.toString()?.trimEnd('/') }
    }

    internal fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun randomSalt(): String {
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---- Mapping ----------------------------------------------------------------

    internal fun song(e: JsonElement): Song {
        val o = e.obj()
        val seconds = o["duration"].long()
        return Song(
            videoId = PREFIX + o["id"].string().orEmpty(),
            title = o["title"].string().orEmpty(),
            artist = o["artist"].string().orEmpty(),
            thumbnailUrl = coverRef(o["coverArt"].string()),
            durationText = seconds?.let { "%d:%02d".format(it / 60, it % 60) },
            albumName = o["album"].string(),
        )
    }

    private fun album(e: JsonElement): Album {
        val o = e.obj()
        return Album(
            id = o["id"].string().orEmpty(),
            name = o["name"].string() ?: o["title"].string().orEmpty(),
            artist = o["artist"].string().orEmpty(),
            artistId = o["artistId"].string(),
            cover = coverRef(o["coverArt"].string()),
            songCount = o["songCount"].int() ?: 0,
            year = o["year"].int(),
        )
    }

    private fun artist(e: JsonElement): Artist {
        val o = e.obj()
        return Artist(o["id"].string().orEmpty(), o["name"].string().orEmpty(), coverRef(o["coverArt"].string()), o["albumCount"].int() ?: 0)
    }

    private fun playlist(e: JsonElement): Playlist {
        val o = e.obj()
        return Playlist(o["id"].string().orEmpty(), o["name"].string().orEmpty(), o["songCount"].int() ?: 0, coverRef(o["coverArt"].string()))
    }

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    /** Subsonic's JSON gives a lone child as an object rather than a one-item list. */
    private fun JsonElement?.list(): List<JsonElement> = when (this) {
        is JsonArray -> this
        is JsonObject -> listOf(this)
        else -> emptyList()
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

    private const val K_URL = "url"
    private const val K_USER = "user"
    private const val K_TOKEN = "token"
    private const val K_SALT = "salt"
    private const val K_TYPE = "type"
}
