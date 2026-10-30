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
        val first = Pages.home()
        assertTrue("no home", first.shelves.isNotEmpty())
        val more = first.next?.let { Pages.homeMore(it).shelves }.orEmpty()
        val moods = runCatching { Pages.moods() }
        println("LIVE moods: ${moods.getOrNull()?.size} ${moods.exceptionOrNull()} ${moods.getOrNull()?.take(5)?.map { it.title }}")
        println("LIVE extras: ${Pages.moreForHome().map { it.title }}")
        println("LIVE home: ${first.shelves.size} shelves, first ${first.shelves.first().title}; next page ${more.size} more")

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

    /** YouTube's Opus stream, through the relay and FFmpeg, for a few seconds of real sound. */
    @Test fun opusPlaysThroughFfmpeg() {
        assumeTrue(System.getProperty("opentune.live") == "true")
        val id = System.getProperty("opentune.video") ?: "dQw4w9WgXcQ"
        val stream = runBlocking { Streams.resolve(id, kind = Streams.Kind.OPUS) }
        println("LIVE opus: ${stream.mimeType} ${stream.kbps}kbps")
        var played = 0L
        val enough = java.util.concurrent.CountDownLatch(1)
        var problem: String? = null
        var length = 0L
        val out = object : PcmOut {
            override fun open(format: javax.sound.sampled.AudioFormat) = format
            override fun write(bytes: ByteArray, off: Int, len: Int): Int {
                played += len / 4
                if (played > 48_000 * 3) enough.countDown()
                return len
            }
            override fun start() = Unit
            override fun stop() = Unit
            override fun flush() = Unit
            override fun drain() = Unit
            override fun close() = Unit
            override val framePosition: Long get() = played
            override fun setGainDb(db: Float) = true
        }
        val engine = FfmpegEngine(
            Relay.addressOf(id, Streams.Kind.OPUS), startAtMs = { 0 }, autoplay = true,
            onReady = { length = it }, onStarted = {}, onPlaying = {}, onEnd = {}, onError = { problem = it; enough.countDown() }, out = out,
        )
        enough.await(60, java.util.concurrent.TimeUnit.SECONDS)
        engine.dispose()
        println("LIVE ffmpeg: ${played / 48} ms decoded of $length ms, problem $problem")
        assertEquals(null, problem)
        assertTrue(played > 48_000 * 3)
        assertTrue(length > 60_000)
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
