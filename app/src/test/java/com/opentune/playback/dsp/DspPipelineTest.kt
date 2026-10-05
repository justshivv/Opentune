package com.opentune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.TrimmingAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

@UnstableApi
class DspPipelineTest {
    private fun pcm(frames: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * 2) { putShort((it % 2000).toShort()) }
            flip()
        }

    @Test
    fun runsThroughTheRealPipeline() {
        val dsp = DspAudioProcessor()
        val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(dsp, SonicAudioProcessor()))
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        repeat(50) {
            val input = pcm(1024)
            while (input.hasRemaining()) {
                pipeline.queueInput(input)
                val out = pipeline.getOutput()
                out.position(out.limit())
            }
        }
    }

    private fun floatPcm(frames: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * 2) { putFloat((it % 200) / 400f) }
            flip()
        }

    /** The chain DefaultAudioSink builds for 16-bit audio, with Opus's encoder delay trimmed. */
    private fun sinkChain(dsp: DspAudioProcessor, encoding: Int, input: () -> ByteBuffer, delay: Int = 312, padding: Int = 0) {
        val trimming = TrimmingAudioProcessor().apply { setTrimFrameCount(delay, padding) }
        val pipeline = AudioProcessingPipeline(
            ImmutableList.of<AudioProcessor>(trimming, ToInt16PcmAudioProcessor(), dsp, SilenceSkippingAudioProcessor(), SonicAudioProcessor()),
        )
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, encoding))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        repeat(50) {
            val buffer = input()
            while (buffer.hasRemaining()) {
                pipeline.queueInput(buffer)
                val out = pipeline.getOutput()
                out.position(out.limit())
            }
        }
        pipeline.queueEndOfStream()
        repeat(4) { pipeline.getOutput().let { it.position(it.limit()) } }
    }

    @Test
    fun sixteenBitWithTrimming() = sinkChain(DspAudioProcessor(), C.ENCODING_PCM_16BIT, { pcm(960) })

    @Test
    fun floatDecoderOutputWithTrimming() = sinkChain(DspAudioProcessor(), C.ENCODING_PCM_FLOAT, { floatPcm(960) })

    @Test
    fun withPaddingAtTheEnd() = sinkChain(DspAudioProcessor(), C.ENCODING_PCM_16BIT, { pcm(960) }, delay = 312, padding = 500)

    /**
     * DefaultAudioSink (1.11) asks the pipeline for output before queueing any
     * input, so a fresh processor sees Media3's shared empty buffer, which is
     * also its own output buffer until it first allocates one.
     */
    @Test
    fun outputAskedForBeforeAnyInput() {
        val dsp = DspAudioProcessor()
        val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(dsp, SonicAudioProcessor()))
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        pipeline.getOutput()
        val input = pcm(1024)
        pipeline.queueInput(input)
        pipeline.getOutput()
        // And again after a reset, as a retry's prepare() does.
        pipeline.reset()
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        pipeline.getOutput()
    }
}
