package com.opentune.desktop

import java.nio.ShortBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.utils.JavaScript

/** FFmpeg decoding Opus, and GraalJS running YouTube-style player code. */
class MediaTest {
    @Test fun ffmpegPlaysAnOpusFileToTheEnd() {
        assertTrue("FFmpeg should load", Ffmpeg.available)
        // Two seconds of a 440 Hz tone, encoded to Opus in WebM, YouTube's best audio format.
        val file = Files.createTempFile("tone", ".webm").toFile().apply { deleteOnExit() }
        FFmpegFrameRecorder(file, 2).apply {
            format = "webm"
            audioCodec = avcodec.AV_CODEC_ID_OPUS
            sampleRate = 48_000
            audioBitrate = 128_000
            start()
            val samples = ShortArray(48_000 * 2 * 2)
            for (i in 0 until 48_000 * 2) {
                val v = (Math.sin(2 * Math.PI * 440 * i / 48_000.0) * 12_000).toInt().toShort()
                samples[i * 2] = v
                samples[i * 2 + 1] = v
            }
            recordSamples(48_000, 2, ShortBuffer.wrap(samples))
            stop()
            release()
        }
        val device = CaptureDevice.install()
        val ended = CountDownLatch(1)
        var length = 0L
        var problem: String? = null
        val engine = FfmpegEngine(
            file.absolutePath, startAtMs = { 0 }, autoplay = true,
            onReady = { length = it }, onStarted = {}, onPlaying = {},
            onEnd = { ended.countDown() }, onError = { problem = it; ended.countDown() },
        )
        assertTrue("it should reach the end", ended.await(20, TimeUnit.SECONDS))
        engine.dispose()
        AudioBus.reset()
        assertEquals(null, problem)
        // The length comes from the file's header, which FFmpeg's own recorder only estimates; YouTube's is exact.
        assertTrue("length $length", length > 0)
        // Exactly the two seconds put in, as stereo floats at the bus's rate, and actual sound in it.
        val pcm = device.samples()
        assertEquals(48_000 * 2 * 2, pcm.size)
        val loudest = pcm.maxOf { kotlin.math.abs(it) }
        assertTrue("peak $loudest", loudest in 0.25f..0.5f)
    }

    @Test fun graalJsRunsCurrentJavaScript() {
        // The shape of YouTube's throttling function: a named function over a string.
        assertEquals("cba", JavaScript.run("function f(a){return a.split('').reverse().join('')}", "f", "abc"))
        // Syntax Rhino can't parse: optional chaining, nullish coalescing, Array.at.
        val modern = "var g = (a) => { const b = a?.split(''); return (b?.at(-1) ?? 'x') + b.length; };"
        JavaScript.compileOrThrow(modern)
        assertEquals("c3", JavaScript.run(modern, "g", "abc"))
        try {
            JavaScript.compileOrThrow("function (")
            fail("broken code should be refused")
        } catch (expected: RuntimeException) {
        }
    }
}
