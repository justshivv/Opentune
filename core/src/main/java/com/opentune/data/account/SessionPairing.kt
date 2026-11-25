package com.opentune.data.account

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One-use, local-network pairing. The secret exists only in the QR and process memory. */
object PairingCrypto {
    private val random = SecureRandom()
    fun secret(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    /** An address on the home network, as [Lan.ipv4] takes it. */
    fun privateHost(host: String): Boolean = Lan.ipv4(host)
    fun seal(key: ByteArray, value: ByteArray, purpose: String): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("OpenTune pairing v1/$purpose".toByteArray(UTF_8))
        return nonce + cipher.doFinal(value)
    }
    fun open(key: ByteArray, packet: ByteArray, purpose: String): ByteArray {
        require(packet.size in 29..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.copyOfRange(0, 12)))
        cipher.updateAAD("OpenTune pairing v1/$purpose".toByteArray(UTF_8))
        return cipher.doFinal(packet, 12, packet.size - 12)
    }
}

/**
 * The QR: `1|host|port|expires|key`, as every phone reads it. What else the device can take
 * from the phone ([take]: its Discord or Last.fm sign-in) rides in the link's query, outside
 * the payload, so an older phone, which reads only the payload, still signs the device in. The
 * hint carries no secret and has the QR's own trust: whoever shows the code holds its key.
 * So do the device's other addresses ([also]), for a phone that can't reach it at [host]:
 * a TV on both Ethernet and Wi-Fi, say.
 *
 * Each end times the code's two minutes by its own steady clock, from when it made or read the
 * code: the two devices' clocks are never compared, since a TV's is often minutes out. The
 * device, which holds the key, is what stops taking it; [expires] is only written for older phones.
 */
class PairingOffer private constructor(
    val host: String,
    val port: Int,
    val expires: Long,
    internal val key: ByteArray,
    val take: Set<String> = emptySet(),
    val also: List<String> = emptyList(),
    /** [Lan.steadyMs] when this end stops taking the code. */
    private val deadline: Long,
) {
    /** Every address the device gave, its main one first. */
    val hosts: List<String> get() = (listOf(host) + also).distinct()
    val payload: String get() = PairingCrypto.encode("1|$host|$port|$expires|${PairingCrypto.encode(key)}".toByteArray(UTF_8))
    val link: String get() {
        val query = listOfNotNull(
            take.takeIf { it.isNotEmpty() }?.let { "take=" + it.sorted().joinToString(",") },
            also.takeIf { it.isNotEmpty() }?.let { "also=" + it.joinToString(",") },
        )
        return "opentune://pair/" + (if (query.isEmpty()) "" else "?" + query.joinToString("&")) + "#" + payload
    }

    /** Whether the code is still good, by this end's own clock. */
    fun valid(steady: Long = Lan.steadyMs()): Boolean = steady < deadline

    companion object {
        const val LIFETIME_MS = 120_000L
        private const val MAX_ALSO = 5

        private fun mainHost(host: String) = Lan.accepts(host)

        private fun others(host: String, also: List<String>) =
            also.map { it.substringBefore('%') }.filter { it != host && Lan.accepts(it) }.distinct().take(MAX_ALSO)

        fun create(
            host: String,
            port: Int,
            now: Long = System.currentTimeMillis(),
            take: Set<String> = emptySet(),
            also: List<String> = emptyList(),
            steady: Long = Lan.steadyMs(),
        ): PairingOffer {
            require(mainHost(host) && port in 1024..65535)
            return PairingOffer(host, port, now + LIFETIME_MS, PairingCrypto.secret(), take intersect PairingExtras.KINDS, others(host, also), steady + LIFETIME_MS)
        }

        /**
         * The code in a scanned link's [payload]; [take] and [also] are the link's queries of
         * those names (comma separated), when it had them. Throws [Lan.OffHomeNetwork] for a code
         * that points off the home network, and IllegalArgumentException for anything that isn't
         * a code. [now] isn't compared with the code's expiry: this phone gives it two minutes
         * from [steady], when it was read, and the device enforces its own two.
         */
        @Suppress("UNUSED_PARAMETER")
        fun parse(
            payload: String,
            now: Long = System.currentTimeMillis(),
            take: String? = null,
            also: String? = null,
            steady: Long = Lan.steadyMs(),
        ): PairingOffer {
            require(payload.length in 40..512)
            val fields = String(PairingCrypto.decode(payload), UTF_8).split('|')
            require(fields.size == 5 && fields[0] == "1")
            val port = fields[2].toInt(); require(port in 1024..65535)
            val key = PairingCrypto.decode(fields[4]); require(key.size == 32)
            val expires = fields[3].toLong(); require(expires > 0)
            if (!mainHost(fields[1])) throw Lan.OffHomeNetwork(fields[1])
            val more = others(fields[1], also.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() })
            return PairingOffer(fields[1], port, expires, key, PairingExtras.kinds(take), more, steady + LIFETIME_MS)
        }
    }
}

/**
 * Sign-ins the phone hands over after the YouTube Music session, each only when the person
 * ticked it for this scan: `{discord?:{token}, lastfm?:{session,username,apiKey?,secret?}}`.
 * Never printed: [toString] names only what's inside.
 */
class PairingExtras(val discord: Discord? = null, val lastFm: LastFm? = null) {
    class Discord(val token: String)

    /**
     * A Last.fm session key works only with the application key that made it. [apiKey] names
     * that application; [secret] comes along only when it's the person's own application
     * rather than OpenTune's, whose secret every OpenTune build already has.
     */
    class LastFm(val session: String, val username: String, val apiKey: String? = null, val secret: String? = null)

    val kinds: Set<String> get() = buildSet { if (discord != null) add(DISCORD); if (lastFm != null) add(LASTFM) }
    val isEmpty: Boolean get() = discord == null && lastFm == null

    /** Just the ones among [allowed]. */
    fun only(allowed: Set<String>) = PairingExtras(discord.takeIf { DISCORD in allowed }, lastFm.takeIf { LASTFM in allowed })

    fun encode(): ByteArray = buildJsonObject {
        discord?.let { putJsonObject(DISCORD) { put("token", it.token) } }
        lastFm?.let { l ->
            putJsonObject(LASTFM) {
                put("session", l.session)
                put("username", l.username)
                l.apiKey?.let { put("apiKey", it) }
                l.secret?.let { put("secret", it) }
            }
        }
    }.toString().toByteArray(UTF_8)

    override fun toString() = "PairingExtras($kinds)"

    companion object {
        const val DISCORD = "discord"
        const val LASTFM = "lastfm"
        val KINDS = setOf(DISCORD, LASTFM)

        private val TOKEN = Regex("[A-Za-z0-9._-]{20,512}")
        private val KEY = Regex("[A-Za-z0-9_-]{1,128}")

        /** The known kinds in a comma-separated hint. */
        fun kinds(hint: String?): Set<String> = hint.orEmpty().split(',').map { it.trim() }.filter { it in KINDS }.toSet()

        /** What's well formed in [bytes]; anything else is left out rather than trusted. */
        fun decode(bytes: ByteArray): PairingExtras {
            require(bytes.size <= 16384)
            val o = Json.parseToJsonElement(String(bytes, UTF_8)).jsonObject
            fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val discord = (o[DISCORD] as? JsonObject)?.text("token")?.takeIf { TOKEN.matches(it) }?.let(::Discord)
            val lastFm = (o[LASTFM] as? JsonObject)?.let { l ->
                val session = l.text("session")?.takeIf { KEY.matches(it) } ?: return@let null
                val user = l.text("username")?.trim()?.takeIf { it.length in 1..64 && it.none { c -> c.isISOControl() } } ?: return@let null
                LastFm(session, user, l.text("apiKey")?.takeIf { KEY.matches(it) }, l.text("secret")?.takeIf { KEY.matches(it) })
            }
            return PairingExtras(discord, lastFm)
        }
    }
}

/**
 * One pairing connection, either end. The first packet is the YouTube Music cookie and the
 * answer is the "ack", exactly as every OpenTune has sent them. A device that asked for more
 * (its offer's `take`) then waits up to [EXTRAS_WAIT_MS] for one more sealed packet, purpose
 * "extras", and answers with which of them it kept ("extras-ack"). Each end takes the other
 * being older as there being nothing more to say: an older phone closes after the ack, and
 * an older device never asks, so the phone sends it nothing more.
 *
 * A device that opened the phone's packet but won't take it says why in a sealed "nack"
 * ([NACK_EXPIRED], [NACK_SESSION]) in place of the ack; an older phone, which can't open that
 * as an ack, fails as it always did when a device hung up.
 */
internal object PairingWire {
    const val EXTRAS_WAIT_MS = 2_000
    /** How long the phone waits for the device's answer: a slow TV saves the session before it says so. */
    const val ANSWER_MS = 10_000
    private const val ACK = "signed-in"
    const val NACK_EXPIRED = "expired"
    const val NACK_SESSION = "session"

    /** The phone's packet didn't open under the key: it was sealed for another code. */
    class WrongCode : Exception()

    /** The device turned the phone's packet down, and why. */
    class Refused(val why: String) : Exception(why)

    private fun DataOutputStream.packet(bytes: ByteArray) { writeInt(bytes.size); write(bytes); flush() }
    private fun DataInputStream.packet(max: Int): ByteArray {
        val size = readInt(); require(size in 29..max)
        return ByteArray(size).also(::readFully)
    }

    /** The device's end. Throws unless [accept] took the session; the extras never decide that. */
    fun serve(
        socket: Socket,
        key: ByteArray,
        take: Set<String>,
        accept: (String) -> Boolean,
        extras: ((PairingExtras) -> Set<String>)?,
        valid: () -> Boolean = { true },
    ) {
        socket.soTimeout = 2500
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val packet = input.packet(65536)
        val clear = try { PairingCrypto.open(key, packet, "session") } catch (_: java.security.GeneralSecurityException) { throw WrongCode() }
        fun refuse(why: String): Nothing {
            clear.fill(0)
            runCatching { output.packet(PairingCrypto.seal(key, why.toByteArray(UTF_8), "nack")) }
            throw Refused(why)
        }
        if (!valid()) refuse(NACK_EXPIRED)
        val cookie = String(clear, UTF_8)
        clear.fill(0)
        if (cookie.length > 32768 || !AccountStore.hasSession(cookie) || cookie.contains('\n') || cookie.contains('\r')) refuse(NACK_SESSION)
        if (!accept(cookie)) refuse(NACK_SESSION)
        output.packet(PairingCrypto.seal(key, ACK.toByteArray(UTF_8), "ack"))
        if (take.isEmpty() || extras == null) return
        runCatching {
            socket.soTimeout = EXTRAS_WAIT_MS
            val sealed = input.packet(65536)
            val plain = PairingCrypto.open(key, sealed, "extras")
            val offered = try { PairingExtras.decode(plain).only(take) } finally { plain.fill(0) }
            val took = if (offered.isEmpty) emptySet() else extras(offered) intersect offered.kinds
            output.packet(PairingCrypto.seal(key, took.sorted().joinToString(",").toByteArray(UTF_8), "extras-ack"))
        }
    }

    /**
     * The phone's end. Throws unless the device took the session ([Refused] when it said why);
     * returns which of [extras] it also took, empty when it took none or couldn't say (an older
     * device has closed).
     */
    fun exchange(socket: Socket, key: ByteArray, cookie: String, extras: PairingExtras?, answerMs: Int = ANSWER_MS): Set<String> {
        socket.soTimeout = answerMs
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        output.packet(PairingCrypto.seal(key, cookie.toByteArray(UTF_8), "session"))
        val answer = input.packet(256)
        val ack = runCatching { String(PairingCrypto.open(key, answer, "ack"), UTF_8) }.getOrNull()
        if (ack != ACK) {
            val why = runCatching { String(PairingCrypto.open(key, answer, "nack"), UTF_8) }.getOrNull()
            throw Refused(why ?: "unknown")
        }
        if (extras == null || extras.isEmpty) return emptySet()
        return runCatching {
            val plain = extras.encode()
            val sealed = try { PairingCrypto.seal(key, plain, "extras") } finally { plain.fill(0) }
            output.packet(sealed)
            PairingExtras.kinds(String(PairingCrypto.open(key, input.packet(256), "extras-ack"), UTF_8)) intersect extras.kinds
        }.getOrDefault(emptySet())
    }
}

/** Why a device's code ended without signing it in. Screens say each in their own words. */
class PairingFailed(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** This device has no address on a home network to put in a code. */
        NO_NETWORK,
        /** Two minutes went by with no phone reaching this device. */
        EXPIRED,
        /** A phone reached this device, but with another code's key (an older code, or another device's). */
        WRONG_CODE,
        /** A phone reached this device with this code, but its session wasn't one that signs requests. */
        BAD_SESSION,
        /** Too many connections that weren't the phone; the code was closed to be safe. */
        TOO_MANY_TRIES,
        /** Closed from here (Cancel, or a new code). */
        STOPPED,
    }
}

/**
 * The device's end: listens on every address it has (Wi-Fi and Ethernet both, whichever the
 * phone can reach), for two minutes by its own clock, and takes connections only from the home
 * network. TCP only, no HTTP redirect, DNS or proxy. Nothing is ever sent to a relay or website.
 * [addresses] are this device's own, best first: the first goes in the code where every phone
 * reads it, the rest ride along for newer phones.
 */
class PairingReceiver(
    take: Set<String> = emptySet(),
    addresses: () -> List<String> = { Lan.addresses() },
    private val steady: () -> Long = Lan::steadyMs,
) : AutoCloseable {
    private val server: ServerSocket
    val offer: PairingOffer
    @Volatile private var stopped = false

    init {
        val ordered = addresses().map { it.substringBefore('%') }.filter { Lan.accepts(it) }.distinct()
        val main = Lan.primary(ordered) ?: ordered.firstOrNull() ?: throw PairingFailed(PairingFailed.Reason.NO_NETWORK)
        // Every interface, not only the one in the code: a phone may reach a TV on its other one.
        server = ServerSocket().apply { bind(InetSocketAddress(0), 8); soTimeout = 1000 }
        offer = try {
            PairingOffer.create(main, server.localPort, take = take, also = ordered - main, steady = steady())
        } catch (e: Exception) {
            server.close(); throw e
        }
    }

    private fun fromHome(address: java.net.InetAddress?): Boolean =
        address != null && (Lan.text(address) != null || (Lan.allowLoopback && address.isLoopbackAddress))

    /**
     * Keeps listening after invalid packets. Only an authenticated, valid session consumes the offer.
     * [extras] gets those of the phone's other sign-ins this offer asked for, and answers which it kept.
     * Throws [PairingFailed] when the code ends without a sign-in.
     */
    fun receive(extras: ((PairingExtras) -> Set<String>)? = null, accept: (String) -> Boolean) {
        var strangers = 0
        var wrongCode = false
        var badSession = false
        while (offer.valid(steady()) && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: java.io.IOException) {
                if (server.isClosed) break else throw e
            }
            socket.use {
                if (!fromHome(it.inetAddress)) { strangers++; return@use }
                var accepted = false
                val outcome = runCatching {
                    PairingWire.serve(it, offer.key, offer.take, { cookie -> accept(cookie).also { ok -> accepted = ok } }, extras) { offer.valid(steady()) }
                }
                if (accepted) { close(); return }
                when (val e = outcome.exceptionOrNull()) {
                    is PairingWire.WrongCode -> { wrongCode = true; strangers++ }
                    is PairingWire.Refused -> if (e.why == PairingWire.NACK_SESSION) badSession = true
                    // A connection that said nothing: the phone trying another of this device's addresses, or a port scan.
                    else -> strangers++
                }
            }
            if (strangers >= MAX_STRANGERS) { close(); throw PairingFailed(PairingFailed.Reason.TOO_MANY_TRIES) }
        }
        val reason = when {
            stopped -> PairingFailed.Reason.STOPPED
            badSession -> PairingFailed.Reason.BAD_SESSION
            wrongCode -> PairingFailed.Reason.WRONG_CODE
            else -> PairingFailed.Reason.EXPIRED
        }
        close()
        throw PairingFailed(reason)
    }

    override fun close() {
        // Closed while the code was still good: someone stopped it, it didn't run out.
        if (!server.isClosed && offer.valid(steady())) stopped = true
        server.close()
        offer.key.fill(0)
    }

    private companion object { const val MAX_STRANGERS = 48 }
}

object PairingSender {
    /** Why the phone couldn't sign the device in. Screens say each in their own words. */
    class Failed(val reason: Reason, cause: Throwable? = null) : Lan.Definitive(reason.name) {
        init { cause?.let(::initCause) }

        enum class Reason {
            /** Two minutes since this phone read the code. */
            EXPIRED,
            /** None of the code's addresses is on a home network. */
            OFF_NETWORK,
            /** The device is there, but nothing listens for this code any more (it ran out, or was closed). */
            NOT_LISTENING,
            /** No answer from any of its addresses: another network, or a router keeping devices apart. */
            NO_ANSWER,
            /** This phone has no route to any of its addresses (no Wi-Fi). */
            NO_ROUTE,
            /** The device answered and hung up: it didn't take this code (a newer one is showing, or it was used). */
            REJECTED,
            /** The device read the code but said it had run out. */
            EXPIRED_THERE,
            /** The device said this phone's session doesn't sign requests. */
            BAD_SESSION,
            /** The session went, but the device never said whether it took it. */
            NO_REPLY,
        }
    }

    /**
     * Signs the device in with [cookie], then hands it those of [extras] its code asked for.
     * Returns which of them it took; throws [Failed] if the sign-in itself fails. Tries every
     * address the code gave, and [also] (where this phone has seen the device on the network),
     * all at once, through [sockets].
     */
    fun send(
        offer: PairingOffer,
        cookie: String,
        extras: PairingExtras? = null,
        also: List<String> = emptyList(),
        sockets: Lan.Sockets = Lan.Sockets.PLAIN,
        connectMs: Int = Lan.CONNECT_MS,
        answerMs: Int = PairingWire.ANSWER_MS,
        steady: Long = Lan.steadyMs(),
    ): Set<String> {
        require(AccountStore.hasSession(cookie) && cookie.length <= 32768)
        if (!offer.valid(steady)) throw Failed(Failed.Reason.EXPIRED)
        // Addresses only, never a host name: nothing is looked up, nothing goes off the network.
        val targets = (offer.hosts + also).filter(Lan::accepts).distinct()
            .flatMap { Lan.resolve(it) }.distinct().map { InetSocketAddress(it, offer.port) }
        if (targets.isEmpty()) throw Failed(Failed.Reason.OFF_NETWORK)
        val shared = extras?.only(offer.take)
        try {
            return Lan.reach(targets, sockets, connectMs) { socket ->
                try {
                    PairingWire.exchange(socket, offer.key, cookie, shared, answerMs)
                } catch (e: PairingWire.Refused) {
                    val reason = when (e.why) {
                        PairingWire.NACK_EXPIRED -> Failed.Reason.EXPIRED_THERE
                        PairingWire.NACK_SESSION -> Failed.Reason.BAD_SESSION
                        // Hung up without a word it could seal: another code's key, or an older device saying no.
                        else -> throw e
                    }
                    throw Failed(reason, e)
                }
            }
        } catch (e: Lan.Unreached) {
            val reason = when {
                e.rejected is java.net.SocketTimeoutException -> Failed.Reason.NO_REPLY
                e.rejected != null -> Failed.Reason.REJECTED
                e.refused -> Failed.Reason.NOT_LISTENING
                e.noRoute && !e.timedOut -> Failed.Reason.NO_ROUTE
                else -> Failed.Reason.NO_ANSWER
            }
            throw Failed(reason, e)
        }
    }
}
