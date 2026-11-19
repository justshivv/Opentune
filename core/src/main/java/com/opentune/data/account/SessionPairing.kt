package com.opentune.data.account

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
    fun privateHost(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it[0] == '0') }) return false
        val ip = parts.map { it.toInt() }
        return ip.all { it in 0..255 } && (ip[0] == 10 || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 192 && ip[1] == 168))
    }
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
 */
class PairingOffer private constructor(
    val host: String,
    val port: Int,
    val expires: Long,
    internal val key: ByteArray,
    val take: Set<String> = emptySet(),
) {
    val payload: String get() = PairingCrypto.encode("1|$host|$port|$expires|${PairingCrypto.encode(key)}".toByteArray(UTF_8))
    val link: String get() =
        if (take.isEmpty()) "opentune://pair/#$payload" else "opentune://pair/?take=${take.sorted().joinToString(",")}#$payload"
    fun valid(now: Long = System.currentTimeMillis()): Boolean = expires > now && expires - now <= 125_000
    companion object {
        fun create(host: String, port: Int, now: Long = System.currentTimeMillis(), take: Set<String> = emptySet()): PairingOffer {
            require(PairingCrypto.privateHost(host) && port in 1024..65535)
            return PairingOffer(host, port, now + 120_000, PairingCrypto.secret(), take intersect PairingExtras.KINDS)
        }
        /** [take] is the link's `take` query (comma separated), when it had one. */
        fun parse(payload: String, now: Long = System.currentTimeMillis(), take: String? = null): PairingOffer {
            require(payload.length in 40..512)
            val fields = String(PairingCrypto.decode(payload), UTF_8).split('|')
            require(fields.size == 5 && fields[0] == "1" && PairingCrypto.privateHost(fields[1]))
            val port = fields[2].toInt(); require(port in 1024..65535)
            val key = PairingCrypto.decode(fields[4]); require(key.size == 32)
            return PairingOffer(fields[1], port, fields[3].toLong(), key, PairingExtras.kinds(take)).also { require(it.valid(now)) }
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
 */
internal object PairingWire {
    const val EXTRAS_WAIT_MS = 2_000
    private const val ACK = "signed-in"

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
        require(valid())
        val clear = PairingCrypto.open(key, packet, "session")
        val cookie = try { String(clear, UTF_8) } finally { clear.fill(0) }
        require(cookie.length <= 32768 && AccountStore.hasSession(cookie) && !cookie.contains('\n') && !cookie.contains('\r'))
        require(accept(cookie))
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
     * The phone's end. Throws unless the device took the session; returns which of [extras]
     * it also took, empty when it took none or couldn't say (an older device has closed).
     */
    fun exchange(socket: Socket, key: ByteArray, cookie: String, extras: PairingExtras?): Set<String> {
        socket.soTimeout = 5000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        output.packet(PairingCrypto.seal(key, cookie.toByteArray(UTF_8), "session"))
        require(String(PairingCrypto.open(key, input.packet(256), "ack"), UTF_8) == ACK)
        if (extras == null || extras.isEmpty) return emptySet()
        return runCatching {
            val plain = extras.encode()
            val sealed = try { PairingCrypto.seal(key, plain, "extras") } finally { plain.fill(0) }
            output.packet(sealed)
            PairingExtras.kinds(String(PairingCrypto.open(key, input.packet(256), "extras-ack"), UTF_8)) intersect extras.kinds
        }.getOrDefault(emptySet())
    }
}

/** TCP only, no HTTP redirect, DNS or proxy. Nothing is ever sent to a relay or website. */
class PairingReceiver(take: Set<String> = emptySet()) : AutoCloseable {
    private val server: ServerSocket
    val offer: PairingOffer
    init {
        val address = NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is Inet4Address && PairingCrypto.privateHost(it.hostAddress.orEmpty()) }
            ?: error("Connect this device and your phone to the same Wi-Fi network.")
        server = ServerSocket(0, 2, address).apply { soTimeout = 1000 }
        offer = PairingOffer.create(address.hostAddress!!, server.localPort, take = take)
    }
    /**
     * Keeps listening after invalid packets. Only an authenticated, valid session consumes the offer.
     * [extras] gets those of the phone's other sign-ins this offer asked for, and answers which it kept.
     */
    fun receive(extras: ((PairingExtras) -> Set<String>)? = null, accept: (String) -> Boolean) {
        var attempts = 0
        while (offer.valid() && !server.isClosed && attempts < 24) {
            val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
            socket.use {
                attempts++
                var accepted = false
                runCatching { PairingWire.serve(it, offer.key, offer.take, { cookie -> accept(cookie).also { ok -> accepted = ok } }, extras, offer::valid) }
                if (accepted) close()
            }
        }
        if (!server.isClosed) { close(); error("Pairing expired. Generate a new QR code and try again.") }
    }
    override fun close() { server.close(); offer.key.fill(0) }
}

object PairingSender {
    /**
     * Signs the device in with [cookie], then hands it those of [extras] its code asked for.
     * Returns which of them it took; the sign-in itself throws if it fails.
     */
    fun send(offer: PairingOffer, cookie: String, extras: PairingExtras? = null): Set<String> {
        require(offer.valid() && AccountStore.hasSession(cookie) && cookie.length <= 32768)
        Socket().use { socket ->
            // parse() only permits canonical RFC1918 IPv4 literals, never host names.
            socket.connect(InetSocketAddress(InetAddress.getByName(offer.host), offer.port), 5000)
            return PairingWire.exchange(socket, offer.key, cookie, extras?.only(offer.take))
        }
    }
}
