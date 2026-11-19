package com.opentune.desktop

import com.opentune.data.model.Song
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The system's own media controls, kept in step with the player: the media flyout and lock
 * screen on Windows (SMTC), Now Playing on macOS, and MPRIS on Linux, which the desktop's
 * panel, its media keys and tools like playerctl all speak. Each shows the song, its cover and
 * where it's got to, and sends play, pause, next, previous and seek back here.
 */
internal object MediaControls {
    enum class Command(val code: Int) { PLAY(1), PAUSE(2), TOGGLE(3), NEXT(4), PREVIOUS(5), STOP(6), SEEK(7) }

    /** What the controls show. */
    data class Track(val id: String, val title: String, val artist: String, val album: String, val artUrl: String?)

    /** One system's controls. */
    interface Backend {
        val name: String
        /** Takes the controls; false when this system has none to take. */
        fun start(onCommand: (Command, Long) -> Unit): Boolean
        fun track(track: Track?)
        /** 2 playing, 1 paused, 0 stopped. */
        fun state(state: Int, positionMs: Long, durationMs: Long)
        fun stop()
    }

    /** Every call to a backend from this one thread: WinRT wants the same apartment each time. */
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "media-controls").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher())
    @Volatile private var backend: Backend? = null

    /** Which controls are in use, for the log and Settings; null before [start] or where there are none. */
    val inUse: String? get() = backend?.name

    fun start() {
        scope.launch {
            val b = when {
                Platform.isWindows() || Platform.isMac() -> NativeMedia
                Platform.isLinux() -> Mpris
                else -> null
            } ?: return@launch
            val ok = runCatching { b.start(::handle) }.onFailure { System.err.println("[media] ${b.name}: ${it.message}") }.getOrDefault(false)
            if (!ok) return@launch
            backend = b
            System.err.println("[media] ${b.name}")
            follow(b)
        }
    }

    private fun follow(b: Backend) {
        scope.launch {
            combine(Player.queue, Player.index) { q, i -> q.getOrNull(i) }.distinctUntilChanged().collect { song ->
                runCatching { b.track(song?.let(::trackOf)) }
            }
        }
        scope.launch {
            // The state when it changes, and where the song is every few seconds while it plays,
            // so a lock screen's progress bar doesn't drift.
            var last = Triple(-1, -1L, -1L)
            var sent = 0L
            while (true) {
                val state = when {
                    Player.current == null -> 0
                    Player.playing.value -> 2
                    else -> 1
                }
                val at = Player.positionMs()
                val length = Player.durationMs()
                val now = System.currentTimeMillis()
                // A jump the clock alone wouldn't make: a seek, or a new song.
                val expected = if (last.first == 2) last.second + (now - sent) else last.second
                val jumped = kotlin.math.abs(at - expected) > 1_500
                if (state != last.first || length != last.third || jumped || (state == 2 && now - sent >= 5_000)) {
                    runCatching { b.state(state, at, length) }
                    last = Triple(state, at, length)
                    sent = now
                }
                delay(500)
            }
        }
    }

    internal fun trackOf(song: Song) = Track(
        id = song.videoId,
        title = song.title,
        artist = song.artist,
        album = song.albumName.orEmpty(),
        // A larger cover than the list's, from YouTube's own resizing.
        artUrl = song.thumbnailUrl?.takeIf { it.startsWith("https://") }?.replace(Regex("=w\\d+-h\\d+[^/]*$"), "=w544-h544"),
    )

    private fun handle(command: Command, value: Long) {
        when (command) {
            Command.PLAY -> Player.play()
            Command.PAUSE, Command.STOP -> Player.pause()
            Command.TOGGLE -> Player.toggle()
            Command.NEXT -> Player.next()
            Command.PREVIOUS -> Player.previous()
            Command.SEEK -> Player.seekTo(value.coerceAtLeast(0))
        }
    }

    fun stop() {
        val b = backend ?: return
        backend = null
        runCatching { thread.submit { b.stop() }.get(2, java.util.concurrent.TimeUnit.SECONDS) }
    }
}

/** otmedia's functions (desktop/native/otmedia_win.c and otmedia_mac.m), as JNA calls them. */
@Suppress("FunctionName")
internal interface OtMedia : Library {
    fun interface CommandCallback : Callback {
        fun invoke(command: Int, value: Long)
    }

    fun otm_start(window: Pointer?, callback: CommandCallback): Int
    fun otm_metadata(title: String, artist: String, album: String, artUrl: String?)
    fun otm_state(state: Int, positionMs: Long, durationMs: Long)
    fun otm_stop()
}

/** SMTC on Windows, Now Playing on macOS, through otmedia. */
internal object NativeMedia : MediaControls.Backend {
    override val name get() = if (Platform.isWindows()) "Windows media controls" else "macOS Now Playing"
    private var lib: OtMedia? = null
    // Held here for as long as the library may call it: JNA's callbacks mustn't be collected.
    private var callback: OtMedia.CommandCallback? = null

    override fun start(onCommand: (MediaControls.Command, Long) -> Unit): Boolean {
        val l = Native.load("otmedia", OtMedia::class.java)
        val cb = OtMedia.CommandCallback { code, value ->
            MediaControls.Command.entries.firstOrNull { it.code == code }?.let { onCommand(it, value) }
        }
        callback = cb
        // SMTC belongs to a window: the app's, once it's on screen.
        val window = if (Platform.isWindows()) {
            var handle: Pointer? = null
            for (i in 0 until 100) {
                handle = DesktopWindow.window?.takeIf { it.isDisplayable }?.let { w -> runCatching { Native.getWindowPointer(w) }.getOrNull() }
                if (handle != null) break
                Thread.sleep(100)
            }
            handle ?: return false
        } else null
        val r = l.otm_start(window, cb)
        check(r == 0) { "the controls didn't start (0x${Integer.toHexString(r)})" }
        lib = l
        return true
    }

    override fun track(track: MediaControls.Track?) {
        val l = lib ?: return
        if (track == null) l.otm_metadata("", "", "", null) else l.otm_metadata(track.title, track.artist, track.album, track.artUrl)
    }

    override fun state(state: Int, positionMs: Long, durationMs: Long) {
        lib?.otm_state(state, positionMs, durationMs)
    }

    override fun stop() {
        lib?.otm_stop()
        lib = null
    }
}
