package com.opentune.data.account

import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RelayPool
import com.opentune.data.together.RoomTransport
import com.opentune.data.together.Schnorr
import com.opentune.data.together.Together
import com.opentune.data.together.toHex
import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The web player's "Sign in with your phone" code: `opentune://pair/web#<payload>`, the
 * payload `w1|expires|publicKey|take`. [publicKey] is a one-use P-256 key the OpenTune
 * extension made for this code and keeps to itself, so what the phone seals for it opens
 * only there: not on the web page that shows the code, nor on the relays that carry it.
 * An older phone reads the payload as a TV's, finds it isn't one, and does nothing.
 */
class WebShareOffer private constructor(val expires: Long, internal val publicKey: ByteArray, val take: Set<String>) {
    /** What the relays index the exchange by; it says nothing about the key. */
    val tag: String get() = MessageDigest.getInstance("SHA-256")
        .digest("opentune/share/${PairingCrypto.encode(publicKey)}".toByteArray(UTF_8)).toHex().take(32)
    fun valid(now: Long = System.currentTimeMillis()): Boolean = expires > now && expires - now <= 125_000

    companion object {
        fun parse(payload: String, now: Long = System.currentTimeMillis()): WebShareOffer {
            require(payload.length in 40..512)
            val fields = String(PairingCrypto.decode(payload), UTF_8).split('|')
            require(fields.size == 4 && fields[0] == "w1")
            val key = PairingCrypto.decode(fields[2])
            ShareCrypto.publicKey(key) // a point on the curve, or this throws
            return WebShareOffer(fields[1].toLong(), key, PairingExtras.kinds(fields[3]))
                .also { require(it.valid(now) && it.take.isNotEmpty()) }
        }
    }
}

/**
 * ECDH on P-256, then HKDF-SHA256 into an AES-256-GCM key, as WebCrypto does it in the
 * extension (web/shared/src/share): the secret is the shared point's x, the salt the
 * extension's key then the phone's (both uncompressed), the info "OpenTune share v1". A
 * packet is nonce (12) then ciphertext and tag; "OpenTune share v1/<purpose>" is the AAD.
 */
internal object ShareCrypto {
    private val random = SecureRandom()
    private const val INFO = "OpenTune share v1"

    private val params: ECParameterSpec by lazy { (keyPair().public as ECPublicKey).params }

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun raw(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - b.size) + b
    }

    /** An uncompressed point, checked to be on the curve before anything is agreed with it. */
    fun publicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte())
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val p = (params.curve.field as java.security.spec.ECFieldFp).p
        require(x < p && y < p)
        require(y.modPow(BigInteger.valueOf(2), p) ==x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    /** For tests: a private key from its scalar. */
    fun privateKey(d: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), params))

    fun sharedKey(mine: PrivateKey, theirs: ByteArray, extension: ByteArray, phone: ByteArray): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run { init(mine); doPhase(publicKey(theirs), true); generateSecret() }
        try {
            val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(extension + phone, "HmacSHA256")); doFinal(secret) }
            return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(prk, "HmacSHA256")); doFinal(INFO.toByteArray(UTF_8) + 1) }
                .also { prk.fill(0) }
        } finally { secret.fill(0) }
    }

    fun seal(key: ByteArray, value: ByteArray, purpose: String): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("$INFO/$purpose".toByteArray(UTF_8))
        return nonce + cipher.doFinal(value)
    }

    fun open(key: ByteArray, packet: ByteArray, purpose: String): ByteArray {
        require(packet.size in 29..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.copyOfRange(0, 12)))
        cipher.updateAAD("$INFO/$purpose".toByteArray(UTF_8))
        return cipher.doFinal(packet, 12, packet.size - 12)
    }
}

/**
 * Hands the web player's extension the sign-ins the person ticked, over the public Nostr
 * relays Listen together uses, as an ephemeral event the relays pass on and don't keep. The
 * event holds the phone's one-use public key and the sealed sign-ins; the relays and the web
 * page see only that. The extension answers with a sealed list of what it kept: only the
 * kinds ("discord"), never a token, and sealed under the same key, so a relay can't fake it.
 */
object WebShare {
    const val KIND = 21422
    private const val RESEND_MS = 3_000L
    private const val WAIT_MS = 45_000L
    private val json = Json { ignoreUnknownKeys = true }

    /** It went out to at least one relay, but no answer came in time: the web player may have taken it or not. */
    class Unconfirmed : Exception("The web player didn't confirm the sign-in")

    /** No relay was reached in time, so nothing left the phone. */
    class Unsent : Exception("No relay carried the sign-in")

    /** Which of [extras] the web player took. Throws [Unconfirmed] or [Unsent] when it didn't answer in time. */
    suspend fun send(offer: WebShareOffer, extras: PairingExtras): Set<String> =
        send(offer, extras) { filter -> RelayPool(Together.RELAYS, filter) }

    internal suspend fun send(
        offer: WebShareOffer,
        extras: PairingExtras,
        waitMs: Long = WAIT_MS,
        transport: (JsonObject) -> RoomTransport,
    ): Set<String> {
        val wanted = extras.only(offer.take)
        require(offer.valid() && !wanted.isEmpty)
        val mine = ShareCrypto.keyPair()
        val minePublic = ShareCrypto.raw(mine.public as ECPublicKey)
        val key = ShareCrypto.sharedKey(mine.private, offer.publicKey, offer.publicKey, minePublic)
        val plain = wanted.encode()
        val sealed = try { ShareCrypto.seal(key, plain, "extras") } finally { plain.fill(0) }
        val content = buildJsonObject {
            put("v", 1)
            put("k", PairingCrypto.encode(minePublic))
            put("s", PairingCrypto.encode(sealed))
        }.toString()
        val event = NostrEvent.signed(Schnorr.newPrivateKey(), KIND, listOf(listOf("t", offer.tag)), content)
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(KIND))))
            put("#t", JsonArray(listOf(JsonPrimitive(offer.tag))))
            put("since", System.currentTimeMillis() / 1000 - 10)
        }
        val pool = transport(filter)
        val sent = AtomicBoolean(false)
        try {
            pool.start()
            // Not withTimeout: its TimeoutCancellationException is a CancellationException, which
            // callers pass on as their own cancelling rather than show as a failure.
            val took = withTimeoutOrNull(waitMs) {
                coroutineScope {
                    val answer = async(start = CoroutineStart.UNDISPATCHED) {
                        pool.events.mapNotNull { e -> if (e.kind == KIND && e.id != event.id) took(key, e.content) else null }.first()
                    }
                    // Sent again until it's answered: a relay that connects late still carries it.
                    val resend = launch { while (true) { if (pool.connected.value > 0 && pool.publish(event)) sent.set(true); delay(RESEND_MS) } }
                    answer.await().also { resend.cancel() }
                }
            }
            return took?.intersect(wanted.kinds) ?: throw if (sent.get()) Unconfirmed() else Unsent()
        } finally {
            pool.close()
            key.fill(0)
        }
    }

    /** What an answer from the extension says it took, or null when it isn't one. */
    internal fun took(key: ByteArray, content: String): Set<String>? = runCatching {
        val o = json.parseToJsonElement(content).jsonObject
        val sealed = PairingCrypto.decode((o["a"] as JsonPrimitive).content)
        PairingExtras.kinds(String(ShareCrypto.open(key, sealed, "ack"), UTF_8))
    }.getOrNull()
}
