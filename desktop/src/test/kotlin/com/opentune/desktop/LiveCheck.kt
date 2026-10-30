package com.opentune.desktop

import com.opentune.data.Http
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Home, a stream and the relay, against YouTube itself.
 * Off unless asked for, since it needs the network:
 * ./gradlew :desktop:test -Dopentune.live=true
 */
class LiveCheck {
    @Test fun aSongStreamsThroughTheRelay() = runBlocking {
        assumeTrue(System.getProperty("opentune.live") == "true")
        val home = Pages.home()
        assertTrue("no home", home.isNotEmpty())
        println("LIVE home: ${home.size} shelves, first ${home.first().title}")

        // YouTube Music gives a server far less than a home connection gets, so the
        // stream is checked on a well-known public video rather than a searched one.
        val song = com.opentune.data.model.Song(System.getProperty("opentune.video") ?: "dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null)
        val stream = Streams.resolve(song.videoId)
        println("LIVE stream: ${stream.mimeType} ${stream.kbps}kbps")
        val piece = Request.Builder().url(Relay.addressOf(song.videoId)).header("Range", "bytes=1000-4999").build()
        Http.client.newCall(piece).execute().use { r ->
            assertEquals(206, r.code)
            assertEquals(4000, r.body!!.bytes().size)
            println("LIVE relay: ${r.code} ${r.header("Content-Range")} ${r.header("Content-Type")}")
        }
    }

    /** JioSaavn's verified 320 kbps file for a well-known song. */
    @Test fun jioSaavnFindsAKnownSong() = runBlocking {
        assumeTrue(System.getProperty("opentune.live") == "true")
        val notes = mutableListOf<String>()
        val url = JioSaavn.find(JioSaavn.Wanted("Kesariya", "Arijit Singh", "Brahmastra", 268.0), notes::add)
        println("LIVE JioSaavn: $url ${notes.joinToString()}")
        assertTrue(notes.joinToString(), url != null)
    }

    /** JavaFX opens the song through the relay and plays it; needs a display (xvfb-run on a server). */
    @Test fun javaFxPlaysTheRelayedSong() {
        assumeTrue(System.getProperty("opentune.live") == "true")
        val ready = java.util.concurrent.CountDownLatch(1)
        javafx.application.Platform.startup { ready.countDown() }
        ready.await()
        val opened = java.util.concurrent.CountDownLatch(1)
        var error: String? = null
        lateinit var player: javafx.scene.media.MediaPlayer
        javafx.application.Platform.runLater {
            player = javafx.scene.media.MediaPlayer(javafx.scene.media.Media(Relay.addressOf("dQw4w9WgXcQ")))
            player.volume = 0.0
            player.setOnReady { opened.countDown() }
            player.setOnError { error = player.error?.message; opened.countDown() }
            player.play()
        }
        assertTrue("never opened", opened.await(60, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(null, error)
        Thread.sleep(3_000)
        val at = player.currentTime.toSeconds()
        println("LIVE javafx: length ${player.totalDuration.toSeconds()}s, played to ${at}s")
        assertTrue("didn't advance", at > 1.0)
        javafx.application.Platform.runLater { player.dispose() }
    }

    /** The phone's lyrics sources, from the desktop: synced lyrics for a well-known song. */
    @Test fun lyricsAreFound() = runBlocking {
        assumeTrue(System.getProperty("opentune.live") == "true")
        com.opentune.data.settings.AppSettings.init(android.content.Context(kotlin.io.path.createTempDirectory("opentune").toFile()))
        val found = com.opentune.data.lyrics.LyricsRepository.lyricsFor("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213_000)
        println("LIVE lyrics: ${found?.source} ${found?.javaClass?.simpleName} ${(found as? com.opentune.data.lyrics.Lyrics.Synced)?.lines?.size} lines")
        assertTrue("no synced lyrics", found is com.opentune.data.lyrics.Lyrics.Synced)
    }
}
