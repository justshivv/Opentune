package com.opentune.data.together

import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** One Nostr event (NIP-01), as relays pass them around. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("pubkey", pubkey); put("created_at", createdAt); put("kind", kind)
        put("tags", buildJsonArray { tags.forEach { t -> add(buildJsonArray { t.forEach { add(JsonPrimitive(it)) } }) } })
        put("content", content); put("sig", sig)
    }

    companion object {
        /** Builds and signs an event; [privateKey] is the sender's session key. */
        fun signed(privateKey: ByteArray, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent {
            val pubkey = Schnorr.publicKey(privateKey).toHex()
            val id = MessageDigest.getInstance("SHA-256").digest(serialize(pubkey, createdAt, kind, tags, content).toByteArray())
            return NostrEvent(id.toHex(), pubkey, createdAt, kind, tags, content, Schnorr.sign(id, privateKey).toHex())
        }

        /** The exact text an event id is the hash of: `[0,pubkey,created_at,kind,tags,content]`, NIP-01 escaping. */
        internal fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String = buildString {
            append("[0,\"").append(pubkey).append("\",").append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, t ->
                if (i > 0) append(',')
                append('[')
                t.forEachIndexed { j, v -> if (j > 0) append(','); quote(v) }
                append(']')
            }
            append("],")
            quote(content)
            append(']')
        }

        private fun StringBuilder.quote(s: String) {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }

        /** An event out of a relay's `["EVENT", sub, {...}]`, or null. */
        fun parse(o: JsonObject): NostrEvent? = runCatching {
            NostrEvent(
                id = (o["id"] as JsonPrimitive).content,
                pubkey = (o["pubkey"] as JsonPrimitive).content,
                createdAt = (o["created_at"] as JsonPrimitive).longOrNull ?: return null,
                kind = (o["kind"] as JsonPrimitive).intOrNull ?: return null,
                tags = (o["tags"] as JsonArray).map { t -> (t as JsonArray).map { (it as JsonPrimitive).content } },
                content = (o["content"] as JsonPrimitive).content,
                sig = (o["sig"] as JsonPrimitive).content,
            )
        }.getOrNull()
    }
}

/**
 * Connections to several public Nostr relays at once, all carrying one
 * subscription. Every event is sent to every connected relay and each
 * incoming event is handed on once, so a room keeps working as long as
 * any one relay does. Dropped connections come back with growing pauses.
 */
class RelayPool(
    private val urls: List<String>,
    private val filter: JsonObject,
) : RoomTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: OkHttpClient = Http.client.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val sockets = mutableMapOf<String, WebSocket>()
    private val open = mutableSetOf<String>()
    private val seen = object : LinkedHashMap<String, Unit>(256, .75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 2000
    }
    private val jobs = mutableListOf<Job>()
    @Volatile private var closed = false

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

    /** How many relays are connected right now. */
    private val _connected = MutableStateFlow(0)
    override val connected: StateFlow<Int> = _connected.asStateFlow()

    override fun start() {
        urls.forEach { url -> jobs += scope.launch { keepConnected(url) } }
    }

    private suspend fun keepConnected(url: String) {
        var backoff = 2_000L
        while (!closed) {
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(open) { open += url; _connected.value = open.size }
                    backoff = 2_000L
                    webSocket.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(SUB)); add(filter) }.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(url, text)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(Unit) }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "$url: ${t.message}")
                    done.complete(Unit)
                }
            })
            synchronized(sockets) { sockets[url] = ws }
            done.await()
            synchronized(open) { open -= url; _connected.value = open.size }
            if (closed) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun handle(url: String, text: String) {
        val msg = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
        when ((msg.getOrNull(0) as? JsonPrimitive)?.contentOrNull) {
            "EVENT" -> {
                val event = (msg.getOrNull(2) as? JsonObject)?.let(NostrEvent::parse) ?: return
                val fresh = synchronized(seen) { seen.put(event.id, Unit) == null }
                if (fresh) _events.tryEmit(event)
            }
            "OK" -> if ((msg.getOrNull(2) as? JsonPrimitive)?.content == "false") Log.w(TAG, "$url refused: ${msg.getOrNull(3)}")
            "NOTICE", "CLOSED" -> Log.w(TAG, "$url: $text")
        }
    }

    /** Sends [event] to every connected relay; false if none is connected. */
    override fun publish(event: NostrEvent): Boolean {
        synchronized(seen) { seen[event.id] = Unit } // our own copy coming back isn't news
        val frame = buildJsonArray { add(JsonPrimitive("EVENT")); add(event.toJson()) }.toString()
        val targets = synchronized(open) { open.toList() }.mapNotNull { synchronized(sockets) { sockets[it] } }
        targets.forEach { it.send(frame) }
        return targets.isNotEmpty()
    }

    override fun close() {
        closed = true
        synchronized(sockets) { sockets.values.forEach { it.close(1000, null) }; sockets.clear() }
        scope.cancel()
        _connected.value = 0
    }

    companion object {
        private const val TAG = "RelayPool"
        private const val SUB = "room"
    }
}
