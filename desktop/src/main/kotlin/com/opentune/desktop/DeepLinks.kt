package com.opentune.desktop

import com.opentune.data.together.RoomCode
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.net.URI
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * opentune:// links: a song, album, artist or playlist, or a Listen Together room, opened in
 * the app from a browser, a chat or another app. Only these forms are taken, each id checked
 * against what that kind of id looks like; anything else is dropped.
 *
 *     opentune://song/dQw4w9WgXcQ
 *     opentune://album/MPREb_…        opentune://artist/UC…        opentune://playlist/PL…
 *     opentune://together/K7QXM2PA9DTE  (and the website's invite link, as the phone reads it)
 */
internal object DeepLinks {
    sealed interface Target {
        data class Song(val videoId: String) : Target
        data class Album(val browseId: String) : Target
        data class Artist(val browseId: String) : Target
        data class Playlist(val browseId: String) : Target
        data class Room(val code: RoomCode) : Target
    }

    private val VIDEO = Regex("[A-Za-z0-9_-]{11}")
    private val ALBUM = Regex("MPREb_[A-Za-z0-9_-]{4,60}")
    private val ARTIST = Regex("UC[A-Za-z0-9_-]{22}")
    private val PLAYLIST = Regex("(VL)?(PL|OLAK5uy_|RD|LM|OL|UU|FL)[A-Za-z0-9_-]{0,80}")

    /** What [link] opens, or null when it isn't one of the forms above. */
    fun parse(link: String?): Target? {
        val text = link?.trim()?.takeIf { it.length in 1..512 } ?: return null
        RoomCode.parse(text)?.takeIf { text.contains("://") }?.let { return Target.Room(it) }
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("opentune", ignoreCase = true)) return null
        val id = uri.path.orEmpty().trim('/').takeIf { '/' !in it } ?: return null
        return when (uri.host?.lowercase()) {
            "song" -> id.takeIf(VIDEO::matches)?.let(Target::Song)
            "album" -> id.takeIf(ALBUM::matches)?.let(Target::Album)
            "artist" -> id.takeIf(ARTIST::matches)?.let(Target::Artist)
            "playlist" -> id.takeIf(PLAYLIST::matches)?.let { Target.Playlist(if (it.startsWith("VL")) it else "VL$it") }
            else -> null
        }
    }

    private val queue = Channel<Target>(capacity = 16)

    /** Links to open, in order; taken by the window (DesktopApp) once it's up, so none is lost before. */
    val incoming: Flow<Target> = queue.receiveAsFlow()

    /** Opens [link] in the app, bringing the window to the front; false when it isn't a link the app opens. */
    fun open(link: String): Boolean {
        val target = parse(link) ?: return false
        queue.trySend(target)
        DesktopWindow.bringToFront()
        return true
    }

    /**
     * Makes this app the one that opens opentune:// links, for this user only, pointing at
     * wherever it runs from now (an installer's copy, the portable zip, an AppImage). On macOS
     * the app's Info.plist claims the scheme and the system hands links over itself.
     */
    fun register() {
        val app = System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }
            ?: System.getenv("APPIMAGE")?.takeIf { File(it).exists() }
            ?: return // Run from a build, not an install: nothing to point links at.
        runCatching {
            when {
                Platform.isWindows() -> registerWindows(app)
                Platform.isLinux() -> registerLinux(app)
            }
        }.onFailure { System.err.println("[links] not registered: ${it.message}") }
    }

    private fun registerWindows(exe: String) {
        val root = "Software\\Classes\\opentune"
        val command = "\"$exe\" \"%1\""
        if (runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, "$root\\shell\\open\\command", "") }.getOrNull() == command) return
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, root)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, root, "", "URL:OpenTune")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, root, "URL Protocol", "")
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$root\\DefaultIcon")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$root\\DefaultIcon", "", "\"$exe\",0")
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$root\\shell\\open\\command")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$root\\shell\\open\\command", "", command)
    }

    /** The .desktop entry Linux uses to find who opens opentune:// links. */
    internal fun desktopEntry(exec: String) = """
        [Desktop Entry]
        Type=Application
        Name=OpenTune
        Comment=Opens opentune:// links in OpenTune
        Exec="${exec.replace("\"", "\\\"")}" %u
        Terminal=false
        NoDisplay=true
        MimeType=x-scheme-handler/opentune;
    """.trimIndent() + "\n"

    private fun registerLinux(app: String) {
        val home = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        val dir = File(home, "applications").apply { mkdirs() }
        val file = File(dir, "opentune-links.desktop")
        val entry = desktopEntry(app)
        if (file.exists() && file.readText() == entry) return
        file.writeText(entry)
        // Who opens the scheme, as the desktop's own tools record it; skipped where they aren't installed.
        runCatching { ProcessBuilder("xdg-mime", "default", file.name, "x-scheme-handler/opentune").redirectErrorStream(true).start().waitFor() }
        runCatching { ProcessBuilder("update-desktop-database", dir.absolutePath).redirectErrorStream(true).start().waitFor() }
    }

    /** On macOS, links arrive as Apple Events, through AWT; set before the window, so none is missed. */
    fun listenOnMac() {
        if (!Platform.isMac()) return
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { open(it.uri.toString()) }
        }
    }
}
