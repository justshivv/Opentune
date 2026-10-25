package com.opentune.data.spotify

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.lyrics.TrackNameCleaner
import com.opentune.data.model.Song
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Spotify's web player API, read with the listener's own Spotify sign-in
 * (the `sp_dc` cookie the in-app sign-in leaves behind): each song's Canvas,
 * the short looping clip Spotify shows behind it, and the library's
 * playlists (see [SpotifyLibrary]). A port of the browser extension's
 * lib/spotify.ts.
 *
 * The web player's token comes from its `/api/token`, signed with the TOTP
 * secret in the player's own script; the query hashes come from the same
 * script. Both are read live, with the last known values as the fallback.
 * Signed out, Spotify still answers searches but every canvas is empty, so
 * the cookie is required. A song is found by the player's own search and
 * only taken with the same title, artist and length (within 3 s).
 */
object SpotifyWebPlayer {
    private const val TAG = "SpotifyWebPlayer"
    private const val WEB_PLAYER = "https://open.spotify.com"
    private const val CDN = "https://open.spotifycdn.com/cdn/build/web-player/"
    private const val PATHFINDER = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val CLIENT_TOKEN = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val SPCLIENT = "https://spclient.wg.spotify.com"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private const val FOUND_TTL_MS = 12 * 60 * 60_000L
    private const val MISSED_TTL_MS = 30 * 60_000L
    private const val NO_CANVAS_TTL_MS = 6 * 60 * 60_000L
    private const val RETRY_MS = 60_000L

    /** The queries used, with the last hashes known to work (the web player's script on 2026-10-24). */
    internal val KNOWN_HASHES = mapOf(
        "canvas" to "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f",
        "searchTracks" to "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751",
        "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
        "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        "home" to "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1",
        "addToLibrary" to "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
        "removeFromLibrary" to "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
    )
    /** The fallback secret, from the extension's table (DesktopSpotifyToken.TOTP_SECRETS). */
    private val KNOWN_SECRET = Secret(12, listOf(107, 81, 49, 57, 67, 93, 87, 81, 69, 67, 40, 93, 48, 50, 46, 91, 94, 113, 41, 47))

    /** Spotify said the sign-in is gone or was never there: sign in again. */
    class SignedOut(message: String) : IOException(message)

    @Serializable
    internal data class Secret(val version: Int, val codes: List<Int>)

    @Serializable
    internal data class WebPlayer(
        val clientVersion: String,
        val secret: Secret,
        val hashes: Map<String, String>,
        /** Whether everything came from the live script; a partial read is tried again sooner. */
        val complete: Boolean,
        val at: Long,
    )

    data class Hit(val uri: String, val title: String, val artists: List<String>, val album: String?, val durationMs: Long)

    private class Access(val token: String, val expiresAt: Long, val clientId: String?, val cookie: String)
    private class ClientToken(val token: String, val expiresAt: Long)
    private class Found(val url: String?, val at: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var dir: File? = null
    private var webPlayer: WebPlayer? = null
    private var access: Access? = null
    private var client: ClientToken? = null
    private var retryAfter = 0L
    private val found = object : LinkedHashMap<String, Found>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Found>?) = size > 300
    }

    fun init(context: Context) {
        dir = context.applicationContext.filesDir
    }

    /** Forget the tokens and answers of a sign-in that has ended. */
    fun signedOut() {
        com.opentune.data.reco.SpotifyRadio.clearPersonalCache()
        access = null
        client = null
        synchronized(found) { found.clear() }
    }

    /**
     * The Canvas clip (an https MP4) for [song], or null when it has none or
     * Spotify has no confident match. Throws [SignedOut] when the cookie no
     * longer signs in, and IOException when Spotify couldn't be reached.
     */
    suspend fun canvasFor(song: Song, durationMs: Long, cookie: String): String? {
        if (durationMs <= 0 || song.title.isBlank() || song.artist.isBlank()) return null
        val key = "${song.title}|${song.artist}|${song.albumName}|${durationMs / 1000}".lowercase(Locale.ROOT)
        synchronized(found) { found[key] }?.let { f ->
            if (f.url != null || System.currentTimeMillis() - f.at < NO_CANVAS_TTL_MS) return f.url
        }
        val hit = findTrack(song.title, song.artist, song.albumName, durationMs, cookie)
        val url = hit?.let { h ->
            canvasIn(query("canvas", cookie, buildJsonObject { put("trackUri", h.uri) }))
        }
        synchronized(found) { found[key] = Found(url, System.currentTimeMillis()) }
        return url
    }

    /**
     * Supplies the sign-in cookie when Spotify is connected, null otherwise;
     * set by the app, which keeps the cookie with its WebView.
     */
    @Volatile var account: () -> String? = { null }

    /**
     * The same recording on Spotify, found with the web player's own search
     * (see [pick]); null when there's no confident match.
     */
    suspend fun findTrack(title: String, artist: String, album: String?, durationMs: Long, cookie: String): Hit? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val search = query("searchTracks", cookie, buildJsonObject {
            put("searchTerm", "${cleaned.title} ${cleaned.artist}")
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", false)
            put("includeAuthors", false)
        })
        return pick(hitsIn(search), cleaned.title, cleaned.artist, album, durationMs)
    }

    /**
     * The id of the playlist Spotify makes as the radio for track [uri] (its
     * "Go to song radio"): fifty songs, chosen for the signed-in listener.
     */
    suspend fun songRadio(uri: String, cookie: String): String? {
        val root = get("$SPCLIENT/inspiredby-mix/v2/seed_to_playlist/$uri?response-format=json", cookie)
        return radioPlaylistIn(root)
    }

    /** The playlist id in a `seed_to_playlist` answer. */
    internal fun radioPlaylistIn(root: JsonObject): String? =
        (root["mediaItems"] as? JsonArray).orEmpty()
            .firstNotNullOfOrNull { (it as? JsonObject)?.text("uri")?.takeIf { u -> u.startsWith("spotify:playlist:") } }
            ?.substringAfterLast(':')
            ?.takeIf { Regex("[A-Za-z0-9]{22}").matches(it) }

    /** A GET on Spotify's own API host, with the web player's tokens. */
    private suspend fun get(url: String, cookie: String, retried: Boolean = false): JsonObject {
        val bearer = accessToken(cookie)
        val clientToken = clientToken(bearer, webPlayer())
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${bearer.token}")
            .header("Accept", "application/json")
            .header("App-Platform", "WebPlayer")
            .header("Origin", WEB_PLAYER)
            .header("Referer", "$WEB_PLAYER/")
            .header("User-Agent", USER_AGENT)
            .apply { if (clientToken != null) header("Client-Token", clientToken) }
            .build()
        val (code, text) = runInterruptible(Dispatchers.IO) {
            Http.client.newCall(request).execute().use { it.code to it.body?.string() }
        }
        if (code == 401 && !retried) {
            access = null
            client = null
            return get(url, cookie, true)
        }
        if (code !in 200..299) throw IOException("Spotify answered $code")
        return text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: throw IOException("Spotify answered with something other than JSON")
    }

    // ---- Matching -------------------------------------------------------------------

    /** Tracks in a `searchTracks` answer. */
    internal fun hitsIn(root: JsonObject): List<Hit> {
        val items = root.path("data", "searchV2", "tracksV2", "items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val data = item.path("item", "data") as? JsonObject ?: return@mapNotNull null
            val uri = data.text("uri")?.takeIf { it.startsWith("spotify:track:") } ?: return@mapNotNull null
            val title = data.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val artists = (data.path("artists", "items") as? JsonArray).orEmpty()
                .mapNotNull { (it.path("profile", "name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            val ms = (data.path("duration", "totalMilliseconds") ?: data.path("trackDuration", "totalMilliseconds"))
                .let { (it as? JsonPrimitive)?.longOrNull } ?: 0L
            Hit(uri, title, artists, (data.path("albumOfTrack", "name") as? JsonPrimitive)?.contentOrNull, ms)
        }
    }

    /**
     * The same recording: title and artist equal once cleaned and folded,
     * length within 3 s (when [durationMs] is known). The same album wins
     * when the song names one.
     */
    internal fun pick(hits: List<Hit>, title: String, artist: String, album: String?, durationMs: Long): Hit? {
        val wantTitle = fold(title)
        val wantArtist = fold(artist)
        if (wantTitle.isEmpty() || wantArtist.isEmpty()) return null
        val same = hits.filter { h ->
            fold(TrackNameCleaner.clean(h.title, h.artists.firstOrNull().orEmpty()).title) == wantTitle &&
                h.artists.any { fold(it) == wantArtist } &&
                (durationMs <= 0 || (h.durationMs > 0 && kotlin.math.abs(h.durationMs - durationMs) <= 3_000))
        }
        val wantAlbum = album?.let(::fold)?.takeIf { it.isNotEmpty() }
        return same.firstOrNull { wantAlbum != null && fold(it.album.orEmpty()) == wantAlbum } ?: same.firstOrNull()
    }

    /** The clip in a `canvas` answer, when it's an MP4 on Spotify's own CDN. */
    internal fun canvasIn(root: JsonObject): String? {
        val url = (root.path("data", "trackUnion", "canvas", "url") as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull() ?: return null
        val host = url.host
        val spotify = host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com")
        return url.toString().takeIf { url.isHttps && spotify && url.encodedPath.endsWith(".mp4") }
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    // ---- Pathfinder -----------------------------------------------------------------

    internal suspend fun query(operation: String, cookie: String, variables: JsonObject, retried: Boolean = false): JsonObject {
        val bearer = accessToken(cookie)
        val player = webPlayer()
        val clientToken = clientToken(bearer, player)
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", player.hashes[operation] ?: KNOWN_HASHES.getValue(operation))
                }
            }
        }.toString()
        val request = Request.Builder().url(PATHFINDER)
            .post(body.toRequestBody("application/json;charset=UTF-8".toMediaType()))
            .header("Authorization", "Bearer ${bearer.token}")
            .header("Accept", "application/json")
            .header("App-Platform", "WebPlayer")
            // Without the web player's origin (and a client token) Spotify answers 403 "Client/request not allowed".
            .header("Origin", WEB_PLAYER)
            .header("Referer", "$WEB_PLAYER/")
            .header("User-Agent", USER_AGENT)
            .apply { if (clientToken != null) header("Client-Token", clientToken) }
            .build()
        val (code, text) = runInterruptible(Dispatchers.IO) {
            Http.client.newCall(request).execute().use { it.code to it.body?.string() }
        }
        if (code == 401 && !retried) {
            access = null
            client = null
            return query(operation, cookie, variables, true)
        }
        val root = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val errors = (root?.get("errors") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("message") }
        // A rebuilt web player retires the old hashes: read them again once.
        if (errors.any { it.contains("PersistedQueryNotFound", ignoreCase = true) } && !retried) {
            lock.withLock { webPlayer = null; readWebPlayer(force = true) }
            return query(operation, cookie, variables, true)
        }
        if (code !in 200..299) throw IOException("Spotify $operation answered $code")
        return root ?: throw IOException("Spotify $operation answered with something other than JSON")
    }

    // ---- Tokens ---------------------------------------------------------------------

    private suspend fun accessToken(cookie: String): Access = lock.withLock {
        access?.takeIf { it.cookie == cookie && System.currentTimeMillis() < it.expiresAt - 30_000 }?.let { return@withLock it }
        access = null
        client = null
        if (System.currentTimeMillis() < retryAfter) throw IOException("Spotify wouldn't give a token; trying again shortly")
        for (force in listOf(false, true)) {
            val player = readWebPlayer(force)
            val code = totp(player.secret.codes, System.currentTimeMillis())
            val url = "$WEB_PLAYER/api/token".toHttpUrl().newBuilder()
                .addQueryParameter("reason", "init")
                .addQueryParameter("productType", "web-player")
                .addQueryParameter("totp", code)
                .addQueryParameter("totpServer", code)
                .addQueryParameter("totpVer", player.secret.version.toString())
                .build()
            val request = Request.Builder().url(url)
                .header("Cookie", "sp_dc=$cookie")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "$WEB_PLAYER/")
                .build()
            val (status, text) = runInterruptible(Dispatchers.IO) {
                Http.client.newCall(request).execute().use { it.code to it.body?.string() }
            }
            val body = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            // The web player reads this flag to learn its secret has been retired.
            if (body?.get("totpVerExpired") != null && !force) continue
            if (status == 401) throw SignedOut("Your Spotify sign-in has expired")
            if ((body?.get("isAnonymous") as? JsonPrimitive)?.booleanOrNull == true) throw SignedOut("Not signed in to Spotify")
            val token = body?.text("accessToken")
            if (token != null) {
                val expires = (body["accessTokenExpirationTimestampMs"] as? JsonPrimitive)?.longOrNull ?: (System.currentTimeMillis() + 3_600_000)
                return@withLock Access(token, expires, body.text("clientId"), cookie).also { access = it }
            }
            Log.w(TAG, "token answer $status without a token")
        }
        retryAfter = System.currentTimeMillis() + RETRY_MS
        throw IOException("Spotify wouldn't give a token")
    }

    /** The client token the web player sends along; best effort, a query without it is still tried. */
    private suspend fun clientToken(bearer: Access, player: WebPlayer): String? {
        client?.takeIf { System.currentTimeMillis() < it.expiresAt - 30_000 }?.let { return it.token }
        val clientId = bearer.clientId ?: return null
        val body = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", player.clientVersion)
                put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "windows")
                    put("os_version", "unknown")
                    put("device_id", deviceId())
                    put("device_type", "computer")
                }
            }
        }.toString()
        return runCatching {
            // The bare type, deliberately: clienttoken answers 400 to a charset suffix.
            val request = Request.Builder().url(CLIENT_TOKEN)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
            val text = runInterruptible(Dispatchers.IO) { Http.client.newCall(request).execute().use { it.body?.string() } }
            val root = json.parseToJsonElement(text.orEmpty()).jsonObject
            if (root.text("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") return@runCatching null
            val granted = root["granted_token"] as? JsonObject ?: return@runCatching null
            val token = granted.text("token") ?: return@runCatching null
            val seconds = (granted["expires_after_seconds"] as? JsonPrimitive)?.longOrNull ?: 3_600
            client = ClientToken(token, System.currentTimeMillis() + seconds * 1000)
            token
        }.onFailure { Log.w(TAG, "client token failed", it) }.getOrNull()
    }

    private fun deviceId(): String {
        val file = dir?.let { File(it, "spotify-device-id") }
        file?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length == 32 }?.let { return it }
        return UUID.randomUUID().toString().replace("-", "").also { id -> runCatching { file?.writeText(id) } }
    }

    // ---- The web player's own values --------------------------------------------------

    private suspend fun webPlayer(): WebPlayer = lock.withLock { readWebPlayer(force = false) }

    /** Callers hold [lock]. Kept on disk too: the script is 4.5 MB. */
    private suspend fun readWebPlayer(force: Boolean): WebPlayer {
        val file = dir?.let { File(it, "spotify-web-player.json") }
        if (!force) {
            val cached = webPlayer ?: file?.takeIf { it.exists() }
                ?.let { f -> runCatching { json.decodeFromString(WebPlayer.serializer(), f.readText()) }.getOrNull() }
            if (cached != null && System.currentTimeMillis() - cached.at < if (cached.complete) FOUND_TTL_MS else MISSED_TTL_MS) {
                webPlayer = cached
                return cached
            }
        }
        val html = text("$WEB_PLAYER/").orEmpty()
        val clientVersion = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""").find(html)?.groupValues?.get(1)
            ?.let { runCatching { String(java.util.Base64.getDecoder().decode(it)) }.getOrNull() }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject.text("clientVersion") }.getOrNull() }
        val bundleUrl = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[0-9a-f]+\.js""").find(html)?.value
        val bundle = bundleUrl?.let { text(it) }.orEmpty()
        // searchTracks is declared in the search page's chunk, named in the bundle's chunk table.
        var searchChunk = ""
        Regex("""(\d+):"xpui-routes-search"""").find(bundle)?.groupValues?.get(1)?.let { id ->
            for (m in Regex("""\b$id:"([0-9a-f]{8})"""").findAll(bundle)) {
                searchChunk = text("${CDN}xpui-routes-search.${m.groupValues[1]}.js").orEmpty()
                if (searchChunk.isNotEmpty()) break
            }
        }
        val secret = secretsIn(bundle).firstOrNull()
        val found = KNOWN_HASHES.keys.associateWith { op -> queryHashIn(bundle, op) ?: queryHashIn(searchChunk, op) }
        val complete = secret != null && clientVersion != null && found.values.all { it != null }
        if (!complete) Log.w(TAG, "web player read partly: version=${clientVersion != null} secret=${secret?.version} missing=${found.filterValues { it == null }.keys}")
        val read = WebPlayer(
            clientVersion ?: "1.2.77.0",
            secret ?: KNOWN_SECRET,
            KNOWN_HASHES.mapValues { (op, known) -> found[op] ?: known },
            complete,
            System.currentTimeMillis(),
        )
        webPlayer = read
        runCatching { file?.writeText(json.encodeToString(WebPlayer.serializer(), read)) }
        return read
    }

    private suspend fun text(url: String): String? = runCatching {
        runInterruptible(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }
    }.getOrNull()

    /** `{secret:'…',version:61}` as the script declares them, newest first. */
    internal fun secretsIn(script: String): List<Secret> =
        Regex("""\{secret:(['"])((?:\\.|(?!\1).)*)\1,version:(\d+)\}""").findAll(script)
            .map { m -> Secret(m.groupValues[3].toInt(), unescapeJs(m.groupValues[2]).map { it.code }) }
            .sortedByDescending { it.version }
            .toList()

    /** `"canvas","query","<64 hex>"` (or `"mutation"`) in the script. */
    internal fun queryHashIn(script: String, operation: String): String? =
        Regex("""["']$operation["']\s*,\s*["'](?:query|mutation)["']\s*,\s*["']([0-9a-f]{64})["']""").find(script)?.groupValues?.get(1)

    /** A JavaScript string literal's body with its escapes read. */
    internal fun unescapeJs(body: String): String =
        Regex("""\\(u\{[0-9a-fA-F]+\}|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\s\S])""").replace(body) { m ->
            val esc = m.groupValues[1]
            when {
                esc.startsWith("u") && esc.length > 1 -> String(Character.toChars(esc.drop(1).trim('{', '}').toInt(16)))
                esc.startsWith("x") && esc.length == 3 -> esc.drop(1).toInt(16).toChar().toString()
                else -> when (esc) {
                    "n" -> "\n"; "r" -> "\r"; "t" -> "\t"; "b" -> "\b"; "f" -> "\u000C"; "v" -> "\u000B"; "0" -> "\u0000"
                    else -> esc
                }
            }
        }

    /** Six digits over SHA-1 in thirty-second steps, from the secret's codes XORed with their place. */
    internal fun totp(codes: List<Int>, epochMs: Long): String {
        val key = codes.mapIndexed { i, c -> c xor ((i % 33) + 9) }.joinToString("").toByteArray()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(epochMs / 1000 / 30).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    internal fun JsonElement?.path(vararg keys: String): JsonElement? =
        keys.fold(this) { at, key -> (at as? JsonObject)?.get(key) }

    internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
