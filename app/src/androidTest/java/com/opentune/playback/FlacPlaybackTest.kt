package com.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.LogRedirectionStrategy
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.opentune.playback.dsp.DspAudioProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Measures the actual decoder → DSP output, not just whether a file opens. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FlacPlaybackTest {
    private class Capture : BaseAudioProcessor() {
        data class Segment(val rate: Int, val samples: MutableList<Float> = mutableListOf())
        val segments = mutableListOf<Segment>()
        override fun onConfigure(format: AudioFormat): AudioFormat {
            require(format.channelCount == 2 && format.encoding == C.ENCODING_PCM_16BIT)
            return format
        }
        override fun onFlush() { segments += Segment(inputAudioFormat.sampleRate) }
        override fun queueInput(buffer: ByteBuffer) {
            if (!buffer.hasRemaining()) return
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            while (view.hasRemaining()) segments.last().samples += view.short / 32768f
            replaceOutputBuffer(buffer.remaining()).put(buffer).flip()
        }
    }

    private fun fixture(file: File, rate: Int, bits: Int) {
        val latch = CountDownLatch(1)
        var success = false
        val session = FFmpegSession.create(arrayOf("-v", "error", "-y", "-f", "lavfi", "-i",
            "aevalsrc=0.17*sin(2*PI*997*t)|0.09*sin(2*PI*233*t):s=$rate:d=0.25",
            "-sample_fmt", if (bits == 16) "s16" else "s32", "-c:a", "flac", file.path), {
            success = ReturnCode.isSuccess(it.returnCode); latch.countDown()
        }, null, null, LogRedirectionStrategy.NEVER_PRINT_LOGS)
        FFmpegKitConfig.asyncFFmpegExecute(session)
        assertTrue(latch.await(30, TimeUnit.SECONDS)); assertTrue(success)
        FFmpegKitConfig.clearSessions()
    }

    @Test fun cdAndHighResolutionFlacPreserveTimingChannelsAndSamplesThroughThePlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "pcm-regression-${System.nanoTime()}").apply { mkdirs() }
        var player: ExoPlayer? = null
        try {
            val rates = listOf(44100, 192000, 96000)
            val files = rates.map { rate -> File(folder, "$rate.flac").also { fixture(it, rate, if (rate == 44100) 16 else 24) } }
            val capture = Capture()
            val ended = CountDownLatch(1)
            var failure: PlaybackException? = null
            instrumentation.runOnMainSync {
                DspAudioProcessor.silenced = false
                DspAudioProcessor.masterGain = 1f
                player = ExoPlayer.Builder(context, MusicRenderersFactory(context, false, arrayOf(DspAudioProcessor(), capture))).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
                        override fun onPlayerError(error: PlaybackException) { failure = error; ended.countDown() }
                    })
                    // Use the same overload as the app; R8 removes unused convenience overloads.
                    setMediaItems(files.map { MediaItem.fromUri(android.net.Uri.fromFile(it)) }, 0, 0L)
                    prepare(); play()
                }
            }
            assertTrue("Playback did not complete", ended.await(30, TimeUnit.SECONDS))
            assertNull("Decoder error: $failure", failure)
            for (rate in rates) {
                val samples = capture.segments.filter { it.rate == rate }.flatMap { it.samples }
                assertEquals("Frame count at $rate Hz", rate / 4 * 2, samples.size)
                for (channel in 0..1) {
                    val frequency = if (channel == 0) 997 else 233
                    val amplitude = if (channel == 0) 0.17 else 0.09
                    var error = 0.0
                    for (frame in 0 until samples.size / 2) {
                        val expected = amplitude * sin(2 * PI * frequency * frame / rate)
                        val delta = samples[frame * 2 + channel] - expected
                        error += delta * delta
                    }
                    assertTrue("Distorted channel $channel at $rate Hz", sqrt(error / (samples.size / 2)) < 0.0002)
                }
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            folder.deleteRecursively()
        }
    }
}
