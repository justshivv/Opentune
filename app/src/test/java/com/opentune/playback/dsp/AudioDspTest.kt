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
    fun sleepTimerGainTurnsTheSignalDown() {
        try {
            for (params in listOf(DspParams(), DspParams(EqualizerSettings(enabled = true, preampDb = 0.5f)))) {
                DspAudioProcessor.masterGain = 0.5f
                val out = run(configured(params), pcm16Stereo(200) { 0.6f })
                val first = out.getShort().toInt()
                assertEquals(0.3 * 32767, first.toDouble(), 32767 * 0.03)
            }
        } finally {
            DspAudioProcessor.masterGain = 1f
        }
        // The fade: a little over three minutes at most, gentle at first, silent at the end.
        assertEquals(180_000L, com.opentune.playback.SleepTimer.fadeMsFor(60 * 60_000L))
        assertEquals(5 * 60_000L / 3, com.opentune.playback.SleepTimer.fadeMsFor(5 * 60_000L))
        assertEquals(1f, com.opentune.playback.SleepTimer.gainAt(0f), 0.001f)
        assertEquals(0f, com.opentune.playback.SleepTimer.gainAt(1f), 0.001f)
        assert(com.opentune.playback.SleepTimer.gainAt(0.25f) > 0.7f)
    }

    @Test
    fun eightDCirclesFromOneEarToTheOther() {
        // A 4-second circle at 48 kHz: a quarter of the way round the sound is on the right, three quarters on the left.
        val p = configured(DspParams(eightDPeriod = 4f))
        val frames = 48_000 * 4
        val out = run(p, pcm16Stereo(frames) { 0.4f })
        fun level(from: Int, to: Int, channel: Int): Double {
            var sum = 0.0
            for (i in from until to) sum += kotlin.math.abs(out.getShort((i * 2 + channel) * 2).toInt())
            return sum / (to - from)
        }
        val q = frames / 4
        assert(level(q - 2_000, q + 2_000, 1) > 3 * level(q - 2_000, q + 2_000, 0)) { "right ear louder at a quarter turn" }
        assert(level(3 * q - 2_000, 3 * q + 2_000, 0) > 3 * level(3 * q - 2_000, 3 * q + 2_000, 1)) { "left ear louder at three quarters" }
        assertEquals(false, DspParams(eightDPeriod = 4f).isNeutral)
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
        val p = configured(DspParams(EqualizerSettings(enabled = true, bands = List(15) { if (it == 8) -3f else 0f })))
        val out = run(p, pcm16Stereo(5_000) { 0.99f })
        var last = 0
        while (out.hasRemaining()) last = out.getShort().toInt()
        assertTrue("got $last", last > 32_300)
    }

    @Test
    fun onlyBoostingChainsUseTheLimiter() {
        assertTrue(!DspParams().canBoost)
        assertTrue(!DspParams(EqualizerSettings(enabled = true, bands = List(15) { if (it == 0) -2f else 0f })).canBoost)
        assertTrue(DspParams(EqualizerSettings(enabled = true, bassDb = 2f)).canBoost)
        assertTrue(DspParams(bassBoost = 100).canBoost)
    }

    @Test
    fun eqBoostsGetMatchingHeadroom() {
        val p = DspParams(EqualizerSettings(enabled = true, bands = List(15) { if (it == 0) 6f else 0f }))
        assertEquals(dbToGain(-6f), p.preampGain, 1e-4f)
        assertEquals(1f, DspParams(EqualizerSettings(enabled = true, bands = List(15) { if (it == 0) -3f else 0f })).preampGain, 1e-4f)
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

class ClarityTest {
    private val fs = 48_000.0

    /** The combined response of every clarity stage at [freq], in dB. */
    private fun responseDb(freq: Double): Double =
        DspParams(clarity = true).stages(fs.toInt()).sumOf { it().magnitudeDb(freq, fs) }

    @Test
    fun liftsPresenceAndAirCutsMud() {
        assertTrue("presence lifted", responseDb(3_400.0) > 2.5)
        assertTrue("air lifted", responseDb(14_000.0) > 3.5)
        assertTrue("low mids cut", responseDb(280.0) < -1.5)
        assertTrue("rumble removed", responseDb(10.0) < -10.0)
    }

    @Test
    fun runsTheLimiterAndLeavesHeadroom() {
        val p = DspParams(clarity = true)
        assertTrue(p.canBoost)
        assertTrue(!p.isNeutral)
        assertEquals(dbToGain(-DspParams.CLARITY_HEADROOM_DB), p.preampGain, 1e-4f)
    }

    @Test
    fun highPassIsFlatInTheBand() {
        val hp = Biquad.highPass(fs, 24.0)
        assertEquals(0.0, hp.magnitudeDb(1_000.0, fs), 0.05)
        assertEquals(-3.0, hp.magnitudeDb(24.0, fs), 0.2)
    }
}

class OutputGainTest {
    @Test
    fun fullVolumeStaysBitExact() {
        assertTrue(DspParams(outputGainDb = 0f).isNeutral)
    }

    @Test
    fun lowerVolumeIsAPlainCut() {
        val p = DspParams(outputGainDb = -12f)
        assertTrue(!p.isNeutral)
        assertTrue(!p.canBoost)
        assertEquals(dbToGain(-12f), p.preampGain, 1e-5f)
    }

    @Test
    fun zeroVolumeIsSilent() {
        assertEquals(0f, DspParams(outputGainDb = com.opentune.playback.BitPerfectUsb.SILENT_DB).preampGain, 0f)
    }
}

class FifteenBandTest {
    @Test
    fun sevenBandCurvesCarryOver() {
        val old = listOf(6f, 0f, 0f, 0f, 0f, 0f, 0f) // +6 dB at 60 Hz only
        val new = com.opentune.data.settings.resampleBands(old)
        assertEquals(15, new.size)
        assertEquals(6f, new[0], 0.01f) // 25 Hz, below the old range: flat at its first value
        assertTrue(new[3] in 0.1f..5.9f) // 100 Hz, between 60 and 150
        assertEquals(0f, new[14], 0.01f)
    }

    @Test
    fun everyPresetHasFifteenBands() {
        com.opentune.data.settings.EqPreset.entries.forEach { assertEquals(it.label, 15, it.bands.size) }
    }
}
