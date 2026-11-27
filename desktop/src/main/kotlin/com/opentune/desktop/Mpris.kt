package com.opentune.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/** org.mpris.MediaPlayer2, as the MPRIS specification has it. */
@DBusInterfaceName("org.mpris.MediaPlayer2")
@Suppress("FunctionName")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

/** org.mpris.MediaPlayer2.Player, as the MPRIS specification has it. */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
@Suppress("FunctionName")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    /** Moves by [offset] microseconds. */
    fun Seek(offset: Long)
    /** Moves to [position] microseconds into [trackId], if it's still the one playing. */
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    /** Sent when the position jumps (a seek), in microseconds. */
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

/**
 * MPRIS on Linux: the player on the session bus as org.mpris.MediaPlayer2.opentune, which the
 * desktop's panel and media keys (GNOME, KDE and the rest) and playerctl all find and control.
 * Its properties are read live from the player; changes are announced with PropertiesChanged,
 * and seeks with Seeked.
 */
internal object Mpris : MediaControls.Backend {
    override val name = "MPRIS"
    const val BUS_NAME = "org.mpris.MediaPlayer2.opentune"
    const val PATH = "/org/mpris/MediaPlayer2"
    private const val ROOT = "org.mpris.MediaPlayer2"
    private const val PLAYER = "org.mpris.MediaPlayer2.Player"

    private var conn: DBusConnection? = null
    private var onCommand: (MediaControls.Command, Long) -> Unit = { _, _ -> }
    @Volatile private var track: MediaControls.Track? = null
    @Volatile private var state = 0
    @Volatile private var positionMs = 0L
    @Volatile private var positionAt = 0L
    @Volatile private var durationMs = 0L
    /** Called for Raise: the window brought to the front. */
    @Volatile var onRaise: () -> Unit = { DesktopWindow.bringToFront() }

    override fun start(onCommand: (MediaControls.Command, Long) -> Unit): Boolean {
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return false
        this.onCommand = onCommand
        val c = DBusConnectionBuilder.forSessionBus().withShared(false).build()
        c.requestBusName(BUS_NAME)
        c.exportObject(PATH, Exported)
        conn = c
        return true
    }

    override fun track(track: MediaControls.Track?) {
        this.track = track
        changed(PLAYER, "Metadata" to metadata(), "CanPlay" to Variant(track != null), "CanPause" to Variant(track != null), "CanSeek" to Variant(track != null))
    }

    override fun state(state: Int, positionMs: Long, durationMs: Long) {
        val before = this.state
        val expected = estimate()
        this.state = state
        this.positionMs = positionMs
        this.positionAt = System.currentTimeMillis()
        val lengthChanged = durationMs != this.durationMs
        this.durationMs = durationMs
        if (state != before) changed(PLAYER, "PlaybackStatus" to Variant(status()))
        if (lengthChanged) changed(PLAYER, "Metadata" to metadata())
        if (kotlin.math.abs(positionMs - expected) > 1_500) conn?.sendMessage(MprisPlayer.Seeked(PATH, positionMs * 1000))
    }

    override fun stop() {
        runCatching { conn?.releaseBusName(BUS_NAME) }
        runCatching { conn?.close() }
        conn = null
    }

    /** Where the song is now: the last position given, moved on by the clock while it plays. */
    private fun estimate(): Long = if (state == 2) positionMs + (System.currentTimeMillis() - positionAt) else positionMs

    private fun status() = when (state) { 2 -> "Playing"; 1 -> "Paused"; else -> "Stopped" }

    /** A track id is an object path: letters, digits and underscores only. */
    internal fun trackPath(id: String) = DBusPath("/com/opentune/track/t" + id.replace(Regex("[^A-Za-z0-9_]"), "_"))

    internal fun metadata(): Variant<*> {
        val t = track
        val map = LinkedHashMap<String, Variant<*>>()
        if (t != null) {
            map["mpris:trackid"] = Variant(trackPath(t.id))
            if (durationMs > 0) map["mpris:length"] = Variant(durationMs * 1000)
            t.artUrl?.let { map["mpris:artUrl"] = Variant(it) }
            map["xesam:title"] = Variant(t.title)
            map["xesam:artist"] = Variant(arrayOf(t.artist))
            if (t.album.isNotBlank()) map["xesam:album"] = Variant(t.album)
        } else {
            map["mpris:trackid"] = Variant(DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack"))
        }
        return Variant(map, "a{sv}")
    }

    private fun properties(iface: String): Map<String, Variant<*>> = when (iface) {
        ROOT -> mapOf(
            "CanQuit" to Variant(false),
            "CanRaise" to Variant(true),
            "HasTrackList" to Variant(false),
            "Identity" to Variant("NepTune"),
            "DesktopEntry" to Variant("opentune"),
            "SupportedUriSchemes" to Variant(arrayOf("opentune")),
            "SupportedMimeTypes" to Variant(arrayOf<String>(), "as"),
        )
        PLAYER -> {
            val has = track != null
            mapOf(
                "PlaybackStatus" to Variant(status()),
                "LoopStatus" to Variant(when (Player.repeat.value) { Player.Repeat.ONE -> "Track"; Player.Repeat.ALL -> "Playlist"; else -> "None" }),
                "Rate" to Variant(1.0),
                "Shuffle" to Variant(Player.shuffled.value),
                "Metadata" to metadata(),
                "Volume" to Variant(Player.volume.value),
                "Position" to Variant(estimate() * 1000),
                "MinimumRate" to Variant(1.0),
                "MaximumRate" to Variant(1.0),
                "CanGoNext" to Variant(has),
                "CanGoPrevious" to Variant(has),
                "CanPlay" to Variant(has),
                "CanPause" to Variant(has),
                "CanSeek" to Variant(has),
                "CanControl" to Variant(true),
            )
        }
        else -> emptyMap()
    }

    private fun changed(iface: String, vararg values: Pair<String, Variant<*>>) {
        val c = conn ?: return
        runCatching { c.sendMessage(Properties.PropertiesChanged(PATH, iface, mapOf(*values), emptyList())) }
            .onFailure { System.err.println("[mpris] ${it.message}") }
    }

    /** The object on the bus: both MPRIS interfaces, and their properties. */
    private object Exported : MprisRoot, MprisPlayer, Properties {
        override fun getObjectPath() = PATH
        override fun Raise() = onRaise()
        override fun Quit() = Unit
        override fun Next() = onCommand(MediaControls.Command.NEXT, 0)
        override fun Previous() = onCommand(MediaControls.Command.PREVIOUS, 0)
        override fun Pause() = onCommand(MediaControls.Command.PAUSE, 0)
        override fun PlayPause() = onCommand(MediaControls.Command.TOGGLE, 0)
        override fun Stop() = onCommand(MediaControls.Command.STOP, 0)
        override fun Play() = onCommand(MediaControls.Command.PLAY, 0)
        override fun Seek(offset: Long) = onCommand(MediaControls.Command.SEEK, (estimate() + offset / 1000).coerceAtLeast(0))
        override fun SetPosition(trackId: DBusPath, position: Long) {
            val t = track ?: return
            // The spec: ignored unless it's for the track playing, and within it.
            if (trackId.path != trackPath(t.id).path || position < 0 || (durationMs > 0 && position / 1000 > durationMs)) return
            onCommand(MediaControls.Command.SEEK, position / 1000)
        }
        override fun OpenUri(uri: String) { DeepLinks.open(uri) }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(iface: String, prop: String): A = properties(iface)[prop] as A

        override fun <A : Any?> Set(iface: String, prop: String, value: A) {
            // Volume is the only one to set; the rest are read-only or not offered.
            if (iface == PLAYER && prop == "Volume") {
                val v = ((value as? Variant<*>)?.value ?: value) as? Double ?: return
                Player.setVolume(v)
            }
        }

        override fun GetAll(iface: String): Map<String, Variant<*>> = properties(iface)
    }
}
