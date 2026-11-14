package com.opentune.data.discord

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Playing on OpenTune" on the listener's Discord profile, from the phone.
 *
 * A phone has no Discord app socket to talk to, as the desktop does, and
 * Discord offers no API for another app to set someone's status. So, as on
 * the web player, the account's own token (read from Discord's sign-in page
 * in Settings, never typed) opens a gateway session that carries the card.
 * The token is kept encrypted with a key from the phone's keystore, and is
 * sent nowhere but discord.com. Signing out forgets it.
 *
 * The gateway client follows the web player's (web/extension/lib/discord.ts):
 * reconnect with a doubling back-off, a heartbeat watchdog, and the last card
 * replayed onto each new session. Paused or stopped, the card comes down.
 */
object DiscordRpc {
    data class Account(val token: String, val id: String, val name: String, val username: String, val avatarUrl: String?)

    /** What's playing, for the card. */
    data class NowPlaying(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val artworkUrl: String?,
        val durationMs: Long,
        val positionMs: Long,
        val rate: Float,
        val playing: Boolean,
    )

    enum class Status { Off, Connecting, Ready, Error }

    private const val GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
    private const val API = "https://discord.com/api/v10"
    private const val PROJECT_URL = "https://github.com/justshivv/Opentune"
    private const val APP_NAME = "OpenTune"
    private const val LABEL_MAX = 32
    private const val TEXT_MAX = 128
    private const val MIN_RETRY_MS = 1_000L
    private const val MAX_RETRY_MS = 60_000L
    /** A card cleared this long with nothing new: the session closes too. */
    private const val IDLE_CLOSE_MS = 5 * 60_000L
    private const val KEY_ALIAS = "opentune.discord"
    private val APP_ID = Regex("[0-9]{17,20}")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _status = MutableStateFlow(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** OpenTune's own Discord application, from the build; empty in a build without one. */
    private val applicationId: String get() = com.opentune.core.BuildConfig.DISCORD_APP_ID.takeIf { it.matches(APP_ID) }.orEmpty()

    fun init(context: Context) {
        val p = context.getSharedPreferences("discord", Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean("enabled", true)
        val sealed = p.getString("account", null) ?: return
        runCatching {
            val json = JSONObject(open(sealed))
            _account.value = Account(
                json.getString("token"), json.getString("id"), json.getString("name"),
                json.getString("username"), json.optString("avatar").takeIf { it.isNotBlank() },
            )
        }.onFailure {
            Log.w(TAG, "Couldn't read the saved Discord account", it)
            p.edit().remove("account").apply()
        }
    }

    /** Checks [token] with Discord and keeps it with the account it belongs to. */
    suspend fun signIn(token: String): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = token.trim().removeSurrounding("\"")
            require(clean.length > 20) { "That isn't a Discord token" }
            val me = JSONObject(api(clean, "/users/@me"))
            val id = me.getString("id")
            val avatar = me.optString("avatar").takeIf { it.isNotBlank() && it != "null" }
            val account = Account(
                token = clean,
                id = id,
                name = me.optString("global_name").takeIf { it.isNotBlank() && it != "null" } ?: me.getString("username"),
                username = me.getString("username"),
                avatarUrl = avatar?.let { "https://cdn.discordapp.com/avatars/$id/$it.png" },
            )
            val json = JSONObject().put("token", account.token).put("id", account.id).put("name", account.name)
                .put("username", account.username).put("avatar", account.avatarUrl.orEmpty())
            prefs?.edit()?.putString("account", seal(json.toString()))?.apply()
            _account.value = account
            _problem.value = null
            gateway?.close()
            gateway = null
            last?.let { update(it) }
            account
        }
    }

    fun signOut() {
        gateway?.close()
        gateway = null
        _account.value = null
        _status.value = Status.Off
        _problem.value = null
        prefs?.edit()?.remove("account")?.apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
        if (!on) {
            gateway?.clear()
            gateway?.close()
            gateway = null
            _status.value = Status.Off
        } else last?.let { update(it) }
    }

    private var last: NowPlaying? = null
    private var gateway: Gateway? = null
    private var idle: Job? = null
    private var pending: Job? = null

    /** The song playing now, or null when nothing is; the card follows it. */
    fun update(now: NowPlaying?) {
        last = now
        val account = _account.value ?: return
        if (!_enabled.value) return
        pending?.cancel()
        pending = scope.launch {
            if (now == null || !now.playing) {
                gateway?.clear()
                idle?.cancel()
                idle = scope.launch {
                    delay(IDLE_CLOSE_MS)
                    gateway?.close()
                    gateway = null
                    _status.value = Status.Off
                }
                return@launch
            }
            idle?.cancel()
            val g = gateway ?: Gateway(account.token).also { gateway = it }
            val presence = runCatching { presence(account.token, now) }.getOrElse {
                Log.w(TAG, "Couldn't build the Discord card", it)
                return@launch
            }
            g.send(presence)
        }
    }

    // ---- The card -----------------------------------------------------------------

    private fun fit(text: String, max: Int): String {
        val t = text.trim()
        val clipped = if (t.length > max) t.take(max - 1).trimEnd() + "…" else t
        return clipped.padEnd(2, ' ')
    }

    private val artwork = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 50
    }

    /** Discord's own copy of an image on the web, as an `mp:` asset, so the card shows it. */
    private fun externalAsset(token: String, url: String): String? {
        val app = applicationId.ifEmpty { return null }
        synchronized(artwork) { if (artwork.containsKey(url)) return artwork[url] }
        val asset = runCatching {
            val body = JSONObject().put("urls", JSONArray().put(url)).toString()
            val list = JSONArray(api(token, "/applications/$app/external-assets", body))
            list.optJSONObject(0)?.optString("external_asset_path")?.takeIf { it.isNotBlank() }?.let { "mp:$it" }
        }.getOrNull()
        synchronized(artwork) { artwork[url] = asset }
        return asset
    }

    internal fun presence(token: String, now: NowPlaying): JSONObject {
        val wall = System.currentTimeMillis()
        val rate = if (now.rate > 0f) now.rate else 1f
        val watch = "https://music.youtube.com/watch?v=${now.videoId}"
        val art = now.artworkUrl?.takeIf { it.startsWith("http") }
        val image = art?.let { externalAsset(token, it) ?: it }
        val activity = JSONObject()
            // A type-2 (Listening) activity, the way BitChord shows it: Discord heads
            // the card "Listening to OpenTune", draws the song's progress as a bar
            // from the timestamps, and puts details over state under the artwork.
            .put("name", APP_NAME)
            .put("type", 2)
            .put("details", fit(if (rate != 1f) "${now.title} [${"%.2f".format(rate)}x]" else now.title, TEXT_MAX))
            .put("state", fit(now.artist.ifBlank { "Unknown artist" }, TEXT_MAX))
            .put("status_display_type", 0)
            .put("assets", JSONObject().apply {
                if (image != null) put("large_image", image)
                now.album?.takeIf { it.isNotBlank() }?.let { put("large_text", fit(it, TEXT_MAX)) }
            })
        if (now.durationMs > 0) {
            activity.put("timestamps", JSONObject()
                .put("start", wall - (now.positionMs / rate).toLong())
                .put("end", wall + ((now.durationMs - now.positionMs).coerceAtLeast(0) / rate).toLong()))
        }
        if (com.opentune.data.isYouTubeId(now.videoId)) {
            activity.put("details_url", watch)
            activity.put("buttons", JSONArray().put(fit("Listen on YouTube Music", LABEL_MAX)).put(fit("Get OpenTune", LABEL_MAX)))
            activity.put("metadata", JSONObject().put("button_urls", JSONArray().put(watch).put(PROJECT_URL)))
        }
        applicationId.takeIf { it.isNotEmpty() }?.let { activity.put("application_id", it) }
        return JSONObject()
            .put("activities", JSONArray().put(activity))
            .put("afk", true)
            .put("since", wall)
            .put("status", "online")
    }

    private fun api(token: String, path: String, body: String? = null): String {
        val request = Request.Builder().url(API + path)
            .header("Authorization", token)
            .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }
            .build()
        Http.client.newCall(request).execute().use { r ->
            if (r.code == 401) throw IllegalStateException("Discord didn't accept the sign-in. Connect again.")
            if (!r.isSuccessful) throw IllegalStateException(com.opentune.i18n.Tr.f("Discord said {0}", r.code))
            return r.body?.string().orEmpty()
        }
    }

    // ---- The gateway ------------------------------------------------------------------

    private class Gateway(private val token: String) {
        private var socket: WebSocket? = null
        private var sequence = 0
        private var sessionId: String? = null
        private var resumeUrl: String? = null
        private var ready = false
        private var closed = false
        private var backoff = MIN_RETRY_MS
        private var beats: Job? = null
        private var acked = true
        /** Replayed onto every new session: Discord drops a card with the session that set it. */
        private var presence: JSONObject? = null
        private var delivered: String? = null

        init {
            open()
        }

        @Synchronized
        fun send(p: JSONObject) {
            presence = p
            if (closed) return
            if (socket == null) open() else deliver()
        }

        @Synchronized
        fun clear() {
            presence = JSONObject().put("activities", JSONArray()).put("afk", true).put("since", 0).put("status", "online")
            deliver()
        }

        @Synchronized
        fun close() {
            closed = true
            beats?.cancel()
            socket?.close(1000, null)
            socket = null
        }

        @Synchronized
        private fun deliver() {
            val p = presence ?: return
            if (!ready) return
            val text = p.toString()
            if (text == delivered) return
            if (socket?.send(JSONObject().put("op", 3).put("d", p).toString()) == true) delivered = text
        }

        @Synchronized
        private fun open() {
            if (closed) return
            _status.value = Status.Connecting
            val request = Request.Builder().url(resumeUrl ?: GATEWAY).build()
            socket = Http.client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    backoff = MIN_RETRY_MS
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { onPayload(webSocket, JSONObject(text)) }.onFailure { Log.w(TAG, "Discord gateway payload", it) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, code)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, -1)
            })
        }

        @Synchronized
        private fun onPayload(ws: WebSocket, p: JSONObject) {
            if (ws !== socket) return
            if (!p.isNull("s")) sequence = p.optInt("s", sequence)
            when (p.optInt("op", -1)) {
                0 -> when (p.optString("t")) {
                    "READY" -> {
                        val d = p.getJSONObject("d")
                        sessionId = d.optString("session_id")
                        resumeUrl = d.optString("resume_gateway_url").takeIf { it.isNotBlank() }?.let { "$it/?v=10&encoding=json" }
                        sessionReady()
                    }
                    "RESUMED" -> sessionReady()
                }
                1 -> ws.send(JSONObject().put("op", 1).put("d", sequence.takeIf { it > 0 } ?: JSONObject.NULL).toString())
                11 -> acked = true
                7 -> ws.close(4000, "Reconnect asked")
                9 -> {
                    forget()
                    scope.launch { delay(500); identify(ws) }
                }
                10 -> {
                    val interval = p.getJSONObject("d").getLong("heartbeat_interval")
                    val session = sessionId
                    if (session != null && sequence > 0) {
                        ws.send(JSONObject().put("op", 6).put("d", JSONObject().put("token", token).put("session_id", session).put("seq", sequence)).toString())
                    } else identify(ws)
                    beat(ws, interval)
                }
            }
        }

        private fun identify(ws: WebSocket) {
            val d = JSONObject()
                .put("token", token)
                .put("capabilities", 65)
                .put("properties", JSONObject().put("os", "Android").put("browser", "Discord Android").put("device", Build.MODEL ?: "Android"))
                .put("presence", JSONObject().put("status", "online").put("since", 0).put("activities", JSONArray()).put("afk", true))
                .put("compress", false)
            ws.send(JSONObject().put("op", 2).put("d", d).toString())
        }

        private fun beat(ws: WebSocket, interval: Long) {
            beats?.cancel()
            acked = true
            beats = scope.launch {
                delay((interval * Math.random()).toLong())
                while (true) {
                    synchronized(this@Gateway) {
                        if (ws !== socket) return@launch
                        // A heartbeat unanswered for a whole interval: the socket is gone.
                        if (!acked) { ws.close(4000, "Heartbeat unanswered"); return@launch }
                        acked = false
                        ws.send(JSONObject().put("op", 1).put("d", sequence.takeIf { it > 0 } ?: JSONObject.NULL).toString())
                    }
                    delay(interval)
                }
            }
        }

        private fun sessionReady() {
            ready = true
            delivered = null
            _status.value = Status.Ready
            _problem.value = null
            deliver()
        }

        private fun forget() {
            sessionId = null
            resumeUrl = null
            sequence = 0
        }

        @Synchronized
        private fun lost(ws: WebSocket, code: Int) {
            if (ws !== socket) return
            socket = null
            ready = false
            beats?.cancel()
            if (closed) return
            if (code == 4004) {
                // The token was refused: retrying with it never helps.
                closed = true
                _status.value = Status.Error
                _problem.value = "Discord didn't accept the sign-in. Connect again."
                return
            }
            if (code == -1) forget()
            _status.value = Status.Connecting
            val wait = backoff
            backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MS)
            scope.launch { delay(wait); open() }
        }
    }

    // ---- Keeping the token ---------------------------------------------------------------

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    private const val TAG = "Discord"
}
