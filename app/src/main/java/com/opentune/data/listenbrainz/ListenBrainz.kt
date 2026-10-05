package com.opentune.data.listenbrainz

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.opentune.BuildConfig
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ListenBrainz, the open alternative to Last.fm run by the MetaBrainz
 * Foundation: listens are sent with the user token from
 * listenbrainz.org/settings, and its collaborative-filtering picks come back
 * as recommendations for Home.
 *
 * The token is a credential, so it lives in app-private storage left out of
 * backups. Listens that can't be sent wait in a queue and go with the next.
 */
object ListenBrainz {
    private const val API = "https://api.listenbrainz.org"
    private const val TAG = "ListenBrainz"
    private const val MAX_QUEUED = 500
    private const val BATCH = 100

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private var prefs: SharedPreferences? = null

    data class Account(val user: String)

    /** A recommended recording, as ListenBrainz names it. */
    data class Pick(val title: String, val artist: String, val album: String?, val coverReleaseMbid: String?)

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _queued = MutableStateFlow(0)
    val queued: StateFlow<Int> = _queued.asStateFlow()

    @Serializable
    internal data class Listen(
        val artist: String,
        val track: String,
        val album: String? = null,
        val listenedAt: Long,
        val durationMs: Long? = null,
        val videoId: String? = null,
    )

    fun init(context: Context) {
        val p = context.getSharedPreferences("listenbrainz", Context.MODE_PRIVATE)
        prefs = p
        p.getString(K_USER, null)?.takeIf { p.getString(K_TOKEN, null) != null }?.let { _account.value = Account(it) }
        _queued.value = pending().size
    }

    /** Checks [token] with ListenBrainz and remembers it with the user it belongs to. */
    suspend fun signIn(token: String): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = token.trim()
            val body = request(Request.Builder().url("$API/1/validate-token").header("Authorization", "Token $clean").build())
                ?: throw IOException("ListenBrainz didn't answer")
            if ((body["valid"] as? JsonPrimitive)?.booleanOrNull != true) throw IOException("That token isn't valid")
            val user = (body["user_name"] as? JsonPrimitive)?.contentOrNull ?: throw IOException("ListenBrainz didn't name the user")
            prefs?.edit {
                putString(K_TOKEN, clean)
                putString(K_USER, user)
            }
            Account(user).also { _account.value = it }
        }
    }

    fun signOut() {
        prefs?.edit { clear() }
        _account.value = null
        _queued.value = 0
    }

    /** "Playing now" on the user's profile; nothing is queued if it fails. */
    fun playingNow(song: Song, durationMs: Long) {
        val token = token() ?: return
        if (song.artist.isBlank()) return
        scope.launch {
            runCatching { submit(token, "playing_now", listOf(listenOf(song, 0, durationMs))) }
                .onFailure { Log.w(TAG, "playing now failed", it) }
        }
    }

    /** Records a listen that started at [startedAtMs], then sends everything waiting. */
    fun listened(song: Song, startedAtMs: Long, durationMs: Long) {
        if (token() == null || song.artist.isBlank()) return
        savePending((pending() + listenOf(song, startedAtMs / 1000, durationMs)).takeLast(MAX_QUEUED))
        flush()
    }

    private fun flush() {
        scope.launch {
            sending.withLock {
                val token = token() ?: return@withLock
                while (true) {
                    val batch = pending().take(BATCH)
                    if (batch.isEmpty()) break
                    val sent = runCatching { submit(token, if (batch.size == 1) "single" else "import", batch) }
                        .onFailure { Log.w(TAG, "submit failed; ${batch.size} kept for later", it) }
                        .isSuccess
                    if (!sent) break
                    savePending(pending().drop(batch.size))
                }
            }
        }
    }

    /**
     * The user's recommended recordings, named. Empty until ListenBrainz has
     * enough listens to work from, which takes a while for a new account.
     */
    suspend fun recommendations(count: Int = 25): List<Pick> = withContext(Dispatchers.IO) {
        val user = _account.value?.user ?: return@withContext emptyList()
        val recs = request(Request.Builder().url("$API/1/cf/recommendation/user/$user/recording?count=$count").build())
            ?: return@withContext emptyList()
        val mbids = (recs["payload"] as? JsonObject)?.get("mbids").array()
            .mapNotNull { (it as? JsonObject)?.get("recording_mbid").string() }
        if (mbids.isEmpty()) return@withContext emptyList()
        val meta = request(
            Request.Builder().url("$API/1/metadata/recording/?recording_mbids=${mbids.joinToString(",")}&inc=artist%20release").build(),
        ) ?: return@withContext emptyList()
        mbids.mapNotNull { id -> (meta[id] as? JsonObject)?.let(::pick) }
    }

    internal fun pick(entry: JsonObject): Pick? {
        val title = (entry["recording"] as? JsonObject)?.get("name").string() ?: return null
        val artist = (entry["artist"] as? JsonObject)?.get("name").string() ?: return null
        val release = entry["release"] as? JsonObject
        return Pick(title, artist, release?.get("name").string(), release?.get("caa_release_mbid").string())
    }

    internal fun listenOf(song: Song, listenedAt: Long, durationMs: Long) = Listen(
        artist = song.artist,
        track = song.title,
        album = song.albumName,
        listenedAt = listenedAt,
        durationMs = durationMs.takeIf { it > 0 },
        videoId = song.videoId.takeIf(::isYouTubeId),
    )

    /** The submit-listens body: listened_at for real listens, none for "playing now". */
    internal fun payload(type: String, listens: List<Listen>): JsonObject = buildJsonObject {
        put("listen_type", type)
        put("payload", buildJsonArray {
            listens.forEach { l ->
                add(buildJsonObject {
                    if (type != "playing_now") put("listened_at", l.listenedAt)
                    put("track_metadata", buildJsonObject {
                        put("artist_name", l.artist)
                        put("track_name", l.track)
                        l.album?.let { put("release_name", it) }
                        put("additional_info", buildJsonObject {
                            put("media_player", "OpenTune")
                            put("submission_client", "OpenTune")
                            put("submission_client_version", BuildConfig.VERSION_NAME)
                            l.durationMs?.let { put("duration_ms", it) }
                            l.videoId?.let {
                                put("music_service", "music.youtube.com")
                                put("origin_url", "https://music.youtube.com/watch?v=$it")
                            }
                        })
                    })
                })
            }
        })
    }

    private fun submit(token: String, type: String, listens: List<Listen>) {
        val body = payload(type, listens).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$API/1/submit-listens").header("Authorization", "Token $token").post(body).build()
        Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("ListenBrainz answered ${r.code}")
        }
    }

    /** A GET or POST's JSON body; null for 204 (nothing yet). */
    private fun request(request: Request): JsonObject? =
        Http.client.newCall(request).execute().use { r ->
            if (r.code == 204) return null
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("ListenBrainz answered ${r.code}")
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        }

    private fun token(): String? = prefs?.getString(K_TOKEN, null)

    private fun pending(): List<Listen> =
        prefs?.getString(K_QUEUE, null)?.let { runCatching { json.decodeFromString(ListSerializer(Listen.serializer()), it) }.getOrNull() }.orEmpty()

    private fun savePending(list: List<Listen>) {
        prefs?.edit { putString(K_QUEUE, json.encodeToString(ListSerializer(Listen.serializer()), list)) }
        _queued.value = list.size
    }

    private fun kotlinx.serialization.json.JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun kotlinx.serialization.json.JsonElement?.array(): List<kotlinx.serialization.json.JsonElement> = (this as? JsonArray).orEmpty()

    private const val K_TOKEN = "token"
    private const val K_USER = "user"
    private const val K_QUEUE = "queue"
}
