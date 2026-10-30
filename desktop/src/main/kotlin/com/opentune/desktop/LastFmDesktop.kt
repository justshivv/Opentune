package com.opentune.desktop

import com.opentune.core.BuildConfig
import com.opentune.data.Http
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * Last.fm on the computer, as the phone (core/…/lastfm/LastFm.kt) and the web
 * player do it: "now playing" when a song starts, a scrobble once half of it
 * (or four minutes) has been heard, kept in a queue until Last.fm takes it.
 *
 * Sign-in is Last.fm's desktop flow: a token approved on last.fm in the
 * browser, then traded for a session key. No password passes through
 * OpenTune. Kept in ~/.opentune/lastfm.json.
 */
object LastFmDesktop {
    private const val API = "https://ws.audioscrobbler.com/2.0/"
    private const val AUTH_PAGE = "https://www.last.fm/api/auth/"
    private const val MAX_QUEUED = 500
    private const val BATCH = 50
    private const val MIN_TRACK_S = 30.0
    private const val LISTEN_CAP_S = 240.0
    private const val APPROVAL_POLL_MS = 3_000L
    private const val APPROVAL_TIMEOUT_MS = 5 * 60_000L

    @Serializable
    data class Session(val key: String, val user: String, val apiKey: String, val secret: String)

    @Serializable
    data class Scrobble(val artist: String, val track: String, val album: String? = null, val timestamp: Long, val durationS: Long? = null)

    @Serializable
    data class State(
        val session: Session? = null,
        val scrobbling: Boolean = true,
        /** The listener's own Last.fm application, for a build that carries none. */
        val ownKey: String = "",
        val ownSecret: String = "",
        val queue: List<Scrobble> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private var file = File(System.getProperty("user.home"), ".opentune/lastfm.json")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    /** "Waiting for Last.fm…" while a sign-in waits for approval in the browser. */
    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()
    private var approval: Job? = null

    /** For tests: keeps everything in [at] instead. */
    internal fun useFile(at: File) {
        file = at
        _state.value = load()
    }

    val buildHasKeys: Boolean get() = BuildConfig.LASTFM_API_KEY.isNotBlank() && BuildConfig.LASTFM_API_SECRET.isNotBlank()

    private fun loginKeys(): Pair<String, String>? = when {
        buildHasKeys -> BuildConfig.LASTFM_API_KEY to BuildConfig.LASTFM_API_SECRET
        _state.value.ownKey.isNotBlank() && _state.value.ownSecret.isNotBlank() -> _state.value.ownKey to _state.value.ownSecret
        else -> null
    }

    fun setOwnKeys(key: String, secret: String) = update { it.copy(ownKey = key.trim(), ownSecret = secret.trim()) }
    fun setScrobbling(on: Boolean) = update { it.copy(scrobbling = on) }
    fun signOut() {
        cancelSignIn()
        update { it.copy(session = null, queue = emptyList()) }
    }

    /**
     * Opens Last.fm's approval page in the browser and waits for the listener to
     * allow OpenTune there; [done] gets null on success, else what went wrong.
     */
    fun signIn(done: (String?) -> Unit) {
        val keys = loginKeys() ?: return done("Add a Last.fm API key and secret first.")
        cancelSignIn()
        _waiting.value = true
        approval = scope.launch {
            val problem = try {
                val token = call(mapOf("method" to "auth.getToken"), keys).optString("token")
                if (token.isEmpty()) throw IOException("Last.fm didn't give a sign-in token")
                openInBrowser("$AUTH_PAGE?api_key=${keys.first}&token=$token")
                val until = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
                var session: Session? = null
                while (session == null && System.currentTimeMillis() < until) {
                    delay(APPROVAL_POLL_MS)
                    session = try {
                        val s = call(mapOf("method" to "auth.getSession", "token" to token), keys).getJSONObject("session")
                        Session(s.getString("key"), s.optString("name", "your account"), keys.first, keys.second)
                    } catch (e: LastFmError) {
                        // 14: not approved yet. Anything else ends the wait.
                        if (e.code != 14) throw e
                        null
                    }
                }
                if (session == null) throw IOException("Last.fm sign-in timed out. Try again and press “Yes, allow access” on last.fm.")
                update { it.copy(session = session) }
                flush()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Couldn't connect to Last.fm"
            } finally {
                _waiting.value = false
            }
            done(problem)
        }
    }

    fun cancelSignIn() {
        approval?.cancel()
        approval = null
        _waiting.value = false
    }

    private fun openInBrowser(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            .onFailure { throw IOException("Couldn't open the browser. Open $url yourself.") }
    }

    // ---- Requests -------------------------------------------------------------------

    private class LastFmError(val code: Int, message: String) : IOException(message)

    /** api_sig: md5 of the parameters sorted by name, each name then value, and the secret. */
    internal fun signature(params: Map<String, String>, secret: String): String {
        val text = params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
        return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun call(params: Map<String, String>, keys: Pair<String, String>, sk: String? = null): JSONObject {
        val all = buildMap {
            putAll(params)
            put("api_key", keys.first)
            sk?.let { put("sk", it) }
        }
        val body = FormBody.Builder().apply {
            all.forEach { (k, v) -> add(k, v) }
            add("api_sig", signature(all, keys.second))
            add("format", "json")
        }.build()
        Http.client.newCall(Request.Builder().url(API).post(body).build()).execute().use { r ->
            val obj = runCatching { JSONObject(r.body!!.string()) }.getOrNull() ?: throw IOException("Last.fm answered ${r.code}")
            if (obj.has("error")) throw LastFmError(obj.optInt("error"), obj.optString("message", "Last.fm refused the request"))
            return obj
        }
    }

    // ---- Scrobbling -----------------------------------------------------------------

    /** The first of "A, B & C": Last.fm matches better on the main artist. */
    internal fun primaryArtist(artist: String): String =
        artist.split(", ", " & ", " x ", " feat. ", " ft. ").first().trim().ifEmpty { artist }

    /** Whether [heardS] of a [durationS] song is a listen: half of it, or four minutes; never under 30 s. */
    internal fun isListen(heardS: Double, durationS: Double): Boolean {
        if (durationS in 0.1..<MIN_TRACK_S) return false
        val needed = if (durationS > 0) minOf(durationS / 2, LISTEN_CAP_S) else LISTEN_CAP_S
        return heardS >= needed
    }

    /** Sends what's waiting, 50 at a time; what Last.fm doesn't take stays for the next try. */
    fun flush() {
        scope.launch {
            sending.withLock {
                while (true) {
                    val session = _state.value.session ?: return@withLock
                    val batch = _state.value.queue.take(BATCH)
                    if (batch.isEmpty()) return@withLock
                    val params = mutableMapOf("method" to "track.scrobble")
                    batch.forEachIndexed { i, s ->
                        params["artist[$i]"] = s.artist
                        params["track[$i]"] = s.track
                        params["timestamp[$i]"] = s.timestamp.toString()
                        s.album?.let { params["album[$i]"] = it }
                        s.durationS?.let { params["duration[$i]"] = it.toString() }
                    }
                    try {
                        call(params, session.apiKey to session.secret, session.key)
                    } catch (e: LastFmError) {
                        // 9: the session was revoked on last.fm; it signs out and the queue goes with it.
                        if (e.code == 9) signOut()
                        return@withLock
                    } catch (_: Exception) {
                        return@withLock
                    }
                    update { it.copy(queue = it.queue.drop(batch.size)) }
                }
            }
        }
    }

    private fun nowPlaying(s: Scrobble) {
        val session = _state.value.session ?: return
        scope.launch {
            runCatching {
                call(
                    buildMap {
                        put("method", "track.updateNowPlaying")
                        put("artist", s.artist)
                        put("track", s.track)
                        s.album?.let { put("album", it) }
                        s.durationS?.let { put("duration", it.toString()) }
                    },
                    session.apiKey to session.secret, session.key,
                )
            }
        }
    }

    private class Listening(val id: String, val meta: Scrobble, var heard: Double = 0.0, var announced: Boolean = false, var done: Boolean = false)

    /**
     * Follows the player once a second: counts the seconds of each song actually
     * heard (a seek forward doesn't count), sends "now playing" once it plays and
     * queues the scrobble when it qualifies. A song started over is a new play.
     */
    fun start() {
        scope.launch {
            var current: Listening? = null
            var last = 0.0
            flush()
            while (isActive) {
                delay(1_000)
                val s = _state.value
                val song = Player.current
                if (s.session == null || !s.scrobbling || song == null) { current = null; continue }
                val position = Player.positionMs() / 1000.0
                val restarted = current?.id == song.videoId && position + 2 < last && position < 3
                if (current?.id != song.videoId || restarted) {
                    val artist = primaryArtist(song.artist)
                    val known = Player.durationOf.value.takeIf { it.first == song.videoId }?.second?.div(1000.0)
                    val duration = known ?: song.durationText?.let(Player::seconds) ?: 0.0
                    current = if (artist.isBlank()) null else Listening(
                        song.videoId,
                        Scrobble(artist, song.title, song.albumName, System.currentTimeMillis() / 1000, duration.toLong().takeIf { it > 0 }),
                    )
                    last = position
                }
                val listening = current ?: continue
                if (Player.playing.value) {
                    val step = position - last
                    if (step > 0 && step < 3) listening.heard += step
                    if (!listening.announced) { listening.announced = true; nowPlaying(listening.meta) }
                    if (!listening.done && isListen(listening.heard, listening.meta.durationS?.toDouble() ?: 0.0)) {
                        listening.done = true
                        update { it.copy(queue = (it.queue + listening.meta).takeLast(MAX_QUEUED)) }
                        flush()
                    }
                }
                last = position
            }
        }
    }

    @Synchronized
    private fun update(change: (State) -> State) {
        _state.value = change(_state.value)
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(State.serializer(), _state.value))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun load(): State = runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrDefault(State())
}
