package com.opentune.playback.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The decoder's PCM, at whatever depth it comes (8, 16, 24 or 32-bit, or
 * float), becomes one block of 32-bit float samples; [dsp] works on that,
 * and the result is made into what the output takes once, at the end: float
 * where the device opens float directly and [floatWanted] says so, else
 * 16-bit with triangular dither and noise shaping ([Dither16]).
 *
 * Without it, Media3 cut a 24-bit file to 16 bits before any effect ran
 * (and a float output skipped the effects altogether), so the equalizer
 * worked on a truncated signal and its result was rounded a second time.
 *
 * With the effects off and the output in the decoder's own format, the
 * samples go through untouched, so a bit-perfect USB route stays bit-exact.
 * Anything that isn't plain linear PCM (a passthrough bitstream) goes
 * straight to [delegate], which must not run [dsp] itself.
 */
@OptIn(UnstableApi::class)
class PrecisionAudioSink(
    private val delegate: AudioSink,
    private val dsp: DspAudioProcessor,
    /** Whether to put out float for a song whose decoder gives this [C] encoding. */
    private val floatWanted: (sourceEncoding: Int) -> Boolean,
) : ForwardingAudioSink(delegate) {

    private var precise = false
    private var inputEncoding = C.ENCODING_INVALID
    private var outputEncoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var dither = Dither16(0)
    private var floats: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    /** Output [delegate] hasn't taken yet, and the time it starts at. */
    private var pending: ByteBuffer? = null
    private var pendingTimeUs = 0L

    /** Whether this format comes through here: linear PCM in an encoding [decode] reads. */
    private fun readable(format: Format) =
        MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding in READABLE

    /**
     * Any depth of integer PCM is taken as it is, so a 24-bit file reaches the
     * effects at 24 bits. Float is taken only when the output is float: a
     * "yes" makes the platform's decoders put out float for every song, and a
     * 16-bit file would then be dithered on the way out instead of passing
     * through bit for bit.
     */
    override fun getFormatSupport(format: Format): Int =
        if (readable(format) && (format.pcmEncoding != C.ENCODING_PCM_FLOAT || floatWanted(C.ENCODING_PCM_FLOAT))) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else super.getFormatSupport(format)

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        pending = null
        precise = readable(format)
        if (!precise) {
            super.configure(audioSinkConfig)
            return
        }
        inputEncoding = format.pcmEncoding
        // Read as the output opens, so an exclusive USB DAC is set to a format that holds it whole.
        com.opentune.playback.BitPerfectUsb.sourceEncoding = inputEncoding
        channels = format.channelCount
        sampleRate = format.sampleRate
        val floatFormat = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
        outputEncoding = if (floatWanted(inputEncoding) && delegate.getFormatSupport(floatFormat) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY) {
            C.ENCODING_PCM_FLOAT
        } else {
            C.ENCODING_PCM_16BIT
        }
        dither = Dither16(channels)
        dsp.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT))
        dsp.flush()
        super.configure(withFormat(audioSinkConfig, format.buildUpon().setPcmEncoding(outputEncoding).build()))
    }

    /** [config] as it was, for [format]. */
    private fun withFormat(config: AudioSink.AudioSinkConfig, format: Format): AudioSink.AudioSinkConfig {
        val b = AudioSink.AudioSinkConfig.Builder(format).setPreferredBufferSizeOverride(config.preferredBufferSizeOverride)
        @Suppress("SENSELESS_COMPARISON")
        if (config.outputChannelMapping != null) b.setOutputChannelMapping(config.outputChannelMapping)
        @Suppress("SENSELESS_COMPARISON")
        if (config.timeline != null) b.setTimeline(config.timeline)
        config.mediaPeriodId?.let(b::setMediaPeriodId)
        return b.build()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!precise) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // What was made last time goes first; new input waits until it has.
        pending?.let { p ->
            if (!super.handleBuffer(p, pendingTimeUs, 1)) return false
            pending = null
        }
        if (!buffer.hasRemaining()) return true
        if (untouched()) {
            if (com.opentune.playback.AudioLevels.listening) measure(buffer)
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        val made = process(buffer)
        pending = made
        pendingTimeUs = presentationTimeUs
        if (super.handleBuffer(made, presentationTimeUs, 1)) pending = null
        return true
    }

    /** The effects are off and the output is the decoder's own format: nothing to do to the samples. */
    private fun untouched(): Boolean =
        inputEncoding == outputEncoding && dsp.params.isNeutral &&
            DspAudioProcessor.masterGain >= 0.999f && !DspAudioProcessor.silenced

    /** [input], all of it, through the effects and into [out], in the output's encoding. */
    private fun process(input: ByteBuffer): ByteBuffer {
        val inFrame = bytesPerSample(inputEncoding) * channels
        val frames = input.remaining() / inFrame
        floats = room(floats, frames * channels * 4)
        decode(input, frames * channels, floats)
        // A trailing partial frame, if a decoder ever left one, is dropped rather than misread.
        input.position(input.limit())
        floats.flip()
        dsp.queueInput(floats)
        val processed = dsp.output.order(ByteOrder.nativeOrder())
        val samples = processed.remaining() / 4
        out = room(out, samples * if (outputEncoding == C.ENCODING_PCM_FLOAT) 4 else 2)
        if (outputEncoding == C.ENCODING_PCM_FLOAT) {
            out.put(processed)
        } else {
            var ch = 0
            while (processed.remaining() >= 4) {
                if (ch == 0) dither.nextFrame()
                out.putShort(dither.encode(processed.getFloat(), ch))
                ch = if (ch + 1 >= channels) 0 else ch + 1
            }
        }
        out.flip()
        return out
    }

    private fun decode(input: ByteBuffer, samples: Int, into: ByteBuffer) {
        val src = input.order(ByteOrder.LITTLE_ENDIAN)
        when (inputEncoding) {
            C.ENCODING_PCM_8BIT -> repeat(samples) { into.putFloat(((src.get().toInt() and 0xFF) - 128) / 128f) }
            C.ENCODING_PCM_16BIT -> repeat(samples) { into.putFloat(src.getShort() / 32768f) }
            C.ENCODING_PCM_24BIT -> repeat(samples) {
                val v = (src.get().toInt() and 0xFF) or ((src.get().toInt() and 0xFF) shl 8) or (src.get().toInt() shl 16)
                into.putFloat(v / 8388608f)
            }
            C.ENCODING_PCM_32BIT -> repeat(samples) { into.putFloat((src.getInt() / 2147483648.0).toFloat()) }
            C.ENCODING_PCM_FLOAT -> repeat(samples) { into.putFloat(src.getFloat()) }
        }
    }

    /** The decoder's samples as levels for the on-screen lights, read without moving [buffer]. */
    private fun measure(buffer: ByteBuffer) {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = view.position()
        val width = bytesPerSample(inputEncoding)
        if (channels == 0 || width == 0) return
        com.opentune.playback.AudioLevels.measure(view.remaining() / (channels * width), channels, sampleRate) { i ->
            when (inputEncoding) {
                C.ENCODING_PCM_16BIT -> view.getShort(base + i * 2) / 32768f
                C.ENCODING_PCM_FLOAT -> view.getFloat(base + i * 4)
                C.ENCODING_PCM_32BIT -> (view.getInt(base + i * 4) / 2147483648.0).toFloat()
                C.ENCODING_PCM_24BIT -> (view.getShort(base + i * 3 + 1) / 32768f)
                else -> 0f
            }
        }
    }

    override fun playToEndOfStream() {
        pending?.let { p ->
            if (!super.handleBuffer(p, pendingTimeUs, 1)) return
            pending = null
        }
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = pending == null && super.isEnded()

    override fun hasPendingData(): Boolean = pending != null || super.hasPendingData()

    override fun flush() {
        pending = null
        if (precise) dsp.flush()
        super.flush()
    }

    override fun reset() {
        pending = null
        precise = false
        dsp.reset()
        super.reset()
    }

    private fun room(buffer: ByteBuffer, bytes: Int): ByteBuffer =
        if (buffer.capacity() >= bytes) buffer.apply { clear() } else ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    companion object {
        private val READABLE = setOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)

        internal fun bytesPerSample(encoding: Int) = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }
}

/**
 * Float samples (−1..1) to 16-bit, dithered and noise-shaped. Triangular
 * dither breaks up the correlation that makes plain truncation sound grainy
 * on quiet tails, and feeding the rounding error back shifts that noise up
 * out of the ear's most sensitive range. A truly silent sample stays silent,
 * and one dither value is shared across a frame's channels, so mono content
 * quantises the same on both.
 */
class Dither16(channels: Int) {
    private val error = FloatArray(channels)
    private var state = -0x61c8864680b583ebL
    private var current = 0f

    /** A new dither value, for the frame about to be encoded. */
    fun nextFrame() {
        var s = state
        s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
        state = s
        val u1 = ((s ushr 40).toInt() and 0xFFFF) / 65536f
        val u2 = ((s ushr 8).toInt() and 0xFFFF) / 65536f
        current = (u1 - 0.5f) + (u2 - 0.5f)
    }

    fun encode(sample: Float, channel: Int): Short {
        if (sample == 0f) { error[channel] = 0f; return 0 }
        val desired = sample * 32767f + error[channel]
        val q = Math.round(desired + current).coerceIn(-32768, 32767)
        error[channel] = desired - q
        return q.toShort()
    }
}
