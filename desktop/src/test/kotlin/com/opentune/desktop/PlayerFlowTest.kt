package com.opentune.desktop

import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import java.nio.ShortBuffer
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import javax.sound.sampled.AudioFormat
import kotlin.math.sqrt
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The player from one song to the next, on a capture device that plays in real time: the
 * next song made ready ahead and started on the sample after the last (gapless), faded in
 * over the end (crossfade), and taken up at once on a skip.
 */
class PlayerFlowTest {
    private val files = mutableMapOf<String, String>()

    @Before fun setUp() {
        AppSettings.init(appContext)
        Library.useFile(Files.createTempDirectory("flow").resolve("desktop.json").toFile())
        Player.sourceFor = { id -> files[id] }
    }

    @After fun tearDown() {
        Player.pause()
        Player.sourceFor = null
        AppSettings.updatePlayback { it.copy(crossfadeSeconds = 0) }
        AudioBus.reset()
        AudioBus.deviceFactory = null
    }

    /** A song of [seconds] of a [hz] tone, as FLAC so it holds exactly the samples put in. */
    private fun song(id: String, hz: Double, seconds: Double): Song {
        val file = Files.createTempFile(id, ".flac").toFile().apply { deleteOnExit() }
        FFmpegFrameRecorder(file, 2).apply {
            format = "flac"
            audioCodec = avcodec.AV_CODEC_ID_FLAC
            sampleRate = 48_000
            start()
            val n = (48_000 * seconds).toInt()
            val samples = ShortArray(n * 2)
            for (i in 0 until n) {
                val v = (sin(2 * PI * hz * i / 48_000) * 12_000).toInt().toShort()
                samples[i * 2] = v
                samples[i * 2 + 1] = v
            }
            recordSamples(48_000, 2, ShortBuffer.wrap(samples))
            stop()
            release()
        }
        files[id] = file.absolutePath
        return Song(id, "Tone $hz", "Test", null)
    }

    private fun rms(s: FloatArray, from: Int, frames: Int): Double {
        var sum = 0.0
        for (f in from until from + frames) sum += s[f * 2].toDouble() * s[f * 2]
        return sqrt(sum / frames)
    }

    /** Each tenth of a second: its loudness and its pitch from zero crossings, to see what played where. */
    private fun profile(s: FloatArray): String = (0 until s.size / 2 / 4_800).joinToString(" ") { k ->
        var crossings = 0
        for (f in k * 4_800 + 1 until (k + 1) * 4_800) if ((s[f * 2] >= 0) != (s[(f - 1) * 2] >= 0)) crossings++
        "${"%.2f".format(rms(s, k * 4_800, 4_800))}/${crossings * 5}Hz"
    }

    private fun waitFor(ms: Long, what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("never: $what")
            Thread.sleep(20)
        }
    }

    @Test fun oneSongRunsIntoTheNextWithNoGap() {
        val device = CaptureDevice.install(paced = true)
        Player.playAll(listOf(song("flow-a", 440.0, 2.0), song("flow-b", 660.0, 2.0)), 0)
        waitFor(6_000, "the second song") { Player.index.value == 1 }
        waitFor(6_000, "the end") { device.frames >= 4 * 48_000 }
        Thread.sleep(300)
        val s = device.samples()
        // Four seconds, and sound all through the join at two.
        assertEquals(profile(s), 4 * 48_000.0, (s.size / 2).toDouble(), 480.0)
        for (at in 94_000 until 98_000 step 480) assertTrue("quiet at $at", rms(s, at, 480) > 0.15)
    }

    @Test fun aCrossfadeBringsTheNextSongInOverTheEnd() {
        AppSettings.updatePlayback { it.copy(crossfadeSeconds = 1) }
        val device = CaptureDevice.install(paced = true)
        Player.playAll(listOf(song("fade-a", 440.0, 4.0), song("fade-b", 660.0, 4.0)), 0)
        // The next song shows as soon as the fade starts, about a second before the first ends.
        waitFor(6_000, "the second song") { Player.index.value == 1 }
        val at = device.frames
        assertTrue("switched at ${at / 48} ms", at in (2_600L * 48)..(3_300L * 48))
        waitFor(8_000, "the end") { device.frames >= 7 * 48_000 }
        Thread.sleep(500)
        val s = device.samples()
        // Seven seconds: the second overlapping the first's last second.
        assertEquals(7 * 48_000.0, (s.size / 2).toDouble(), 4_800.0)
        // Never a dip through the fade.
        for (f in 140_000 until 190_000 step 480) assertTrue("quiet at $f", rms(s, f, 480) > 0.15)
    }

    @Test fun skippingToTheSongMadeReadyStartsIt() {
        val device = CaptureDevice.install(paced = true)
        // Short songs, so the next one is made ready at once.
        Player.playAll(listOf(song("skip-a", 440.0, 5.0), song("skip-b", 660.0, 5.0)), 0)
        waitFor(3_000, "playing") { device.frames > 24_000 }
        Thread.sleep(400)
        Player.next()
        assertEquals(1, Player.index.value)
        val before = device.frames
        waitFor(2_000, "the next song heard") { device.frames > before + 9_600 }
        assertTrue(Player.playing.value)
        assertTrue(Player.positionMs() < 1_500)
    }

    /** A bit-perfect output that plays in real time and takes [closing] ms to give the device back. */
    private class SlowToClose(private val closing: Long) : PcmOut {
        @Volatile var bytes = 0L
        @Volatile var closedAt = 0L
        override fun open(format: AudioFormat) = format
        override fun write(bytes: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(len / 192L)
            this.bytes += len
            return len
        }
        override fun start() {}
        override fun stop() {}
        override fun flush() {}
        override fun drain() {}
        override fun close() { Thread.sleep(closing); closedAt = System.nanoTime() }
        override val framePosition: Long get() = bytes / 4
        override fun setGainDb(db: Float) = true
    }

    @Test fun skippingAwayFromABitPerfectSongDoesNotHoldUpTheWindow() {
        val device = CaptureDevice.install(paced = true)
        Player.playAll(listOf(song("bp-a", 440.0, 5.0), song("bp-b", 660.0, 5.0)), 0)
        waitFor(3_000, "playing") { device.frames > 9_600 }
        // A bit-perfect FLAC takes over; its device takes most of a second to close.
        val out = SlowToClose(700)
        val flac = FlacEngine(source = { java.io.File(files.getValue("bp-a")).inputStream() }, startAtMs = { 0 }, autoplay = true,
            onStarted = {}, onEnd = {}, onError = {}, out = out)
        waitFor(3_000, "the FLAC playing") { out.bytes > 0 }
        Player.takeOver(flac)
        var openedAt = 0L
        AudioBus.deviceFactory = { openedAt = System.nanoTime(); device }
        val before = device.frames
        val began = System.nanoTime()
        Player.next()
        val took = (System.nanoTime() - began) / 1_000_000
        assertTrue("the skip took $took ms", took < 300)
        assertEquals(1, Player.index.value)
        // The next song plays on the bus, opened only once the FLAC gave the device back.
        waitFor(4_000, "the next song heard") { device.frames > before + 9_600 }
        assertTrue("the bus opened before the FLAC's device closed", out.closedAt in 1..openedAt)
    }
}
