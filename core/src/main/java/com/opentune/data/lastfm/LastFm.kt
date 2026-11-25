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
import kotlinx.coroutines.ensureActive
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

/** Last.fm mobile authentication exchanges the password once for a private session key.
 * The build supplies OpenTune's application credentials. Existing sessions keep their own
 * signing key pair so an app update never breaks their queued scrobbles.
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

    private val bundledLogin: Boolean get() = com.opentune.core.BuildConfig.LASTFM_API_KEY.isNotBlank() && com.opentune.core.BuildConfig.LASTFM_API_SECRET.isNotBlank()
    private val loginKey: String get() = if (bundledLogin) com.opentune.core.BuildConfig.LASTFM_API_KEY else apiKey
    private val loginSecret: String get() = if (bundledLogin) com.opentune.core.BuildConfig.LASTFM_API_SECRET else secret
    val canSignIn: Boolean get() = loginKey.isNotBlank() && loginSecret.isNotBlank()

    suspend fun signIn(username: String, password: String): Result<Unit> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            check(canSignIn) { "Last.fm sign-in isn't available in this build yet." }
            val apiKey = loginKey
            val secret = loginSecret
            val response = call(
                mapOf("method" to "auth.getMobileSession", "username" to username, "password" to password),
                apiKey, secret, session = null,
            )
            val session = response["session"]?.jsonObject ?: error(errorOf(response) ?: "Last.fm didn't return a session")
            val key = session["key"]?.jsonPrimitive?.content ?: error("Last.fm didn't return a session")
            val name = session["name"]?.jsonPrimitive?.content ?: username
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            prefs?.edit {
                putString(K_KEY, apiKey)
                putString(K_SECRET, secret)
                putString(K_SESSION, key)
                putString(K_USER, name)
            }
            _account.value = Account(name)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * This phone's sign-in, for another of the person's devices the person chose to sign in
     * too (sealed end to end by the pairing). A session key works only with the application
     * key that made it, so that key goes with it. Its secret goes only when it's the person's
     * own application: OpenTune's is in every OpenTune build already and never needs to travel.
     */
    fun shared(): com.opentune.data.account.PairingExtras.LastFm? {
        val p = prefs ?: return null
        val session = p.getString(K_SESSION, null) ?: return null
        val user = p.getString(K_USER, null) ?: return null
        val key = apiKey.takeIf { it.isNotBlank() } ?: return null
        val own = key != com.opentune.core.BuildConfig.LASTFM_API_KEY
        return com.opentune.data.account.PairingExtras.LastFm(session, user, key, secret.takeIf { own && it.isNotBlank() })
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

    /** A song's write-up on Last.fm: its listeners' wiki summary, and the page it's from. */
    data class Wiki(val text: String, val url: String)

    /**
     * The Last.fm wiki summary for [title] by [artist] (track.getInfo, which needs only the
     * application key the app already has; no sign-in). Null when there's no key, no
     * write-up, or Last.fm can't be reached.
     */
    suspend fun trackWiki(artist: String, title: String): Wiki? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val key = apiKey.ifBlank { com.opentune.core.BuildConfig.LASTFM_API_KEY }
        if (key.isBlank() || artist.isBlank() || title.isBlank()) return@withContext null
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("ws.audioscrobbler.com").addPathSegment("2.0").addPathSegment("")
            .addQueryParameter("method", "track.getInfo").addQueryParameter("api_key", key)
            .addQueryParameter("artist", primaryArtist(artist)).addQueryParameter("track", title)
            .addQueryParameter("format", "json").build()
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) parseWiki(r.body?.string().orEmpty()) else null
            }
        }.onFailure { Log.w(TAG, "track info failed", it) }.getOrNull()
    }

    /** The wiki summary in a track.getInfo answer, as plain text without Last.fm's own "Read more" link. */
    internal fun parseWiki(body: String): Wiki? {
        val track = runCatching { json.parseToJsonElement(body).jsonObject["track"]?.jsonObject }.getOrNull() ?: return null
        val wiki = track["wiki"] as? JsonObject ?: return null
        val summary = (wiki["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val text = summary
            .replace(Regex("""<a\s[^>]*>\s*Read more on Last\.fm\s*</a>\.?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .trim()
        if (text.length < 40) return null
        val page = (track["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.startsWith("https://www.last.fm/") } ?: return null
        return Wiki(text, page)
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
