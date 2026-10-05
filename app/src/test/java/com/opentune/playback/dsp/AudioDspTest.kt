package com.opentune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.opentune.data.settings.EqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {
    private val fs = 48_000.0

    @Test
    fun peakingHitsItsGainAtTheCentreAndLeavesFarFrequenciesAlone() {
        val f = Biquad.peaking(fs, 1_000.0, 1.1, 6.0)
        assertEquals(6.0, f.magnitudeDb(1_000.0, fs), 0.05)
        assertEquals(0.0, f.magnitudeDb(30.0, fs), 0.3)
        assertEquals(0.0, f.magnitudeDb(18_000.0, fs), 0.3)
    }

    @Test
    fun shelvesReachTheirGainAtTheExtremes() {
        assertEquals(9.0, Biquad.lowShelf(fs, 120.0, 9.0).magnitudeDb(20.0, fs), 0.3)
        assertEquals(0.0, Biquad.lowShelf(fs, 120.0, 9.0).magnitudeDb(5_000.0, fs), 0.2)
        assertEquals(-6.0, Biquad.highShelf(fs, 8_000.0, -6.0).magnitudeDb(20_000.0, fs), 0.5)
        assertEquals(0.0, Biquad.highShelf(fs, 8_000.0, -6.0).magnitudeDb(200.0, fs), 0.2)
    }

    @Test
    fun timeDomainOutputMatchesTheDesignedGain() {
        val f = Biquad.peaking(fs, 1_000.0, 1.1, -6.0)
        val n = 48_000
        var inSq = 0.0; var outSq = 0.0
        for (i in 0 until n) {
            val x = sin(2 * PI * 1_000 * i / fs)
            val y = f.process(x)
            if (i > n / 2) { inSq += x * x; outSq += y * y }
        }
        assertEquals(-6.0, 10 * kotlin.math.log10(outSq / inSq), 0.1)
    }
}

class DspAudioProcessorTest {
    private fun pcm16Stereo(frames: Int, gen: (Int) -> Float): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { i -> val s = (gen(i) * 32767).toInt().toShort(); b.putShort(s); b.putShort(s) }
        b.flip()
        return b
    }

    private fun run(p: DspAudioProcessor, input: ByteBuffer): ByteBuffer {
        p.queueInput(input)
        return p.output
    }

    private fun configured(params: DspParams): DspAudioProcessor = DspAudioProcessor().apply {
        this.params = params
        configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        flush()
    }

    @Test
    fun neutralSettingsPassSamplesThroughUnchanged() {
        val p = configured(DspParams())
        val input = pcm16Stereo(1_000) { sin(it / 10.0).toFloat() * 0.5f }
        val copy = ByteBuffer.allocate(input.remaining()).put(input.duplicate()).apply { flip() }
        val out = run(p, input)
        assertEquals(copy.remaining(), out.remaining())
        while (copy.hasRemaining()) assertEquals(copy.get(), out.get())
    }

    @Test
    fun balanceFullyRightSilencesTheLeftChannel() {
        val p = configured(DspParams(EqualizerSettings(enabled = true, balance = 1f)))
        val out = run(p, pcm16Stereo(500) { 0.5f })
        repeat(500) { assertEquals(0, out.getShort().toInt()); assertTrue(out.getShort() > 15_000) }
    }

    @Test
    fun boostsAreSoftLimitedInsteadOfClipping() {
        val p = configured(DspParams(EqualizerSettings(enabled = true, preampDb = 12f)))
        val out = run(p, pcm16Stereo(2_000) { sin(2 * PI * 440 * it / 48_000).toFloat() * 0.9f })
        var peak = 0
        while (out.hasRemaining()) peak = maxOf(peak, abs(out.getShort().toInt()))
        assertTrue("peak $peak stays under full scale", peak < 32_767)
        assertTrue("but is still loud", peak > 28_000)
    }

    @Test
    fun cutsOnlyLeaveFullScalePeaksUntouched() {
        // A cut can't clip, so nothing should limit. A 1 kHz cut has unity gain
        // at DC, so near-full-scale DC must come out at the same level; with the
        // limiter engaged it would be squeezed to about 0.95.
        val p = configured(DspParams(EqualizerSettings(enabled = true, bands = listOf(0f, 0f, 0f, -3f, 0f, 0f, 0f))))
        val out = run(p, pcm16Stereo(5_000) { 0.99f })
        var last = 0
        while (out.hasRemaining()) last = out.getShort().toInt()
        assertTrue("got $last", last > 32_300)
    }

    @Test
    fun onlyBoostingChainsUseTheLimiter() {
        assertTrue(!DspParams().canBoost)
        assertTrue(!DspParams(EqualizerSettings(enabled = true, bands = listOf(-2f, 0f, 0f, 0f, 0f, 0f, 0f))).canBoost)
        assertTrue(DspParams(EqualizerSettings(enabled = true, bassDb = 2f)).canBoost)
        assertTrue(DspParams(bassBoost = 100).canBoost)
    }

    @Test
    fun eqBoostsGetMatchingHeadroom() {
        val p = DspParams(EqualizerSettings(enabled = true, bands = listOf(6f, 0f, 0f, 0f, 0f, 0f, 0f)))
        assertEquals(dbToGain(-6f), p.preampGain, 1e-4f)
        assertEquals(1f, DspParams(EqualizerSettings(enabled = true, bands = listOf(-3f, 0f, 0f, 0f, 0f, 0f, 0f))).preampGain, 1e-4f)
    }

    @Test
    fun softLimitIsTransparentBelowTheKnee() {
        assertEquals(0.5f, DspAudioProcessor.softLimit(0.5f), 0f)
        assertTrue(DspAudioProcessor.softLimit(3f) < 1f)
        assertTrue(DspAudioProcessor.softLimit(-3f) > -1f)
    }

    @Test
    fun spatialWideningKeepsMonoContentIntact() {
        val p = configured(DspParams(spatial = true))
        val out = run(p, pcm16Stereo(200) { 0.3f })
        repeat(200) { val l = out.getShort(); val r = out.getShort(); assertEquals(l, r) }
    }
}
