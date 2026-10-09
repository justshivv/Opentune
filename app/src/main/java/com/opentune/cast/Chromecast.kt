package com.opentune.cast

import android.annotation.SuppressLint
import android.os.SystemClock
import com.opentune.data.DebugLog as Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Chromecast (or a TV or speaker with Chromecast built in), spoken to
 * directly over the Cast protocol: a TLS socket on port 8009 carrying small
 * framed messages, each a namespace and a JSON payload. It runs Google's
 * Default Media Receiver, which plays any URL it's given; here, the phone's
 * own [CastServer].
 */
class Chromecast(
    override val name: String,
    private val host: String,
    private val port: Int = 8009,
    /** Plain TCP instead of TLS, for tests against a pretend device. */
    private val plain: Boolean = false,
) : Receiver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: java.net.Socket? = null
    private var output: DataOutputStream? = null
    private val requestIds = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var loadedId: String? = null
    @Volatile private var loadRequest = 0
    private val _status = MutableStateFlow(RemoteStatus())
    override val status: StateFlow<RemoteStatus> = _status

    /** The phone's address on the network the Chromecast is on, for it to fetch from. */
    var localAddress: InetAddress? = null
        private set

    /** Opens the connection and starts the media receiver app on it. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        // Straight to the device, never through a proxy: it's on the local network.
        val address = InetSocketAddress(host, port)
        val tcp = java.net.Socket(java.net.Proxy.NO_PROXY)
        tcp.connect(address, CONNECT_TIMEOUT_MS)
        val s = if (plain) {
            tcp
        } else {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(AcceptDeviceCertificate), SecureRandom())
            (context.socketFactory.createSocket(tcp, host, port, true) as SSLSocket).apply { startHandshake() }
        }
        socket = s
        localAddress = s.localAddress
        output = DataOutputStream(s.outputStream)
        scope.launch { readLoop(DataInputStream(s.inputStream)) }
        send(RECEIVER, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                runCatching { send(RECEIVER, NS_HEARTBEAT, JSONObject().put("type", "PING")) }
            }
        }
        // Launch the media receiver, then wait until it says it's running.
        var app = runCatching { request(RECEIVER, NS_RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", MEDIA_APP)) }
            .getOrNull()?.let(::runningApp)
        val deadline = SystemClock.elapsedRealtime() + LAUNCH_WAIT_MS
        while (app == null && SystemClock.elapsedRealtime() < deadline) {
            delay(500)
            app = runCatching { request(RECEIVER, NS_RECEIVER, JSONObject().put("type", "GET_STATUS")) }.getOrNull()?.let(::runningApp)
        }
        checkNotNull(app) { "$name didn't start its player" }
        transportId = app.getString("transportId")
        sessionId = app.getString("sessionId")
        send(transportId!!, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        // Ask how it's getting on every second, so the phone can keep time with it.
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val t = transportId ?: continue
                if (mediaSessionId != null) runCatching { send(t, NS_MEDIA, JSONObject().put("type", "GET_STATUS").put("requestId", requestIds.getAndIncrement())) }
            }
        }
    }

    override suspend fun load(media: CastMedia, positionMs: Long, play: Boolean) {
        val t = transportId ?: return
        loadedId = media.id
        mediaSessionId = null
        _status.value = RemoteStatus(RemoteState.LOADING, positionMs, SystemClock.elapsedRealtime(), _status.value.volume, media.id)
        val metadata = JSONObject()
            .put("metadataType", 3)
            .put("title", media.title)
            .put("artist", media.artist)
            .apply { media.album?.let { put("albumName", it) } }
            .apply { media.imageUrl?.let { put("images", JSONArray().put(JSONObject().put("url", it))) } }
        val payload = JSONObject()
            .put("type", "LOAD")
            .put("sessionId", sessionId)
            .put("autoplay", play)
            .put("currentTime", positionMs / 1000.0)
            .put(
                "media",
                JSONObject()
                    .put("contentId", media.url)
                    .put("contentUrl", media.url)
                    .put("contentType", media.contentType)
                    .put("streamType", if (media.live) "LIVE" else "BUFFERED")
                    .put("metadata", metadata)
                    .apply { if (media.durationMs > 0) put("duration", media.durationMs / 1000.0) },
            )
        val id = requestIds.getAndIncrement()
        loadRequest = id
        try {
            val reply = request(t, NS_MEDIA, payload, LOAD_WAIT_MS, id)
            if (reply.optString("type") == "LOAD_FAILED" || reply.optString("type") == "INVALID_REQUEST") {
                _status.value = _status.value.copy(state = RemoteState.ERROR, at = SystemClock.elapsedRealtime())
            }
        } finally {
            if (loadRequest == id) loadRequest = 0
        }
    }

    override suspend fun play() = media("PLAY")
    override suspend fun pause() = media("PAUSE")
    override suspend fun seek(positionMs: Long) = media("SEEK") { put("currentTime", positionMs / 1000.0) }

    override suspend fun setVolume(level: Float) {
        runCatching {
            send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "SET_VOLUME").put("requestId", requestIds.getAndIncrement()).put("volume", JSONObject().put("level", level.coerceIn(0f, 1f).toDouble())))
        }
    }

    private suspend fun media(type: String, extra: JSONObject.() -> Unit = {}) {
        val t = transportId ?: return
        val id = mediaSessionId ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                send(t, NS_MEDIA, JSONObject().put("type", type).put("requestId", requestIds.getAndIncrement()).put("mediaSessionId", id).apply(extra))
            }
        }
    }

    override fun close() {
        val s = sessionId
        scope.launch {
            runCatching { if (s != null) send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "STOP").put("sessionId", s).put("requestId", requestIds.getAndIncrement())) }
            runCatching { send(RECEIVER, NS_CONNECTION, JSONObject().put("type", "CLOSE")) }
            runCatching { socket?.close() }
            scope.cancel()
        }
    }

    private suspend fun request(
        destination: String,
        namespace: String,
        payload: JSONObject,
        timeoutMs: Long = REQUEST_WAIT_MS,
        id: Int = requestIds.getAndIncrement(),
    ): JSONObject {
        val reply = CompletableDeferred<JSONObject>()
        waiting[id] = reply
        try {
            withContext(Dispatchers.IO) { send(destination, namespace, payload.put("requestId", id)) }
            return withTimeout(timeoutMs) { reply.await() }
        } finally {
            waiting.remove(id)
        }
    }

    private fun send(destination: String, namespace: String, payload: JSONObject) {
        val frame = CastFrames.encode(SENDER, destination, namespace, payload.toString())
        val out = output ?: error("not connected")
        synchronized(out) {
            out.writeInt(frame.size)
            out.write(frame)
            out.flush()
        }
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (scope.isActive) {
                val size = input.readInt()
                if (size !in 0..MAX_FRAME) error("bad frame size $size")
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val frame = CastFrames.decode(bytes)
                val payload = frame.payload?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
                handle(frame, payload)
            }
        } catch (e: Exception) {
            if (scope.isActive) Log.w(TAG, "$name went away", e)
            _status.value = _status.value.copy(state = RemoteState.GONE, at = SystemClock.elapsedRealtime())
        }
    }

    private fun handle(frame: CastFrames.Frame, payload: JSONObject) {
        val type = payload.optString("type")
        when {
            frame.namespace == NS_HEARTBEAT && type == "PING" -> runCatching { send(frame.source, NS_HEARTBEAT, JSONObject().put("type", "PONG")) }
            frame.namespace == NS_CONNECTION && type == "CLOSE" && frame.source == transportId -> {
                _status.value = _status.value.copy(state = RemoteState.GONE, at = SystemClock.elapsedRealtime())
            }
            type == "RECEIVER_STATUS" -> {
                val status = payload.optJSONObject("status")
                status?.optJSONObject("volume")?.optDouble("level")?.takeIf { !it.isNaN() }?.let { level ->
                    _status.value = _status.value.copy(volume = level.toFloat())
                }
                // Someone else took the Chromecast over, or it was stopped on the TV.
                if (transportId != null && status != null && runningApp(payload) == null) {
                    _status.value = _status.value.copy(state = RemoteState.GONE, at = SystemClock.elapsedRealtime())
                }
            }
            // While a song is loading, reports about the one before it are stale.
            type == "MEDIA_STATUS" && (loadRequest == 0 || payload.optInt("requestId") == loadRequest) ->
                payload.optJSONArray("status")?.optJSONObject(0)?.let(::mediaStatus)
        }
        val id = payload.optInt("requestId", 0)
        if (id != 0) waiting[id]?.complete(payload)
    }

    private fun mediaStatus(s: JSONObject) {
        s.optInt("mediaSessionId", 0).takeIf { it != 0 }?.let { mediaSessionId = it }
        val state = when (s.optString("playerState")) {
            "PLAYING" -> RemoteState.PLAYING
            "PAUSED" -> RemoteState.PAUSED
            "BUFFERING" -> RemoteState.BUFFERING
            "LOADING" -> RemoteState.LOADING
            "IDLE" -> when (s.optString("idleReason")) {
                "FINISHED" -> RemoteState.FINISHED
                "ERROR" -> RemoteState.ERROR
                else -> RemoteState.IDLE
            }
            else -> _status.value.state
        }
        val position = s.optDouble("currentTime", Double.NaN).takeIf { !it.isNaN() }?.let { (it * 1000).toLong() } ?: _status.value.positionMs
        val volume = s.optJSONObject("volume")?.optDouble("level")?.takeIf { !it.isNaN() }?.toFloat() ?: _status.value.volume
        _status.value = RemoteStatus(state, position, SystemClock.elapsedRealtime(), volume, loadedId)
    }

    private fun runningApp(payload: JSONObject): JSONObject? {
        val apps = payload.optJSONObject("status")?.optJSONArray("applications") ?: return null
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            if (app.optString("appId") == MEDIA_APP && app.has("transportId")) return app
        }
        return null
    }

    /**
     * Chromecasts identify themselves with certificates from Google's own
     * device authority, which isn't one the phone holds, so the connection
     * accepts the device's certificate as it is. It only ever goes to an
     * address found on the local network.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AcceptDeviceCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val TAG = "Chromecast"
        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        /** Google's Default Media Receiver. */
        const val MEDIA_APP = "CC1AD845"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HEARTBEAT_MS = 5_000L
        const val POLL_MS = 1_000L
        const val REQUEST_WAIT_MS = 6_000L
        const val LOAD_WAIT_MS = 20_000L
        const val LAUNCH_WAIT_MS = 12_000L
        const val MAX_FRAME = 64 * 1024
    }
}

/**
 * The Cast protocol's message, by hand: a protobuf with the protocol
 * version, who it's from and to, the namespace, and a UTF-8 payload.
 * Only the string payload is used, so that's all this reads and writes.
 */
internal object CastFrames {
    data class Frame(val source: String, val destination: String, val namespace: String, val payload: String?)

    fun encode(source: String, destination: String, namespace: String, payload: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x08); out.write(0x00) // protocol_version = CASTV2_1_0
        string(out, 2, source)
        string(out, 3, destination)
        string(out, 4, namespace)
        out.write(0x28); out.write(0x00) // payload_type = STRING
        string(out, 6, payload)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Frame {
        var i = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val strings = HashMap<Int, String>()
        while (i < bytes.size) {
            val key = varint().toInt()
            val field = key ushr 3
            when (key and 7) {
                0 -> varint()
                1 -> i += 8
                2 -> {
                    val length = varint().toInt()
                    strings[field] = String(bytes, i, length, Charsets.UTF_8)
                    i += length
                }
                5 -> i += 4
                else -> error("unknown wire type")
            }
        }
        return Frame(strings[2].orEmpty(), strings[3].orEmpty(), strings[4].orEmpty(), strings[6])
    }

    private fun string(out: ByteArrayOutputStream, field: Int, value: String) {
        val b = value.toByteArray(Charsets.UTF_8)
        out.write((field shl 3) or 2)
        var v = b.size
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
        out.write(b)
    }
}
