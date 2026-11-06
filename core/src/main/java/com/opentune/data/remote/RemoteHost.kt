package com.opentune.data.remote

import com.opentune.data.account.PairingCrypto
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** This device's address on the home network, if it has one. */
object LocalNetwork {
    fun privateAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is Inet4Address && PairingCrypto.privateHost(it.hostAddress.orEmpty()) }
            ?.hostAddress
    }.getOrNull()
}

/**
 * The TV's end: listens on the home network for the phones in [phones], and for a new phone
 * that holds a live [RemoteInvite]. A paired phone's messages go to [onMessage] (on a thread of
 * its own; the caller moves them to the one it needs); [push] sends something to every phone
 * connected. Nothing is ever sent to a phone that can't prove, with its key, that it is one.
 */
class RemoteHost(
    private val phones: PairedPhones,
    private val tvName: () -> String,
    private val onMessage: (RemoteMessage) -> Unit,
    /** Called with the names of the phones connected, when that changes. */
    private val onControllers: (List<String>) -> Unit = {},
    private val address: () -> String? = LocalNetwork::privateAddress,
    port: Int = RemoteProtocol.DEFAULT_PORT,
) : AutoCloseable {
    private class Controller(val phoneId: String, val name: String, val socket: Socket, val channel: SecureChannel)

    private val server: ServerSocket = try { ServerSocket(port) } catch (_: IOException) { ServerSocket(0) }
    /** Where it listens: announced to phones, and written into invites. */
    val port: Int = server.localPort

    @Volatile private var invite: RemoteInvite? = null
    @Volatile private var closed = false
    private val controllers = CopyOnWriteArrayList<Controller>()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "remote-host-send").apply { isDaemon = true } }
    private val handlers = Executors.newCachedThreadPool { r -> Thread(r, "remote-host").apply { isDaemon = true } }

    init {
        Thread({
            while (!closed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                try { handlers.execute { serve(socket) } } catch (_: RejectedExecutionException) { socket.close() }
            }
        }, "remote-host-accept").apply { isDaemon = true }.start()
    }

    /** A new invite, to show as a QR code; it replaces any earlier one. */
    fun createInvite(): RemoteInvite {
        val host = address() ?: error("Connect this TV to your home network first.")
        return RemoteInvite.create(host, port, phones.tvId, tvName()).also { invite = it }
    }

    fun cancelInvite() { invite = null }

    /** Sends [message] to every phone connected. */
    fun push(message: RemoteMessage) {
        if (controllers.isEmpty()) return
        try {
            sender.execute {
                for (c in controllers) {
                    try { c.channel.send(message) } catch (_: Exception) { drop(c) }
                }
            }
        } catch (_: RejectedExecutionException) { }
    }

    /** Cuts the connection of a phone that has just been removed. */
    fun disconnect(phoneId: String) {
        controllers.filter { it.phoneId == phoneId }.forEach { drop(it) }
    }

    private fun drop(c: Controller) {
        runCatching { c.socket.close() }
        if (controllers.remove(c)) onControllers(controllers.map { it.name })
    }

    private fun serve(socket: Socket) {
        var controller: Controller? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val raw = ByteArray(RemoteHandshake.HEADER_SIZE)
            DataInputStream(input).readFully(raw)
            val (mode, idBytes) = RemoteHandshake.parse(raw) ?: return
            val id = SecureChannel.hex(idBytes)
            val pairing = mode == RemoteHandshake.PAIR
            val offer = invite
            val key = if (pairing) offer?.takeIf { it.valid() && SecureChannel.hex(it.id) == id }?.token
            else phones.find(id)?.key?.let(PairingCrypto::decode)
            // An unknown id gets nothing, not even a nonce, so the TV tells a stranger no more than it must.
            key ?: return

            val nonce = ByteArray(RemoteHandshake.NONCE_BYTES).also(SecureChannel.random::nextBytes)
            output.write(nonce); output.flush()
            val channel = SecureChannel(input, output, key, nonce, outgoing = "s2c", incoming = "c2s")
            channel.send(RemoteMessage.Welcome(tvName()))
            val first = channel.receive()

            if (pairing) {
                val request = first as? RemoteMessage.PairRequest ?: return
                // One use: taken before the key is made, so a second phone with the same QR finds it gone.
                synchronized(this) { if (invite?.let { SecureChannel.hex(it.id) } != id) return; invite = null }
                val phone = phones.add(request.name)
                channel.send(RemoteMessage.PairGranted(phone.id, phone.key, phones.tvId))
                return
            }

            val hello = first as? RemoteMessage.Hello ?: return
            val phone = phones.find(id) ?: return
            socket.soTimeout = IDLE_MS
            controller = Controller(phone.id, hello.name.take(40).ifBlank { phone.name }, socket, channel)
                .also { controllers += it; onControllers(controllers.map { c -> c.name }) }
            while (!closed) {
                when (val m = channel.receive()) {
                    null, is RemoteMessage.Ping, is RemoteMessage.Hello -> Unit
                    // Only a TV says these; a phone that does is wrong, and ignored.
                    is RemoteMessage.Welcome, is RemoteMessage.Now, is RemoteMessage.PairGranted, is RemoteMessage.PairRequest -> Unit
                    else -> onMessage(m)
                }
            }
        } catch (_: Exception) {
            // A bad key, a replay, a timeout or a phone that left: all end the connection the same way.
        } finally {
            controller?.let(::drop)
            runCatching { socket.close() }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        controllers.toList().forEach(::drop)
        sender.shutdownNow()
        handlers.shutdownNow()
    }

    private companion object {
        const val HANDSHAKE_MS = 4_000
        /** A phone pings every few seconds; this long without a word and it is gone. */
        const val IDLE_MS = 30_000
    }
}
