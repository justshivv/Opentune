package com.opentune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PrecisionAudioSinkTest {

    /** What the real sink would be handed, and a switch for whether it takes it. */
    private class Delegate(val takesFloat: Boolean) {
        var configured: Format? = null
        val received = mutableListOf<ByteArray>()
        val buffers = mutableListOf<ByteBuffer>()
        var accepting = true

        val sink: AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, m, args ->
            when (m.name) {
                "configure" -> { configured = (args[0] as AudioSink.AudioSinkConfig).format; null }
                "getFormatSupport" -> {
                    val f = args[0] as Format
                    if (f.pcmEncoding == C.ENCODING_PCM_FLOAT && !takesFloat) AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING else AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                }
                "handleBuffer" -> {
                    val b = args[0] as ByteBuffer
                    if (!accepting) false else {
                        buffers += b
                        received += ByteArray(b.remaining()).also { b.get(it) }
                        true
                    }
                }
                "isEnded", "hasPendingData", "supportsFormat" -> false
                else -> if (m.returnType == java.lang.Boolean.TYPE) false else if (m.returnType == java.lang.Long.TYPE) 0L else if (m.returnType == Integer.TYPE) 0 else null
            }
        } as AudioSink
    }

    private fun format(encoding: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding).setChannelCount(2).setSampleRate(48_000).build()

    private fun config(encoding: Int) = AudioSink.AudioSinkConfig.Builder(format(encoding)).build()

    private fun pcm(bytes: Int, fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { fill(); flip() }

    private fun shorts(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { s -> ShortArray(s.remaining()).also { s.get(it) } }

    @Test fun aTwentyFourBitFileBecomesDithered16BitNotTruncated() {
        val d = Delegate(takesFloat = false)
        val sink = PrecisionAudioSink(d.sink, DspAudioProcessor(), floatWanted = { false })
        sink.configure(config(C.ENCODING_PCM_24BIT))
        assertEquals(C.ENCODING_PCM_16BIT, d.configured!!.pcmEncoding)
        // Half scale on both channels, for a while.
        val input = pcm(6 * 200) { repeat(400) { put(0x00); put(0x00); put(0x40) } }
        assertTrue(sink.handleBuffer(input, 0, 1))
        assertFalse(input.hasRemaining())
        val out = shorts(d.received.single())
        assertEquals(400, out.size)
        assertTrue(out.all { it in 16382..16385 })
    }

    @Test fun nothingToDoMeansTheSamplesGoThroughUntouched() {
        val d = Delegate(takesFloat = false)
        val sink = PrecisionAudioSink(d.sink, DspAudioProcessor(), floatWanted = { false })
        sink.configure(config(C.ENCODING_PCM_16BIT))
        val input = pcm(8) { putShort(1); putShort(-2); putShort(12345); putShort(-32768) }
        assertTrue(sink.handleBuffer(input, 0, 1))
        assertSame(input, d.buffers.single())
        assertEquals(listOf<Short>(1, -2, 12345, -32768), shorts(d.received.single()).toList())
    }

    @Test fun decodersAreOnlyAskedForFloatWhenTheOutputIsFloat() {
        val d = Delegate(takesFloat = false)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, PrecisionAudioSink(d.sink, DspAudioProcessor(), floatWanted = { false }).getFormatSupport(format(C.ENCODING_PCM_FLOAT)))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, PrecisionAudioSink(d.sink, DspAudioProcessor(), floatWanted = { false }).getFormatSupport(format(C.ENCODING_PCM_24BIT)))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, PrecisionAudioSink(d.sink, DspAudioProcessor(), floatWanted = { true }).getFormatSupport(format(C.ENCODING_PCM_FLOAT)))
    }

    @Test fun floatWhereTheOutputTakesIt() {
        val d = Delegate(takesFloat = true)
        val sink = PrecisionAudioSink(d.sink, DspAudioProcessor(), floatWanted = { true })
        sink.configure(config(C.ENCODING_PCM_24BIT))
        assertEquals(C.ENCODING_PCM_FLOAT, d.configured!!.pcmEncoding)
        sink.handleBuffer(pcm(6) { repeat(2) { put(0x00); put(0x00); put(0xC0.toByte()) } }, 0, 1)
        val f = ByteBuffer.wrap(d.received.single()).order(ByteOrder.nativeOrder())
        assertEquals(-0.5f, f.getFloat(), 0f)
    }

    @Test fun effectsRunOnTheFloatSignal() {
        val d = Delegate(takesFloat = false)
        val dsp = DspAudioProcessor().apply { params = DspParams(outputGainDb = -6.0206f) }
        val sink = PrecisionAudioSink(d.sink, dsp, floatWanted = { false })
        sink.configure(config(C.ENCODING_PCM_16BIT))
        sink.handleBuffer(pcm(400) { repeat(200) { putShort(16384) } }, 0, 1)
        assertTrue(shorts(d.received.single()).all { it in 8190..8194 })
    }

    @Test fun outputTheDeviceIsntReadyForWaitsAndNewInputWaitsBehindIt() {
        val d = Delegate(takesFloat = false)
        val dsp = DspAudioProcessor().apply { params = DspParams(outputGainDb = -6.0206f) }
        val sink = PrecisionAudioSink(d.sink, dsp, floatWanted = { false })
        sink.configure(config(C.ENCODING_PCM_16BIT))
        d.accepting = false
        val first = pcm(8) { repeat(4) { putShort(1000) } }
        assertTrue(sink.handleBuffer(first, 0, 1))
        assertTrue(sink.hasPendingData())
        val second = pcm(8) { repeat(4) { putShort(2000) } }
        assertFalse(sink.handleBuffer(second, 100, 1))
        assertEquals(8, second.remaining())
        d.accepting = true
        assertTrue(sink.handleBuffer(second, 100, 1))
        assertEquals(2, d.received.size)
        assertTrue(shorts(d.received[0]).all { it in 498..502 })
        assertTrue(shorts(d.received[1]).all { it in 998..1002 })
    }
}
