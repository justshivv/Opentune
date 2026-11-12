package com.opentune.data.account

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
        cipher.updateAAD("WEAV pairing v1/$purpose".toByteArray(UTF_8))
        return nonce + cipher.doFinal(value)
    }
    fun open(key: ByteArray, packet: ByteArray, purpose: String): ByteArray {
        require(packet.size in 29..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.copyOfRange(0, 12)))
        cipher.updateAAD("WEAV pairing v1/$purpose".toByteArray(UTF_8))
        return cipher.doFinal(packet, 12, packet.size - 12)
    }
}

class PairingOffer private constructor(val host: String, val port: Int, val expires: Long, internal val key: ByteArray) {
    val payload: String get() = PairingCrypto.encode("1|$host|$port|$expires|${PairingCrypto.encode(key)}".toByteArray(UTF_8))
    val link: String get() = "opentune://pair/#$payload"
    fun valid(now: Long = System.currentTimeMillis()): Boolean = expires > now && expires - now <= 125_000
    companion object {
        fun create(host: String, port: Int, now: Long = System.currentTimeMillis()): PairingOffer {
            require(PairingCrypto.privateHost(host) && port in 1024..65535)
            return PairingOffer(host, port, now + 120_000, PairingCrypto.secret())
        }
        fun parse(payload: String, now: Long = System.currentTimeMillis()): PairingOffer {
            require(payload.length in 40..512)
            val fields = String(PairingCrypto.decode(payload), UTF_8).split('|')
            require(fields.size == 5 && fields[0] == "1" && PairingCrypto.privateHost(fields[1]))
            val port = fields[2].toInt(); require(port in 1024..65535)
            val key = PairingCrypto.decode(fields[4]); require(key.size == 32)
            return PairingOffer(fields[1], port, fields[3].toLong(), key).also { require(it.valid(now)) }
        }
    }
}

/** TCP only, no HTTP redirect, DNS or proxy. Nothing is ever sent to a relay or website. */
class PairingReceiver : AutoCloseable {
    private val server: ServerSocket
    val offer: PairingOffer
    init {
        val address = NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is Inet4Address && PairingCrypto.privateHost(it.hostAddress.orEmpty()) }
            ?: error("Connect this device and your phone to the same Wi-Fi network.")
        server = ServerSocket(0, 2, address).apply { soTimeout = 1000 }
        offer = PairingOffer.create(address.hostAddress!!, server.localPort)
    }
    /** Keeps listening after invalid packets. Only an authenticated, valid session consumes the offer. */
    fun receive(accept: (String) -> Boolean) {
        var attempts = 0
        while (offer.valid() && !server.isClosed && attempts < 24) {
            val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
            socket.use {
                attempts++
                runCatching {
                    it.soTimeout = 2500
                    val input = java.io.DataInputStream(it.getInputStream())
                    val size = input.readInt(); require(size in 29..65536)
                    val packet = ByteArray(size).also(input::readFully)
                    require(offer.valid())
                    val clear = PairingCrypto.open(offer.key, packet, "session")
                    val cookie = try { String(clear, UTF_8) } finally { clear.fill(0) }
                    require(cookie.length <= 32768 && AccountStore.hasSession(cookie) && !cookie.contains('\n') && !cookie.contains('\r'))
                    require(accept(cookie))
                    val reply = PairingCrypto.seal(offer.key, "signed-in".toByteArray(UTF_8), "ack")
                    try { java.io.DataOutputStream(it.getOutputStream()).apply { writeInt(reply.size); write(reply); flush() } }
                    finally { close() }
                }
            }
        }
        if (!server.isClosed) { close(); error("Pairing expired. Generate a new QR code and try again.") }
    }
    override fun close() { server.close(); offer.key.fill(0) }
}

object PairingSender {
    fun send(offer: PairingOffer, cookie: String) {
        require(offer.valid() && AccountStore.hasSession(cookie) && cookie.length <= 32768)
        val packet = PairingCrypto.seal(offer.key, cookie.toByteArray(UTF_8), "session")
        Socket().use { socket ->
            // parse() only permits canonical RFC1918 IPv4 literals, never host names.
            socket.connect(InetSocketAddress(InetAddress.getByName(offer.host), offer.port), 5000)
            socket.soTimeout = 5000
            java.io.DataOutputStream(socket.getOutputStream()).apply { writeInt(packet.size); write(packet); flush() }
            val input = java.io.DataInputStream(socket.getInputStream())
            val size = input.readInt(); require(size in 29..256)
            val reply = ByteArray(size).also(input::readFully)
            require(String(PairingCrypto.open(offer.key, reply, "ack"), UTF_8) == "signed-in")
        }
    }
}
