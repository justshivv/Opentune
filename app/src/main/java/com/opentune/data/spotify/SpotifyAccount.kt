package com.opentune.data.spotify

import android.content.Context
import android.net.Uri
import com.opentune.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object SpotifyAccount {
    private lateinit var vault: SpotifyVault
    private val lock = Mutex()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val client by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build() }
    fun init(context: Context) {
        vault = SpotifyVault(context)
        _connected.value = vault.read().optString("refresh_token").isNotBlank()
    }
    fun clientId(): String = if (::vault.isInitialized) vault.read().optString("client_id") else ""

    suspend fun begin(clientId: String): String = withContext(Dispatchers.IO) { lock.withLock {
        require(SpotifyOAuth.validClientId(clientId)) { "Enter the 32-character Spotify client ID" }
        val state = SpotifyOAuth.randomValue()
        val verifier = SpotifyOAuth.randomValue()
        // A new attempt invalidates both the previous callback and previous account.
        vault.write(JSONObject().put("client_id", clientId).put("state", state).put("verifier", verifier)
            .put("started", System.currentTimeMillis()))
        _connected.value = false
        _status.value = "Finish signing in with Spotify in your browser"
        SpotifyOAuth.authorize(clientId, state, verifier)
    } }

    fun isCallback(uri: Uri): Boolean = uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
        ((uri.scheme == "https" && uri.host == "justshivv.github.io" && uri.path == "/Opentune/spotify/callback/") ||
            (uri.scheme == "opentune" && uri.host == "spotify-callback" && uri.path.isNullOrEmpty()))

    suspend fun complete(uri: Uri): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        if (!isCallback(uri)) return@withLock false
        val data = vault.read()
        val state = data.optString("state")
        if (!SpotifyOAuth.matchesAttempt(uri.getQueryParameters("state"), state, data.optLong("started"), System.currentTimeMillis())) {
            _status.value = "This sign-in link expired or doesn't match. Start again in OpenTune."
            return@withLock false
        }
        val verifier = data.optString("verifier")
        data.remove("state"); data.remove("verifier"); data.remove("started")
        vault.write(data) // consume exactly once, including denied and failed exchanges
        if (uri.getQueryParameter("error") != null) {
            _status.value = "Spotify sign-in was cancelled"
            return@withLock false
        }
        val code = uri.getQueryParameters("code").singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 4096 }
        if (code == null) { _status.value = "Spotify didn't return an authorization code"; return@withLock false }
        try {
            val result = token(FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", SpotifyOAuth.REDIRECT).add("client_id", data.getString("client_id"))
                .add("code_verifier", verifier).build())
            saveTokens(data, result)
            _status.value = "Spotify connected"
            true
        } catch (_: IOException) {
            _status.value = "Couldn't finish Spotify sign-in. Check your connection and client setup, then try again."
            false
        }
    } }

    suspend fun disconnect() = withContext(Dispatchers.IO) { lock.withLock {
        vault.clear(); _connected.value = false; _status.value = null
    } }

    internal suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        require(SpotifyLibrary.allowedUrl(url)) { "Unexpected Spotify API address" }
        var data = vault.read()
        if (data.optString("refresh_token").isBlank()) throw IOException("Connect Spotify first")
        if (data.optLong("expires_at") <= System.currentTimeMillis() + 60_000) {
            val refreshed = try {
                token(FormBody.Builder().add("grant_type", "refresh_token")
                    .add("refresh_token", data.getString("refresh_token")).add("client_id", data.getString("client_id")).build())
            } catch (e: TokenRejected) {
                vault.clear(); _connected.value = false; _status.value = "Spotify connection expired. Sign in again."
                throw IOException("Spotify connection expired. Sign in again.")
            }
            saveTokens(data, refreshed)
            data = vault.read()
        }
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${data.getString("access_token")}").build()
        client.newCall(req).execute().use { response ->
            if (response.code == 401) {
                data.put("expires_at", 0); vault.write(data)
                throw IOException("Spotify needs a refreshed session. Try again.")
            }
            if (response.code == 403) throw IOException("Spotify denied access. Check this account is allowed in your Developer app.")
            if (response.code == 429) throw IOException("Spotify is limiting requests. Try again later.")
            if (!response.isSuccessful) throw IOException("Spotify answered HTTP ${response.code}")
            JSONObject(response.body?.string() ?: throw IOException("Empty Spotify response"))
        }
    } }

    private class TokenRejected : IOException()
    private fun token(body: FormBody): JSONObject = client.newCall(Request.Builder()
        .url("https://accounts.spotify.com/api/token").post(body).build()).execute().use {
        if (it.code == 400 || it.code == 401) throw TokenRejected()
        if (!it.isSuccessful) throw IOException("Spotify token request failed")
        val data = JSONObject(it.body?.string() ?: throw IOException("Empty Spotify response"))
        if (data.optString("access_token").isBlank()) throw IOException("Missing Spotify access token")
        data
    }
    private fun saveTokens(data: JSONObject, result: JSONObject) {
        data.put("access_token", result.getString("access_token"))
        result.optString("refresh_token").takeIf { it.isNotBlank() }?.let { data.put("refresh_token", it) }
        if (data.optString("refresh_token").isBlank()) throw IOException("Missing Spotify refresh token")
        data.put("expires_at", System.currentTimeMillis() + result.optLong("expires_in", 3600).coerceIn(1, 86400) * 1000)
        vault.write(data); _connected.value = true
    }
}
