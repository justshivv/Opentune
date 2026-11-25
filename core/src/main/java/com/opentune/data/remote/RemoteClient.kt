package com.opentune.data.remote

import com.opentune.data.account.Lan
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
class RemoteClient(
    /** Makes the sockets to the TV: on a phone, ones held to its Wi-Fi rather than mobile data or a VPN. */
    private val sockets: Lan.Sockets = Lan.Sockets.PLAIN,
    private val phoneName: () -> String,
) : AutoCloseable {
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
            var failure = com.opentune.i18n.Tr.f("Couldn't reach {0}. Open NepTune on the TV, on the same Wi-Fi.", tv.name)
            try {
                val key = PairingCrypto.decode(tv.key)
                var open: Opened? = null
                for ((host, port) in where) {
                    if (stopped) return
                    try { open = open(host, port, RemoteHandshake.CONTROL, SecureChannel.unhex(tv.id), key); onReached(host, port); break }
                    catch (e: AuthFailure) {
                        failure = if (e.refused) com.opentune.i18n.Tr.f("{0} doesn't know this phone any more. Pair it again.", tv.name) else "That doesn't look like the TV you paired with."
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
                if (!stopped) _state.value = State.Failed(tv.name, com.opentune.i18n.Tr.f("Lost the connection to {0}.", tv.name))
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
        var last: Exception? = null
        // A link-local address read from elsewhere is tried on each of this phone's interfaces.
        for (address in Lan.resolve(host)) {
            val socket = sockets.socket(address)
            try {
                socket.connect(InetSocketAddress(address, port), CONNECT_MS)
                return handshake(socket, mode, id, key)
            } catch (e: AuthFailure) {
                runCatching { socket.close() }
                throw e
            } catch (e: Exception) {
                runCatching { socket.close() }
                last = e
            }
        }
        throw last ?: IOException("no address")
    }

    /** Says who this phone is on a connected [socket], and checks the TV holds the key by opening its first frame. */
    private fun handshake(socket: Socket, mode: Int, id: ByteArray, key: ByteArray): Opened {
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
    }

    /**
     * Pairs with the TV that showed [invite]: proves this phone saw its screen, and receives a
     * key of its own. Returns the TV to keep. Tries the code's address and [also] (where this
     * phone has seen that same TV announce itself) at once. Throws [PairFailed] when the TV
     * can't be reached, the code has run out or someone else used it first.
     */
    fun pair(invite: RemoteInvite, also: List<Pair<String, Int>> = emptyList()): PairedTv {
        if (!invite.valid()) throw PairFailed(PairFailed.Reason.EXPIRED)
        val targets = (listOf(invite.host to invite.port) + also)
            .filter { (h, p) -> HomeNetwork.accepts(h) && p in 1024..65535 }.distinct()
            .flatMap { (h, p) -> Lan.resolve(h).map { InetSocketAddress(it, p) } }.distinct()
        if (targets.isEmpty()) throw PairFailed(PairFailed.Reason.OFF_NETWORK)
        try {
            return Lan.reach(targets, sockets, CONNECT_MS) { socket ->
                val o = handshake(socket, RemoteHandshake.PAIR, invite.id, invite.token)
                o.channel.send(RemoteMessage.PairRequest(phoneName()))
                val granted = o.channel.receive() as? RemoteMessage.PairGranted ?: throw PairFailed(PairFailed.Reason.USED)
                val at = socket.inetAddress?.let(Lan::text) ?: invite.host
                PairedTv(granted.tvId, o.tv, granted.id, granted.key, at, socket.port)
            }
        } catch (e: Lan.Unreached) {
            val auth = e.rejected as? AuthFailure
            throw PairFailed(
                when {
                    auth != null && auth.refused -> PairFailed.Reason.USED_OR_EXPIRED
                    auth != null -> PairFailed.Reason.WRONG_TV
                    e.rejected is PairFailed -> (e.rejected as PairFailed).reason
                    e.rejected is java.net.SocketTimeoutException -> PairFailed.Reason.NO_ANSWER
                    e.rejected != null -> PairFailed.Reason.USED
                    e.refused -> PairFailed.Reason.NOT_LISTENING
                    else -> PairFailed.Reason.NO_ANSWER
                },
            )
        }
    }

    /** Why pairing didn't work; [message] is what the phone says (translated where it's shown). */
    class PairFailed(val reason: Reason) : IOException(reason.text) {
        enum class Reason(val text: String) {
            EXPIRED("That code has run out. Make a new one on the TV."),
            OFF_NETWORK("The TV's address in that code isn't on your home network."),
            USED_OR_EXPIRED("The TV doesn't take that code any more. Make a new one on the TV."),
            WRONG_TV("That isn't the TV that showed the code."),
            USED("The TV didn't accept the code. It may have been used already."),
            NOT_LISTENING("The TV is on this network, but NepTune isn't waiting for a phone there. Open NepTune on the TV and make a new code."),
            NO_ANSWER("The TV didn't answer. Check the phone and the TV are on the same Wi-Fi, not a guest network, and that NepTune is open on the TV."),
        }
    }

    private companion object {
        const val CONNECT_MS = 5_000
        const val HANDSHAKE_MS = 4_000
        const val IDLE_MS = 30_000
        const val PING_MS = 8_000L
    }
}
