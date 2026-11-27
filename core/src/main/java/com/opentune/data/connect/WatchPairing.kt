package com.opentune.data.connect

import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RelayPool
import com.opentune.data.together.RoomTransport
import com.opentune.data.together.Schnorr
import com.opentune.data.together.Together
import com.opentune.data.together.gcmOpen
import com.opentune.data.together.gcmSeal
import com.opentune.data.together.toHex
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pairing a watch without typing: the watch shows a one-use key as a QR code,
 * the phone scans it and sends the Connect group (and, if asked to, the
 * YouTube Music session) sealed with that key, through the same relays
 * Connect runs on. It works on any connection the watch has, Bluetooth
 * through the phone included. The key exists only in the code and in the
 * two devices' memory.
 */
class WatchPairingKey(val secret: ByteArray) {
    init { require(secret.size == SIZE) }

    /** What relays file the pairing under; its own derivation, never a Connect group's tag. */
    val tag: String by lazy { ConnectGroup.hkdf(secret, "opentune/watch-pair/tag", 16).toHex() }
    private val key by lazy { SecretKeySpec(ConnectGroup.hkdf(secret, "opentune/watch-pair/key", 32), "AES") }

    fun seal(plain: String): String = gcmSeal(key, tag, plain)
    fun open(sealed: String): String? = gcmOpen(key, tag, sealed)

    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    val link: String get() = "$LINK$encoded"

    /** Done with: the key is wiped from memory. */
    fun forget() = secret.fill(0)

    companion object {
        const val SIZE = 32
        const val LINK = "opentune://watch/#"
        private val ENCODED = Regex("[A-Za-z0-9_-]{43}")
        private val random = SecureRandom()

        fun generate() = WatchPairingKey(ByteArray(SIZE).also(random::nextBytes))

        /** The key in a scanned `opentune://watch/#…` link; null for anything else. */
        fun parse(text: String?): WatchPairingKey? {
            val raw = text?.trim()?.takeIf { it.startsWith(LINK) }?.removePrefix(LINK) ?: return null
            if (!raw.matches(ENCODED)) return null
            return runCatching { WatchPairingKey(Base64.getUrlDecoder().decode(raw)) }.getOrNull()
        }
    }
}

/** What the phone sends the watch: the Connect group, maybe the session, and who sent it. */
data class WatchOffer(val group: String, val cookie: String?, val name: String)

object WatchPairing {
    /** How long a code on the watch lasts. */
    const val OFFER_MS = 3 * 60_000L
    /** How long the phone waits for the watch to answer. */
    const val ANSWER_MS = 20_000L
    /** How often the phone sends again until the watch answers (a relay may not have been connected yet). */
    internal const val RESEND_MS = 2_000L

    private val json = Json { ignoreUnknownKeys = true }

    internal fun offerText(o: WatchOffer): String = buildJsonObject {
        put("t", "offer"); put("v", 1); put("group", o.group); put("name", o.name)
        o.cookie?.let { put("cookie", it) }
    }.toString()

    internal fun ackText(name: String): String = buildJsonObject { put("t", "ack"); put("name", name) }.toString()

    internal fun parseOffer(text: String): WatchOffer? = runCatching {
        val o = json.parseToJsonElement(text) as JsonObject
        if (o["t"]?.jsonPrimitive?.contentOrNull != "offer" || o["v"]?.jsonPrimitive?.intOrNull != 1) return null
        val group = o["group"]?.jsonPrimitive?.contentOrNull?.takeIf { ConnectGroup.parse(it) != null } ?: return null
        WatchOffer(group, o["cookie"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }, o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(40))
    }.getOrNull()

    internal fun parseAck(text: String): String? = runCatching {
        val o = json.parseToJsonElement(text) as JsonObject
        if (o["t"]?.jsonPrimitive?.contentOrNull != "ack") null else o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrNull()

    internal fun transportFor(key: WatchPairingKey): RoomTransport = RelayPool(
        Together.RELAYS,
        buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(ConnectSession.KIND))))
            put("#t", JsonArray(listOf(JsonPrimitive(key.tag))))
            put("since", System.currentTimeMillis() / 1000 - 10)
        },
    )

    private fun event(key: WatchPairingKey, signer: ByteArray, plain: String) =
        NostrEvent.signed(signer, ConnectSession.KIND, listOf(listOf("t", key.tag)), key.seal(plain))

    /**
     * The watch's side: waits up to [OFFER_MS] for the phone's offer under [key],
     * answers it with [myName], and returns it; null when the time runs out.
     * [onListening] says when the watch can be heard, so the code can be shown.
     */
    suspend fun receive(key: WatchPairingKey, myName: String, onListening: () -> Unit = {}): WatchOffer? =
        receive(key, myName, onListening, transportFor(key), OFFER_MS)

    internal suspend fun receive(key: WatchPairingKey, myName: String, onListening: () -> Unit, transport: RoomTransport, waitMs: Long): WatchOffer? {
        val signer = Schnorr.newPrivateKey()
        transport.start()
        try {
            return coroutineScope {
                val got = CompletableDeferred<WatchOffer>()
                val listen = launch { transport.events.collect { e -> key.open(e.content)?.let(::parseOffer)?.let { got.complete(it) } } }
                val heard = launch { transport.connected.first { it > 0 }; onListening() }
                val offer = withTimeoutOrNull(waitMs) { got.await() }
                listen.cancel(); heard.cancel()
                // Said a few times: the phone may miss the first while its relays connect.
                if (offer != null) repeat(3) { transport.publish(event(key, signer, ackText(myName))); delay(400) }
                offer
            }
        } finally {
            transport.close()
        }
    }

    /**
     * The phone's side: sends [offer] under [key] until the watch answers,
     * for up to [ANSWER_MS]. The watch's name, or null when it didn't answer.
     */
    suspend fun send(key: WatchPairingKey, offer: WatchOffer): String? = send(key, offer, transportFor(key), ANSWER_MS)

    internal suspend fun send(key: WatchPairingKey, offer: WatchOffer, transport: RoomTransport, waitMs: Long): String? {
        val signer = Schnorr.newPrivateKey()
        transport.start()
        try {
            return coroutineScope {
                val answered = CompletableDeferred<String>()
                val listen = launch { transport.events.collect { e -> key.open(e.content)?.let(::parseAck)?.let { answered.complete(it) } } }
                val resend = launch {
                    val text = offerText(offer)
                    while (true) { transport.publish(event(key, signer, text)); delay(RESEND_MS) }
                }
                val name = withTimeoutOrNull(waitMs) { answered.await() }
                listen.cancel(); resend.cancel()
                name
            }
        } finally {
            transport.close()
        }
    }
}
