package com.opentune.data.spotify

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import androidx.core.net.toUri
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Request

/**
 * Spotify sign-in through Spotify's own login page (OAuth with PKCE), to
 * read your playlists and liked songs so they can be brought over. Nothing
 * plays from Spotify: imported songs are found on YouTube Music.
 *
 * Spotify only lets registered apps sign in, so you register one for
 * yourself (free, at developer.spotify.com) and give OpenTune its Client
 * ID. No client secret is involved. Tokens stay in app-private storage
 * left out of backups.
 */
object Spotify {
    private const val TAG = "Spotify"
    const val REDIRECT_URI = "opentune://spotify-auth"
    private const val AUTHORIZE = "https://accounts.spotify.com/authorize"
    private const val TOKEN = "https://accounts.spotify.com/api/token"
    private const val API = "https://api.spotify.com/v1"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"

    data class Account(val name: String)
    data class Playlist(val id: String, val name: String, val owner: String, val tracks: Int, val image: String?)
    data class Track(val title: String, val artists: List<String>, val album: String?, val durationMs: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val refreshing = Mutex()
    private var prefs: SharedPreferences? = null

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)
        prefs = p
        p.getString(K_NAME, null)?.takeIf { p.getString(K_REFRESH, null) != null }?.let { _account.value = Account(it) }
    }

    val clientId: String? get() = prefs?.getString(K_CLIENT, null)

    /**
     * The login page to open in the browser. Remembers the PKCE verifier and
     * state for [finishSignIn], which receives Spotify's redirect.
     */
    fun authorizeUrl(clientId: String): Uri {
        val verifier = randomString(64)
        val state = randomString(16)
        prefs?.edit {
            putString(K_CLIENT, clientId.trim())
            putString(K_VERIFIER, verifier)
            putString(K_STATE, state)
        }
        return AUTHORIZE.toUri().buildUpon()
            .appendQueryParameter("client_id", clientId.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build()
    }

    fun isRedirect(uri: Uri?) = uri?.scheme == "opentune" && uri.host == "spotify-auth"

    /** Trades the code in Spotify's redirect for tokens, and reads who signed in. */
    suspend fun finishSignIn(redirect: Uri): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            val p = prefs ?: error("Not ready")
            redirect.getQueryParameter("error")?.let { throw IOException(if (it == "access_denied") "Sign-in was cancelled" else "Spotify said: $it") }
            if (redirect.getQueryParameter("state") != p.getString(K_STATE, null)) throw IOException("This sign-in link doesn't match; try again")
            val code = redirect.getQueryParameter("code") ?: throw IOException("Spotify sent no code")
            val client = p.getString(K_CLIENT, null) ?: throw IOException("No Client ID")
            val verifier = p.getString(K_VERIFIER, null) ?: throw IOException("Sign-in expired; try again")
            token(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .add("client_id", client)
                    .add("code_verifier", verifier)
                    .build(),
            )
            p.edit { remove(K_VERIFIER); remove(K_STATE) }
            val me = get("$API/me")
            val name = me.str("display_name") ?: me.str("id") ?: "Spotify user"
            p.edit { putString(K_NAME, name) }
            Account(name).also { _account.value = it }
        }.onFailure { Log.w(TAG, "sign-in failed", it) }
    }

    fun signOut() {
        val client = clientId
        prefs?.edit { clear(); client?.let { putString(K_CLIENT, it) } }
        _account.value = null
    }

    /** Every playlist in the account's library, its own and followed ones. */
    suspend fun playlists(): List<Playlist> = withContext(Dispatchers.IO) {
        pages("$API/me/playlists?limit=50").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Playlist(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name").orEmpty(),
                owner = (o["owner"] as? JsonObject)?.str("display_name").orEmpty(),
                tracks = ((o["tracks"] as? JsonObject)?.get("total") as? JsonPrimitive)?.intOrNull ?: 0,
                image = ((o["images"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("url"),
            )
        }
    }

    suspend fun playlistTracks(id: String): List<Track> = withContext(Dispatchers.IO) {
        pages("$API/playlists/$id/tracks?limit=100&fields=items(track(name,type,duration_ms,artists(name),album(name))),next").mapNotNull(::track)
    }

    suspend fun likedTracks(): List<Track> = withContext(Dispatchers.IO) {
        pages("$API/me/tracks?limit=50").mapNotNull(::track)
    }

    /** A playlist or library item's track; podcast episodes and local files left out. */
    internal fun track(item: kotlinx.serialization.json.JsonElement): Track? {
        val t = (item as? JsonObject)?.get("track") as? JsonObject ?: return null
        if ((t.str("type") ?: "track") != "track") return null
        val title = t.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return Track(
            title = title,
            artists = (t["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
            album = (t["album"] as? JsonObject)?.str("name"),
            durationMs = (t["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0,
        )
    }

    /** Follows "next" links to the end of a paged list. */
    private suspend fun pages(first: String): List<kotlinx.serialization.json.JsonElement> {
        val out = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var next: String? = first
        var count = 0
        while (next != null && count < MAX_PAGES) {
            val page = get(next)
            out += (page["items"] as? JsonArray).orEmpty()
            next = page.str("next")
            count++
        }
        return out
    }

    private suspend fun get(url: String): JsonObject {
        val response = call(url, accessToken())
        if (response.first == 401) return call(url, accessToken(force = true)).let { (code, body) ->
            if (code !in 200..299) throw IOException("Spotify answered $code")
            body
        }
        if (response.first == 429) throw IOException("Spotify asks to slow down; try again in a minute")
        if (response.first !in 200..299) throw IOException("Spotify answered ${response.first}")
        return response.second
    }

    private fun call(url: String, token: String): Pair<Int, JsonObject> =
        Http.client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            r.code to (runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: JsonObject(emptyMap()))
        }

    /** A valid access token, refreshed with the refresh token when it has run out. */
    private suspend fun accessToken(force: Boolean = false): String = refreshing.withLock {
        val p = prefs ?: throw IOException("Not signed in")
        val token = p.getString(K_ACCESS, null)
        if (!force && token != null && System.currentTimeMillis() < p.getLong(K_EXPIRES, 0) - 60_000) return token
        val refresh = p.getString(K_REFRESH, null) ?: throw IOException("Not signed in")
        val client = p.getString(K_CLIENT, null) ?: throw IOException("No Client ID")
        token(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", client).build())
        p.getString(K_ACCESS, null) ?: throw IOException("Spotify gave no token")
    }

    private fun token(form: FormBody) {
        Http.client.newCall(Request.Builder().url(TOKEN).post(form).build()).execute().use { r ->
            val body = runCatching { json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrNull()
            if (!r.isSuccessful || body == null) {
                val reason = body?.str("error_description") ?: body?.str("error") ?: "code ${r.code}"
                if (body?.str("error") == "invalid_grant") {
                    prefs?.edit { remove(K_REFRESH); remove(K_ACCESS) }
                    _account.value = null
                }
                throw IOException("Spotify refused: $reason")
            }
            val access = body.str("access_token") ?: throw IOException("Spotify gave no token")
            val expires = (body["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
            prefs?.edit {
                putString(K_ACCESS, access)
                putLong(K_EXPIRES, System.currentTimeMillis() + expires * 1000)
                // Spotify may or may not send a new refresh token; keep the old one if not.
                body.str("refresh_token")?.let { putString(K_REFRESH, it) }
            }
        }
    }

    internal fun challenge(verifier: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return String(CharArray(length) { chars[random.nextInt(chars.length)] })
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private const val MAX_PAGES = 100
    private const val K_CLIENT = "clientId"
    private const val K_VERIFIER = "verifier"
    private const val K_STATE = "state"
    private const val K_ACCESS = "access"
    private const val K_REFRESH = "refresh"
    private const val K_EXPIRES = "expiresAt"
    private const val K_NAME = "name"
}
