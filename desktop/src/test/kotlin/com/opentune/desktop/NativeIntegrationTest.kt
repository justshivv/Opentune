package com.opentune.desktop

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** opentune:// links, one app at a time, and MPRIS on a real session bus. */
class NativeIntegrationTest {
    @Test fun onlyTheLinkFormsTheAppKnowsAreOpened() {
        assertEquals(DeepLinks.Target.Song("dQw4w9WgXcQ"), DeepLinks.parse("opentune://song/dQw4w9WgXcQ"))
        assertEquals(DeepLinks.Target.Album("MPREb_4pL8gzRtw1p"), DeepLinks.parse("opentune://album/MPREb_4pL8gzRtw1p"))
        assertEquals(DeepLinks.Target.Artist("UCuAXFkgsw1L7xaCfnd5JJOw"), DeepLinks.parse("OPENTUNE://artist/UCuAXFkgsw1L7xaCfnd5JJOw/"))
        // A playlist id is shown to the page as its browse id.
        assertEquals(DeepLinks.Target.Playlist("VLPLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"), DeepLinks.parse("opentune://playlist/PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
        assertEquals(DeepLinks.Target.Playlist("VLOLAK5uy_abc"), DeepLinks.parse("opentune://playlist/VLOLAK5uy_abc"))
        // A room, as the phone's invites write it, and the website's invite link.
        assertTrue(DeepLinks.parse("opentune://together/K7QX-M2PA-9DTE") is DeepLinks.Target.Room)
        assertTrue(DeepLinks.parse("https://justshivv.github.io/Opentune/together#code=K7QXM2PA9DTE") is DeepLinks.Target.Room)
        // Everything else: other schemes, malformed ids, paths that try for more.
        for (bad in listOf(
            "opentune://song/short", "opentune://song/dQw4w9WgXcQ/extra", "opentune://album/UCabc", "opentune://artist/MPREb_x",
            "opentune://settings/reset", "https://music.youtube.com/watch?v=dQw4w9WgXcQ", "file:///etc/passwd",
            "opentune://song/../../x", "K7QXM2PA9DTE", "", "opentune://playlist/DROP;TABLE",
        )) assertNull(bad, DeepLinks.parse(bad))
    }

    @Test fun theLinuxEntryOpensTheSchemeWithTheAppItRunsFrom() {
        val entry = DeepLinks.desktopEntry("/opt/opentune/bin/OpenTune")
        assertTrue(entry.contains("Exec=\"/opt/opentune/bin/OpenTune\" %u"))
        assertTrue(entry.contains("MimeType=x-scheme-handler/opentune;"))
        assertTrue(entry.contains("NoDisplay=true"))
    }

    @Test fun aSecondStartHandsItsLinkToTheFirst() {
        val dir = Files.createTempDirectory("instance").toFile()
        val got = CopyOnWriteArrayList<List<String>>()
        val first = SingleInstance(dir) { got += it }
        assertTrue(first.claim())
        try {
            val second = SingleInstance(dir) {}
            assertFalse("the lock is held", second.claim())
            assertTrue(second.handOff(listOf("opentune://song/dQw4w9WgXcQ")))
            val until = System.currentTimeMillis() + 3_000
            while (got.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(listOf(listOf("opentune://song/dQw4w9WgXcQ")), got.toList())
            // Readable by this user alone, where the system has POSIX permissions.
            val info = java.io.File(dir, "instance.json")
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(info.toPath())))
            }
            // Without the token, nothing is taken.
            val port = org.json.JSONObject(info.readText()).getInt("port")
            java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port).use { s ->
                s.getOutputStream().write("{\"token\":\"wrong\",\"args\":[\"opentune://song/aaaaaaaaaaa\"]}\n".toByteArray())
                s.soTimeout = 2_000
                assertNull(runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull())
            }
            Thread.sleep(200)
            assertEquals(1, got.size)
        } finally {
            first.release()
        }
        // Once the first has gone, the next start is the first.
        val next = SingleInstance(dir) {}
        assertTrue(next.claim())
        next.release()
    }

    /** Asks the bus as a desktop panel would, with GLib's gdbus. */
    private fun gdbus(vararg args: String): String {
        val p = ProcessBuilder(listOf("gdbus", "call", "--session", "--dest", Mpris.BUS_NAME, "--object-path", Mpris.PATH) + args)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        return out.trim()
    }

    @Test fun mprisShowsTheSongAndTakesCommands() {
        // Run inside a session bus: dbus-run-session -- ./gradlew :desktop:test …
        org.junit.Assume.assumeTrue(!System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank())
        org.junit.Assume.assumeTrue(runCatching { ProcessBuilder("gdbus", "help").start().waitFor() }.isSuccess)
        val commands = CopyOnWriteArrayList<Pair<MediaControls.Command, Long>>()
        assertTrue(Mpris.start { c, v -> commands += c to v })
        try {
            Mpris.track(MediaControls.Track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", "https://lh3.googleusercontent.com/x=w544-h544"))
            Mpris.state(2, 30_000, 213_000)
            val get = "org.freedesktop.DBus.Properties.Get"
            assertEquals("(<'Playing'>,)", gdbus("--method", get, "org.mpris.MediaPlayer2.Player", "PlaybackStatus"))
            val meta = gdbus("--method", get, "org.mpris.MediaPlayer2.Player", "Metadata")
            assertTrue(meta, meta.contains("'xesam:title': <'Never Gonna Give You Up'>"))
            assertTrue(meta, meta.contains("'xesam:artist': <['Rick Astley']>"))
            assertTrue(meta, meta.contains("'mpris:length': <int64 213000000>"))
            assertTrue(meta, meta.contains("'mpris:trackid': <objectpath '/com/opentune/track/tdQw4w9WgXcQ'>"))
            assertTrue(gdbus("--method", get, "org.mpris.MediaPlayer2", "Identity").contains("OpenTune"))
            // The panel's buttons, and a seek to a minute in.
            gdbus("--method", "org.mpris.MediaPlayer2.Player.PlayPause")
            gdbus("--method", "org.mpris.MediaPlayer2.Player.Next")
            gdbus("--method", "org.mpris.MediaPlayer2.Player.SetPosition", "/com/opentune/track/tdQw4w9WgXcQ", "60000000")
            // For another track it's ignored, as the spec says.
            gdbus("--method", "org.mpris.MediaPlayer2.Player.SetPosition", "/com/opentune/track/tother", "60000000")
            val until = System.currentTimeMillis() + 3_000
            while (commands.size < 3 && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(
                listOf(MediaControls.Command.TOGGLE to 0L, MediaControls.Command.NEXT to 0L, MediaControls.Command.SEEK to 60_000L),
                commands.toList(),
            )
            // Paused shows as paused.
            Mpris.state(1, 31_000, 213_000)
            assertEquals("(<'Paused'>,)", gdbus("--method", get, "org.mpris.MediaPlayer2.Player", "PlaybackStatus"))
        } finally {
            Mpris.stop()
        }
    }
}
