package com.opentune.data.remote

import com.opentune.data.account.PairingCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom

/**
 * Messages between a phone and a TV, one way each, sealed with AES-GCM under the key the two
 * share. Each frame is bound to this connection's [nonce] (chosen fresh by the TV) and its
 * position in the stream, so a recorded frame can't be played back later or out of order.
 * Whoever can seal a frame that opens holds the key; that is how each side knows the other.
 */
internal class SecureChannel(
    input: InputStream,
    output: OutputStream,
    private val key: ByteArray,
    private val nonce: ByteArray,
    /** What this side's frames are called ("s2c" from the TV, "c2s" from the phone), and the other side's. */
    private val outgoing: String,
    private val incoming: String,
) {
    private val data = DataInputStream(BufferedInputStream(input))
    private val out = DataOutputStream(BufferedOutputStream(output))
    private var sent = 0L
    private var received = 0L

    private fun purpose(direction: String, seq: Long) = "remote/$direction/${hex(nonce)}/$seq"

    @Synchronized
    fun send(message: RemoteMessage) {
        val packet = PairingCrypto.seal(key, RemoteCodec.encode(message).toByteArray(UTF_8), purpose(outgoing, sent++))
        out.writeInt(packet.size)
        out.write(packet)
        out.flush()
    }

    /**
     * The next message; null for one this version doesn't know. Throws when a frame is the wrong
     * size, doesn't open under the key (wrong key, or replayed, or out of order) or the stream ends.
     */
    fun receive(): RemoteMessage? {
        val size = data.readInt()
        require(size in MIN_FRAME..MAX_FRAME) { "frame of $size bytes" }
        val packet = ByteArray(size).also(data::readFully)
        val clear = PairingCrypto.open(key, packet, purpose(incoming, received++))
        return RemoteCodec.decode(String(clear, UTF_8))
    }

    companion object {
        private const val MIN_FRAME = 29
        private const val MAX_FRAME = 8 * 1024

        val random = SecureRandom()
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** What a connection starts with, before any sealed frame: who is asking, and for what. */
internal object RemoteHandshake {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte())
    const val CONTROL: Int = 1
    const val PAIR: Int = 2
    const val ID_BYTES = 8
    const val NONCE_BYTES = 16

    fun header(mode: Int, id: ByteArray): ByteArray {
        require(id.size == ID_BYTES)
        return MAGIC + byteArrayOf(mode.toByte()) + id
    }

    /** The mode and id in a header, or null when it isn't one. */
    fun parse(bytes: ByteArray): Pair<Int, ByteArray>? {
        if (bytes.size != MAGIC.size + 1 + ID_BYTES) return null
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val mode = bytes[MAGIC.size].toInt()
        if (mode != CONTROL && mode != PAIR) return null
        return mode to bytes.copyOfRange(MAGIC.size + 1, bytes.size)
    }

    val HEADER_SIZE = MAGIC.size + 1 + ID_BYTES
}

/** Which addresses the remote will talk to: the home network, and only it. */
internal object HomeNetwork {
    /** Tests run both ends on this machine, so they may allow its own address. */
    @Volatile var allowLoopback = false

    fun accepts(host: String): Boolean = PairingCrypto.privateHost(host) || (allowLoopback && host == "127.0.0.1")
}
