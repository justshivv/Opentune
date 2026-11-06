package com.opentune.data.remote

import com.opentune.data.account.PairingCrypto
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's end: pairs with a TV once, then connects to it whenever the remote is open.
 * Everything here blocks or runs on threads of its own; call [pair] off the main thread.
 */
class RemoteClient(private val phoneName: () -> String) : AutoCloseable {
    sealed interface State {
        data object Idle : State
        data class Connecting(val tv: String) : State
        data class Connected(val tv: String) : State
        data class Failed(val tv: String, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _now = MutableStateFlow<RemoteMessage.Now?>(null)
    /** What the TV is playing, as it last said. */
    val now: StateFlow<RemoteMessage.Now?> = _now.asStateFlow()

    @Volatile private var session: Session? = null

    /**
     * Connects to [tv], trying each of [where] (host and port) until one answers. [onReached] is
     * told which did, so the phone can remember it for networks that hide the TV from discovery.
     */
    fun connect(tv: PairedTv, where: List<Pair<String, Int>>, onReached: (String, Int) -> Unit = { _, _ -> }) {
        disconnect()
        _now.value = null
        _state.value = State.Connecting(tv.name)
        val s = Session()
        session = s
        Thread({ s.run(tv, where, onReached) }, "remote-client").apply { isDaemon = true }.start()
    }

    /** Sends [message] to the TV; dropped when there is no connection. */
    fun send(message: RemoteMessage) { session?.queue?.offer(message) }

    fun disconnect() {
        session?.stop()
        session = null
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    override fun close() = disconnect()

    private inner class Session {
        val queue = LinkedBlockingQueue<RemoteMessage>()
        @Volatile private var stopped = false
        @Volatile private var socket: Socket? = null

        fun stop() { stopped = true; runCatching { socket?.close() } }

        fun run(tv: PairedTv, where: List<Pair<String, Int>>, onReached: (String, Int) -> Unit) {
            var failure = "Couldn't reach ${tv.name}. Open OpenTune on the TV, on the same Wi-Fi."
            try {
                val key = PairingCrypto.decode(tv.key)
                var open: Opened? = null
                for ((host, port) in where) {
                    if (stopped) return
                    try { open = open(host, port, RemoteHandshake.CONTROL, SecureChannel.unhex(tv.id), key); onReached(host, port); break }
                    catch (e: AuthFailure) {
                        failure = if (e.refused) "${tv.name} doesn't know this phone any more. Pair it again." else "That doesn't look like the TV you paired with."
                        break
                    }
                    catch (_: Exception) { }
                }
                val o = open ?: run { if (!stopped) _state.value = State.Failed(tv.name, failure); return }
                socket = o.socket
                o.channel.send(RemoteMessage.Hello(phoneName()))
                if (stopped) return
                _state.value = State.Connected(o.tv)
                val writer = Thread({ write(o.channel) }, "remote-client-send").apply { isDaemon = true; start() }
                o.socket.soTimeout = IDLE_MS
                try {
                    while (!stopped) {
                        when (val m = o.channel.receive()) {
                            is RemoteMessage.Now -> _now.value = m
                            else -> Unit
                        }
                    }
                } finally { writer.interrupt() }
            } catch (_: Exception) {
                if (!stopped) _state.value = State.Failed(tv.name, "Lost the connection to ${tv.name}.")
            } finally {
                runCatching { socket?.close() }
            }
        }

        private fun write(channel: SecureChannel) {
            try {
                while (!stopped) {
                    // A ping when nothing else has gone for a while, so the TV knows this phone is still there.
                    val m = queue.poll(PING_MS, TimeUnit.MILLISECONDS) ?: RemoteMessage.Ping(System.currentTimeMillis())
                    channel.send(m)
                }
            } catch (_: Exception) {
                stop()
            }
        }
    }

    /** The TV answered but won't talk: [refused] when it hung up on this phone's id, else its key didn't match. */
    private class AuthFailure(val refused: Boolean) : Exception()
    private class Opened(val socket: Socket, val channel: SecureChannel, val tv: String)

    /** Connects, and checks the TV holds the key by opening its first frame. */
    private fun open(host: String, port: Int, mode: Int, id: ByteArray, key: ByteArray): Opened {
        require(HomeNetwork.accepts(host) && port in 1024..65535) { "not on the home network" }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_MS
            socket.getOutputStream().apply { write(RemoteHandshake.header(mode, id)); flush() }
            // A TV that doesn't know this id hangs up without a word.
            val nonce = ByteArray(RemoteHandshake.NONCE_BYTES).also {
                try { java.io.DataInputStream(socket.getInputStream()).readFully(it) } catch (e: java.io.EOFException) { throw AuthFailure(refused = true) }
            }
            val channel = SecureChannel(socket.getInputStream(), socket.getOutputStream(), key, nonce, outgoing = "c2s", incoming = "s2c")
            val welcome = try { channel.receive() } catch (e: java.security.GeneralSecurityException) { throw AuthFailure(refused = false) }
            return Opened(socket, channel, (welcome as? RemoteMessage.Welcome)?.tv ?: throw AuthFailure(refused = false))
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Pairs with the TV that showed [invite]: proves this phone saw its screen, and receives a
     * key of its own. Returns the TV to keep. Throws when the TV can't be reached, the code has
     * run out or someone else used it first.
     */
    fun pair(invite: RemoteInvite): PairedTv {
        check(invite.valid()) { "That code has run out. Make a new one on the TV." }
        val o = try { open(invite.host, invite.port, RemoteHandshake.PAIR, invite.id, invite.token) }
        catch (e: AuthFailure) {
            throw IOException(if (e.refused) "The TV doesn't take that code any more. Make a new one on the TV." else "That isn't the TV that showed the code.")
        }
        catch (e: IOException) { throw IOException("Couldn't reach the TV. Keep both on the same Wi-Fi, with OpenTune open on the TV.") }
        o.socket.use {
            o.channel.send(RemoteMessage.PairRequest(phoneName()))
            val granted = o.channel.receive() as? RemoteMessage.PairGranted ?: throw IOException("The TV didn't accept the code. It may have been used already.")
            return PairedTv(granted.tvId, o.tv, granted.id, granted.key, invite.host, invite.port)
        }
    }

    private companion object {
        const val CONNECT_MS = 3_500
        const val HANDSHAKE_MS = 4_000
        const val IDLE_MS = 30_000
        const val PING_MS = 8_000L
    }
}
