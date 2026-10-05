package com.opentune.data.lastfm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.model.Song
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request

/**
 * Last.fm scrobbling with the listener's own API account: they create an API
 * key and secret at last.fm/api, sign in once with their username and
 * password (exchanged for a session key; the password is never stored), and
 * every song heard long enough is scrobbled. Scrobbles that can't be sent
 * wait in a queue and go with the next one.
 *
 * The session key is a credential, so it lives in app-private storage and is
 * left out of backups, like the YouTube cookie.
 */
object LastFm {
    private const val API = "https://ws.audioscrobbler.com/2.0/"
    private const val TAG = "LastFm"
    private const val MAX_QUEUED = 500

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private var prefs: SharedPreferences? = null

    data class Account(val user: String)

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _queued = MutableStateFlow(0)
    val queued: StateFlow<Int> = _queued.asStateFlow()

    @Serializable
    private data class Scrobble(val artist: String, val track: String, val album: String? = null, val timestamp: Long, val durationS: Long? = null)

    fun init(context: Context) {
        val p = context.getSharedPreferences("lastfm", Context.MODE_PRIVATE)
        prefs = p
        p.getString(K_USER, null)?.takeIf { p.getString(K_SESSION, null) != null }?.let { _account.value = Account(it) }
        _queued.value = pending().size
    }

    val apiKey: String get() = prefs?.getString(K_KEY, null).orEmpty()
    val secret: String get() = prefs?.getString(K_SECRET, null).orEmpty()

    /** Exchanges the listener's login for a session key, with their own API key and secret. */
    suspend fun signIn(apiKey: String, secret: String, username: String, password: String): Result<Unit> = runCatching {
        val response = call(
            mapOf("method" to "auth.getMobileSession", "username" to username, "password" to password),
            apiKey.trim(), secret.trim(), session = null,
        )
        val session = response["session"]?.jsonObject ?: error(errorOf(response) ?: "Last.fm didn't return a session")
        val key = session["key"]?.jsonPrimitive?.content ?: error("Last.fm didn't return a session")
        val name = session["name"]?.jsonPrimitive?.content ?: username
        prefs?.edit {
            putString(K_KEY, apiKey.trim())
            putString(K_SECRET, secret.trim())
            putString(K_SESSION, key)
            putString(K_USER, name)
        }
        _account.value = Account(name)
    }

    fun signOut() {
        prefs?.edit {
            remove(K_SESSION)
            remove(K_USER)
            remove(K_QUEUE)
        }
        _account.value = null
        _queued.value = 0
    }

    /** "Now playing" on the listener's profile; nothing is queued if it fails. */
    fun nowPlaying(song: Song, durationMs: Long) {
        val session = prefs?.getString(K_SESSION, null) ?: return
        if (song.artist.isBlank()) return
        scope.launch {
            runCatching {
                call(
                    buildMap {
                        put("method", "track.updateNowPlaying")
                        put("artist", primaryArtist(song.artist))
                        put("track", song.title)
                        song.albumName?.let { put("album", it) }
                        if (durationMs > 0) put("duration", (durationMs / 1000).toString())
                    },
                    apiKey, secret, session,
                )
            }.onFailure { Log.w(TAG, "now playing failed", it) }
        }
    }

    /** Records a listen that started at [startedAtMs] (wall clock), then sends everything waiting. */
    fun scrobble(song: Song, startedAtMs: Long, durationMs: Long) {
        if (prefs?.getString(K_SESSION, null) == null || song.artist.isBlank()) return
        val item = Scrobble(primaryArtist(song.artist), song.title, song.albumName, startedAtMs / 1000, (durationMs / 1000).takeIf { it > 0 })
        savePending((pending() + item).takeLast(MAX_QUEUED))
        flush()
    }

    private fun flush() {
        scope.launch {
            sending.withLock {
                val session = prefs?.getString(K_SESSION, null) ?: return@withLock
                // Last.fm takes up to 50 scrobbles in one request.
                while (true) {
                    val batch = pending().take(50)
                    if (batch.isEmpty()) break
                    val params = mutableMapOf("method" to "track.scrobble")
                    batch.forEachIndexed { i, s ->
                        params["artist[$i]"] = s.artist
                        params["track[$i]"] = s.track
                        params["timestamp[$i]"] = s.timestamp.toString()
                        s.album?.let { params["album[$i]"] = it }
                        s.durationS?.let { params["duration[$i]"] = it.toString() }
                    }
                    val ok = runCatching { call(params, apiKey, secret, session) }
                        .onFailure { Log.w(TAG, "scrobble failed; ${batch.size} kept for later", it) }
                        .getOrNull()
                        ?.let { it["scrobbles"] != null }
                        ?: false
                    if (!ok) break
                    savePending(pending().drop(batch.size))
                }
            }
        }
    }

    /** The first of "A, B & C": Last.fm matches better on the main artist. */
    private fun primaryArtist(artist: String): String =
        artist.split(", ", " & ", " x ", " feat. ", " ft. ").first().trim().ifEmpty { artist }

    private fun pending(): List<Scrobble> =
        prefs?.getString(K_QUEUE, null)?.let { runCatching { json.decodeFromString(ListSerializer(Scrobble.serializer()), it) }.getOrNull() }.orEmpty()

    private fun savePending(list: List<Scrobble>) {
        prefs?.edit { putString(K_QUEUE, json.encodeToString(ListSerializer(Scrobble.serializer()), list)) }
        _queued.value = list.size
    }

    /** A signed POST, as every write method needs: md5 of the sorted parameters and the secret. */
    private fun call(params: Map<String, String>, apiKey: String, secret: String, session: String?): JsonObject {
        val all = buildMap {
            putAll(params)
            put("api_key", apiKey)
            session?.let { put("sk", it) }
        }
        val signature = md5(all.toSortedMap().entries.joinToString("") { it.key + it.value } + secret)
        val body = FormBody.Builder().apply {
            all.forEach { (k, v) -> add(k, v) }
            add("api_sig", signature)
            add("format", "json")
        }.build()
        Http.client.newCall(Request.Builder().url(API).post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw IOException("Last.fm answered ${r.code}")
            if (obj["error"] != null) throw IOException(errorOf(obj))
            return obj
        }
    }

    private fun errorOf(obj: JsonObject): String? = obj["message"]?.jsonPrimitive?.content

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private const val K_KEY = "api_key"
    private const val K_SECRET = "secret"
    private const val K_SESSION = "session"
    private const val K_USER = "user"
    private const val K_QUEUE = "queue"
}
