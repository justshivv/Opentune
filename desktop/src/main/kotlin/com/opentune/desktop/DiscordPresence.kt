package com.opentune.desktop

import com.opentune.data.model.Song
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Listening to OpenTune" on the listener's Discord profile, through the
 * Discord app running on the same computer (its local RPC socket), the way
 * Discord means apps to do it. No account token is involved: it is OpenTune's
 * own Discord application (the one the web extension uses) and works only
 * while the Discord app is open. Paused or stopped, the card comes down.
 */
object DiscordPresence {
    /** OpenTune's Discord application (web/extension/lib/discord.ts). */
    private const val CLIENT_ID = "1543679166967193620"
    private const val OP_HANDSHAKE = 0
    private const val OP_FRAME = 1
    private const val OP_CLOSE = 2
    private const val RETRY_MS = 15_000L

    enum class Status { Off, NotRunning, Connected }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file = File(System.getProperty("user.home"), ".opentune/discord.json")

    private val _enabled = MutableStateFlow(runCatching { JSONObject(file.readText()).optBoolean("enabled", true) }.getOrDefault(true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _status = MutableStateFlow(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        runCatching {
            file.parentFile.mkdirs()
            file.writeText(JSONObject().put("enabled", on).toString())
        }
    }

    // ---- The socket ------------------------------------------------------------------

    /** One connection to the Discord app: a Unix socket, or a named pipe on Windows. */
    private interface Pipe : Closeable {
        fun write(bytes: ByteArray)
        fun readFully(bytes: ByteArray)
    }

    private class UnixPipe(path: File) : Pipe {
        private val channel = SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(path.toPath())) }
        override fun write(bytes: ByteArray) { val b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) channel.write(b) }
        override fun readFully(bytes: ByteArray) {
            val b = ByteBuffer.wrap(bytes)
            while (b.hasRemaining()) if (channel.read(b) < 0) throw IOException("Discord closed the connection")
        }
        override fun close() = channel.close()
    }

    private class WindowsPipe(name: String) : Pipe {
        private val file = RandomAccessFile(name, "rw")
        override fun write(bytes: ByteArray) = file.write(bytes)
        override fun readFully(bytes: ByteArray) = file.readFully(bytes)
        override fun close() = file.close()
    }

    /** For tests: the folders to look in instead of the system's. */
    internal var searchRoots: List<String>? = null

    /** For tests: the on/off switch kept in [at] instead. */
    internal fun useFile(at: File) {
        file = at
    }

    /** Where the Discord app listens: discord-ipc-0…9, in the usual folders and the Flatpak and Snap ones. */
    private fun open(): Pipe? {
        val windows = searchRoots == null && System.getProperty("os.name").lowercase().contains("win")
        for (i in 0..9) {
            if (windows) {
                runCatching { return WindowsPipe("\\\\.\\pipe\\discord-ipc-$i") }
                continue
            }
            val roots = searchRoots ?: listOfNotNull(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP"), "/tmp").distinct()
            for (root in roots) for (sub in listOf("", "app/com.discordapp.Discord", "snap.discord", ".flatpak/com.discordapp.Discord/xdg-run")) {
                val path = File(File(root, sub), "discord-ipc-$i")
                if (path.exists()) runCatching { return UnixPipe(path) }
            }
        }
        return null
    }

    private fun Pipe.send(op: Int, payload: JSONObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        write(ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).put(body).array())
    }

    private fun Pipe.receive(): Pair<Int, JSONObject> {
        val header = ByteArray(8).also(::readFully)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val op = b.int
        val length = b.int
        if (length !in 0..1_000_000) throw IOException("Discord sent a frame of $length bytes")
        val body = ByteArray(length).also(::readFully)
        return op to runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }

    // ---- The card --------------------------------------------------------------------

    /** Discord wants 2 to 128 characters in each line; a short one is padded with a blank it won't trim. */
    private fun line(text: String): String = text.trim().take(128).padEnd(2, '\u2800')

    /** A bigger cover than the list's thumbnail, where the address says how big. */
    private fun cover(url: String?): String? =
        url?.takeIf { it.startsWith("https://") }?.replace(Regex("=w\\d+-h\\d+[^/]*$"), "=w512-h512")?.takeIf { it.length <= 300 }

    /** What the card says for [song], [positionMs] into its [durationMs]. */
    internal fun activity(song: Song, positionMs: Long, durationMs: Long, withType: Boolean): JSONObject {
        val started = System.currentTimeMillis() - positionMs
        return JSONObject().apply {
            if (withType) put("type", 2) // Listening
            put("details", line(song.title))
            put("state", line(song.artist.ifBlank { "Unknown artist" }))
            put("timestamps", JSONObject().put("start", started).apply { if (durationMs > 0) put("end", started + durationMs) })
            put("assets", JSONObject().apply {
                cover(song.thumbnailUrl)?.let { put("large_image", it) }
                put("large_text", line(song.albumName ?: song.title))
            })
            if (isYouTubeId(song.videoId)) {
                put("buttons", JSONArray().put(JSONObject().put("label", "Listen on YouTube Music").put("url", "https://music.youtube.com/watch?v=${song.videoId}")))
            }
        }
    }

    private fun isYouTubeId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{11}"))

    /**
     * Keeps the card in step with the player: connects while it's on and Discord
     * is running, sets the song when it plays (again after a seek), clears it on
     * pause, and tries again every 15 seconds when Discord isn't there.
     */
    fun start() {
        scope.launch {
            var pipe: Pipe? = null
            var shown: String? = null
            // An older Discord refuses the activity type; then the card goes without it.
            var withType = true
            fun disconnect() {
                runCatching { pipe?.close() }
                pipe = null
                shown = null
            }
            while (isActive) {
                if (!_enabled.value) {
                    if (pipe != null) {
                        runCatching { pipe!!.send(OP_FRAME, setActivity(null)); pipe!!.receive() }
                        disconnect()
                    }
                    _status.value = Status.Off
                    delay(1_000)
                    continue
                }
                if (pipe == null) {
                    pipe = runCatching {
                        open()?.also { p ->
                            p.send(OP_HANDSHAKE, JSONObject().put("v", 1).put("client_id", CLIENT_ID))
                            val (op, ready) = p.receive()
                            if (op == OP_CLOSE || ready.optString("evt") != "READY") { p.close(); throw IOException(ready.optString("message", "Discord refused the connection")) }
                        }
                    }.getOrNull()
                    if (pipe == null) {
                        _status.value = Status.NotRunning
                        delay(RETRY_MS)
                        continue
                    }
                    _status.value = Status.Connected
                }
                val song = Player.current
                val playing = Player.playing.value && song != null
                val position = Player.positionMs()
                val duration = song?.let { s -> Player.durationOf.value.takeIf { it.first == s.videoId }?.second ?: s.durationText?.let(Player::seconds)?.times(1000)?.toLong() } ?: 0L
                // What's on the card, to the nearest few seconds: a seek moves it, ordinary playback doesn't.
                val want = if (playing) "${song!!.videoId}@${(System.currentTimeMillis() - position) / 3_000}" else null
                if (want != shown) {
                    val sent = runCatching {
                        pipe!!.send(OP_FRAME, setActivity(if (playing) activity(song!!, position, duration, withType) else null))
                        val (op, answer) = pipe!!.receive()
                        if (op == OP_CLOSE) throw IOException("Discord closed the connection")
                        if (answer.optString("evt") == "ERROR" && withType) {
                            withType = false
                            false
                        } else {
                            true
                        }
                    }
                    when {
                        sent.isFailure -> { disconnect(); _status.value = Status.NotRunning; delay(RETRY_MS); continue }
                        sent.getOrDefault(false) -> shown = want
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun setActivity(activity: JSONObject?): JSONObject = JSONObject()
        .put("cmd", "SET_ACTIVITY")
        .put("args", JSONObject().put("pid", ProcessHandle.current().pid()).put("activity", activity ?: JSONObject.NULL))
        .put("nonce", UUID.randomUUID().toString())
}
