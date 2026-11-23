package com.opentune.desktop

import java.nio.ShortBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bus: songs one after another with no gap, crossfades, resampling, the limiter, and the native output. */
class AudioBusTest {
    @After fun done() {
        AudioBus.reset()
        AudioBus.deviceFactory = null
    }

    /** [seconds] of a [hz] tone, encoded as [codec] in [format], at [rate]. */
    private fun tone(hz: Double, seconds: Double, rate: Int = 48_000, format: String = "webm", codec: Int = avcodec.AV_CODEC_ID_OPUS): java.io.File {
        val file = Files.createTempFile("tone", ".$format").toFile().apply { deleteOnExit() }
        FFmpegFrameRecorder(file, 2).apply {
            this.format = format
            audioCodec = codec
            sampleRate = rate
            if (codec == avcodec.AV_CODEC_ID_OPUS) audioBitrate = 128_000
            start()
            val n = (rate * seconds).toInt()
            val samples = ShortArray(n * 2)
            for (i in 0 until n) {
                val v = (sin(2 * PI * hz * i / rate) * 12_000).toInt().toShort()
                samples[i * 2] = v
                samples[i * 2 + 1] = v
            }
            recordSamples(rate, 2, ShortBuffer.wrap(samples))
            stop()
            release()
        }
        return file
    }

    private fun rms(s: FloatArray, from: Int, frames: Int): Double {
        var sum = 0.0
        for (f in from until from + frames) sum += s[f * 2].toDouble() * s[f * 2]
        return sqrt(sum / frames)
    }

    @Test fun theNextSongStartsOnTheSampleAfterTheLast() {
        val device = CaptureDevice.install()
        // FLAC, so the files hold exactly the samples put in, with no encoder padding to hear.
        val a = tone(440.0, 1.0, format = "flac", codec = avcodec.AV_CODEC_ID_FLAC)
        val b = tone(660.0, 1.0, format = "flac", codec = avcodec.AV_CODEC_ID_FLAC)
        val second = CountDownLatch(1)
        lateinit var next: FfmpegEngine
        next = FfmpegEngine(b.absolutePath, { 0 }, false, {}, {}, {}, { second.countDown() }, { second.countDown() }, queued = true)
        val first = FfmpegEngine(a.absolutePath, { 0 }, true, {}, {}, {}, {}, {})
        AudioBus.chain(first.voice, next.voice)
        assertTrue("both should play to the end", second.await(20, TimeUnit.SECONDS))
        val s = device.samples()
        // Two seconds exactly: not a sample of silence between them.
        assertEquals(96_000, s.size / 2)
        // Sound right across the join.
        for (at in listOf(47_000, 47_520, 48_000, 48_480, 49_000)) assertTrue("quiet at $at", rms(s, at, 480) > 0.15)
        first.dispose()
        next.dispose()
    }

    @Test fun aSongAtAnotherRateIsResampledToTheBus() {
        val device = CaptureDevice.install(rate = 48_000)
        val file = tone(1_000.0, 1.0, rate = 44_100, format = "flac", codec = avcodec.AV_CODEC_ID_FLAC)
        val end = CountDownLatch(1)
        val e = FfmpegEngine(file.absolutePath, { 0 }, true, {}, {}, {}, { end.countDown() }, { end.countDown() })
        assertTrue(end.await(20, TimeUnit.SECONDS))
        e.dispose()
        // One second at 44.1 kHz is one second at 48 kHz, give or take the resampler's edges.
        assertEquals(48_000.0, (device.samples().size / 2).toDouble(), 100.0)
    }

    @Test fun aCrossfadeHasNoDipAndEndsOnTheNewSong() {
        val device = CaptureDevice.install()
        // Held while it's set up: the capture device takes sound as fast as it comes.
        AudioBus.pause()
        val a = AudioBus.voice()
        val b = AudioBus.voice(queued = true)
        val steady = FloatArray(48_000 * 2) { 0.5f }
        a.write(steady, 0, 48_000); a.endOfInput()
        b.write(steady, 0, 48_000); b.endOfInput()
        AudioBus.crossfade(a, b, 9_600)
        AudioBus.resume()
        val start = System.currentTimeMillis()
        while (b.state != Voice.State.DONE && System.currentTimeMillis() - start < 10_000) Thread.sleep(20)
        val s = device.samples()
        // Equal power: the sum never dips below either alone during the fade, and never jumps above √2 of it.
        val heard = s.filterIndexed { i, _ -> i % 2 == 0 }
        val fadeStart = heard.indexOfFirst { abs(it - 0.5f) > 0.001f }
        assertTrue(fadeStart >= 0)
        for (i in fadeStart until fadeStart + 9_600) assertTrue("at $i: ${heard[i]}", heard[i] in 0.499f..0.7072f)
        // After it, only the new song.
        assertEquals(0.5f, heard.last(), 0.001f)
        assertEquals(Voice.State.DONE, a.state)
    }

    @Test fun theLimiterLeavesQuietSamplesAloneAndBendsLoudOnes() {
        val buf = floatArrayOf(0.2f, -0.9f, 0.95f, 1.4f, -3f)
        AudioBus.finish(buf, buf.size, 1f)
        assertEquals(0.2f, buf[0], 0f)
        assertEquals(-0.9f, buf[1], 0f)
        assertEquals(0.95f, buf[2], 0f)
        assertTrue(buf[3] in 0.95f..1f)
        assertTrue(buf[4] in -1f..-0.95f)
        val half = floatArrayOf(0.8f)
        AudioBus.finish(half, 1, 0.5f)
        assertEquals(0.4f, half[0], 0.0001f)
    }

    @Test fun aSeekDropsWhatWasWaiting() {
        CaptureDevice.install()
        AudioBus.pause()
        val v = AudioBus.voice()
        val ones = FloatArray(1_000 * 2) { 1f }
        assertTrue(v.write(ones, 0, 1_000))
        assertEquals(1_000, v.buffered)
        v.flush()
        assertEquals(0, v.buffered)
    }

    @Test fun aBitPerfectFlacPlaysAtItsOwnFormatOnTheNativeOutput() {
        org.junit.Assume.assumeTrue("otaudio wasn't built here", NativeAudio.available)
        val file = tone(1_000.0, 3.0, rate = 44_100, format = "flac", codec = avcodec.AV_CODEC_ID_FLAC)
        val out = NativePcmOut("", exclusive = true)
        val started = CountDownLatch(1)
        var problem: String? = null
        val e = FlacEngine(
            source = { file.inputStream() }, startAtMs = { 0 }, autoplay = true,
            onStarted = { started.countDown() }, onEnd = {}, onError = { problem = it; started.countDown() }, out = out,
        )
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertEquals(null, problem)
            // The file's own rate and depth, untouched; the silent backend has no exclusive mode, so shared.
            assertEquals(44_100f, e.output!!.sampleRate)
            assertEquals(16, e.output!!.sampleSizeInBits)
            assertEquals(false, e.exclusive)
            Thread.sleep(1_000)
            // Playing in real time: about a second in.
            assertTrue("at ${e.positionMs()} ms", e.positionMs() in 600..1_600)
        } finally {
            e.dispose()
            e.awaitClosed(2_000)
        }
    }

    @Test fun twentyFourBitTriesExclusiveIn32BitWordsBeforeShared() {
        val f = NativeAudio.Format.entries
        assertEquals(listOf(f[2] to true, f[3] to true, f[2] to false, f[3] to false, f[1] to false), outputTries(24, exclusive = true))
        assertEquals(listOf(NativeAudio.Format.S16 to true, NativeAudio.Format.S16 to false), outputTries(16, exclusive = true))
        assertEquals(listOf(NativeAudio.Format.S32 to false), outputTries(32, exclusive = false))
        assertEquals(null, outputTries(8, exclusive = true))
    }

    @Test fun widening24BitKeepsEverySampleBitForBit() {
        // -1, the largest and the smallest 24-bit samples, little-endian.
        val packed = byteArrayOf(-1, -1, -1, -1, -1, 0x7f, 0, 0, -128)
        val wide = ByteArray(12)
        widen24(packed, 0, 3, wide)
        val words = java.nio.ByteBuffer.wrap(wide).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        assertEquals(-1 shl 8, words[0])
        assertEquals(8_388_607 shl 8, words[1])
        assertEquals(-8_388_608 shl 8, words[2])
    }

    @Test fun theNativeOutputPlaysInRealTime() {
        // miniaudio's silent backend in the test JVM (-Dopentune.audio=null): a clock, no sound card.
        org.junit.Assume.assumeTrue("otaudio wasn't built here", NativeAudio.available)
        assertEquals("Null", NativeAudio.backend)
        val out = NativeAudio.open("", NativeAudio.Format.F32, 48_000, 2, exclusive = false, ringMs = 200)
        try {
            assertEquals(48_000, out.rate)
            assertEquals(2, out.channels)
            val second = FloatArray(48_000 * 2) { (it % 100) / 100f }
            out.start()
            val t0 = System.nanoTime()
            val written = out.writeAll(48_000, stop = { false }) { from, n -> out.offer(second, from * 2, n) }
            assertEquals(48_000, written)
            while (out.queued > 0 && System.nanoTime() - t0 < 5_000_000_000L) Thread.sleep(5)
            val took = (System.nanoTime() - t0) / 1_000_000
            // A second of sound takes about a second to play, not no time at all.
            assertTrue("took $took ms", took in 700..2_500)
            assertTrue("played ${out.played}", out.played in 47_000..48_000)
        } finally {
            out.stop()
            out.close()
        }
    }
}
