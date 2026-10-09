package com.opentune.playback.dsp

import com.opentune.playback.BitPerfectUsb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.settings.EqualizerSettings
import com.opentune.data.settings.FilterType
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** One second-order IIR section, transposed direct form II, double precision state. */
class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    /** |H(f)| in dB, for tests and for drawing the curve. */
    fun magnitudeDb(freq: Double, sampleRate: Double): Double {
        val w = 2 * PI * freq / sampleRate
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2; val ni = -(b1 * sw + b2 * s2)
        val dr = 1 + a1 * cw + a2 * c2; val di = -(a1 * sw + a2 * s2)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20 * kotlin.math.log10(mag)
    }

    companion object {
        /** Coefficients from Robert Bristow-Johnson's Audio EQ Cookbook. */
        fun peaking(sampleRate: Double, freq: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * freq / sampleRate
            val alpha = sin(w0) / (2 * q)
            val cw = cos(w0)
            val a0 = 1 + alpha / a
            return Biquad((1 + alpha * a) / a0, -2 * cw / a0, (1 - alpha * a) / a0, -2 * cw / a0, (1 - alpha / a) / a0)
        }

        /** Second-order high-pass; [q] of 0.7071 is Butterworth. */
        fun highPass(sampleRate: Double, freq: Double, q: Double = 0.7071): Biquad {
            val w0 = 2 * PI * freq / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            return Biquad((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
        }

        /** [q] of 1/√2 (the default) is a shelf slope of 1; AutoEq's shelves give their own. */
        fun lowShelf(sampleRate: Double, freq: Double, gainDb: Double, q: Double = SHELF_Q): Biquad = shelf(sampleRate, freq, gainDb, q, low = true)

        fun highShelf(sampleRate: Double, freq: Double, gainDb: Double, q: Double = SHELF_Q): Biquad = shelf(sampleRate, freq, gainDb, q, low = false)

        private const val SHELF_Q = 0.70710678

        private fun shelf(sampleRate: Double, freq: Double, gainDb: Double, q: Double, low: Boolean): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * freq / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val sa = 2 * sqrt(a) * alpha
            return if (low) {
                val a0 = (a + 1) + (a - 1) * cw + sa
                Biquad(
                    a * ((a + 1) - (a - 1) * cw + sa) / a0,
                    2 * a * ((a - 1) - (a + 1) * cw) / a0,
                    a * ((a + 1) - (a - 1) * cw - sa) / a0,
                    -2 * ((a - 1) + (a + 1) * cw) / a0,
                    ((a + 1) + (a - 1) * cw - sa) / a0,
                )
            } else {
                val a0 = (a + 1) - (a - 1) * cw + sa
                Biquad(
                    a * ((a + 1) + (a - 1) * cw + sa) / a0,
                    -2 * a * ((a - 1) + (a + 1) * cw) / a0,
                    a * ((a + 1) + (a - 1) * cw - sa) / a0,
                    2 * ((a - 1) - (a + 1) * cw) / a0,
                    ((a + 1) - (a - 1) * cw - sa) / a0,
                )
            }
        }
    }
}

/** Everything the processor applies, as one immutable snapshot. */
data class DspParams(
    val equalizer: EqualizerSettings = EqualizerSettings(),
    /** 0..1000 from the Remix sheet's bass boost. */
    val bassBoost: Int = 0,
    val spatial: Boolean = false,
    /** The "Clarity" curve: firmer bass, less mud, more presence and air. */
    val clarity: Boolean = false,
    /** A plain level change, never above 0 dB: the volume while bit-perfect. */
    val outputGainDb: Float = 0f,
) {
    private val eqActive: Boolean
        get() = equalizer.enabled && (
            equalizer.bands.any { it != 0f } || equalizer.preampDb != 0f ||
                equalizer.bassDb != 0f || equalizer.trebleDb != 0f || equalizer.balance != 0f
            )

    /** The headphone correction from AutoEq, when one is chosen; independent of [eqActive]. */
    private val headphone get() = equalizer.headphone?.takeIf { it.filters.isNotEmpty() }

    val isNeutral: Boolean get() = !eqActive && headphone == null && bassBoost == 0 && !spatial && !clarity && outputGainDb == 0f

    /**
     * Whether anything in the chain can push a sample past full scale. Only
     * then does the soft limiter run; otherwise peaks are left exactly as
     * mastered.
     */
    val canBoost: Boolean
        get() = spatial || clarity || bassBoost > 0 || headphone?.filters?.any { it.gainDb > 0 } == true || (eqActive && (
            equalizer.preampDb > 0 || equalizer.bassDb > 0 || equalizer.trebleDb > 0 || equalizer.bands.any { it > 0 }
            ))

    /** The filter stages to run, given the sample rate. */
    fun stages(sampleRate: Int): List<() -> Biquad> {
        val fs = sampleRate.toDouble()
        val nyquistSafe = fs * 0.45
        val out = mutableListOf<() -> Biquad>()
        // The headphone correction first: it flattens the headphones, and the
        // listener's own EQ then shapes a neutral sound.
        headphone?.filters?.forEach { f ->
            val freq = f.freqHz.toDouble()
            if (freq <= 0 || freq >= nyquistSafe) return@forEach
            val q = f.q.toDouble().coerceAtLeast(0.1)
            val gain = f.gainDb.toDouble()
            val stage: () -> Biquad = when (f.type) {
                FilterType.PEAK -> { { Biquad.peaking(fs, freq, q, gain) } }
                FilterType.LOW_SHELF -> { { Biquad.lowShelf(fs, freq, gain, q) } }
                FilterType.HIGH_SHELF -> { { Biquad.highShelf(fs, freq, gain, q) } }
            }
            out.add(stage)
        }
        if (eqActive) {
            equalizer.bands.forEachIndexed { i, gain ->
                val f = EQ_BANDS_HZ.getOrNull(i)?.toDouble() ?: return@forEachIndexed
                if (gain != 0f && f < nyquistSafe) out += { Biquad.peaking(fs, f, BAND_Q, gain.toDouble()) }
            }
            if (equalizer.bassDb != 0f) out += { Biquad.lowShelf(fs, 120.0, equalizer.bassDb.toDouble()) }
            if (equalizer.trebleDb != 0f && 8_000.0 < nyquistSafe) out += { Biquad.highShelf(fs, 8_000.0, equalizer.trebleDb.toDouble()) }
        }
        if (bassBoost > 0) out += { Biquad.lowShelf(fs, 100.0, bassBoost / 1000.0 * MAX_BASS_BOOST_DB) }
        if (clarity) {
            // Cut rumble below hearing,
            // firm up the bass, clear low-mid mud and boxiness, lift the
            // presence band and the air above 10 kHz.
            out += { Biquad.highPass(fs, 24.0) }
            out += { Biquad.peaking(fs, 72.0, 0.80, 3.2) }
            out += { Biquad.peaking(fs, 280.0, 0.90, -3.0) }
            out += { Biquad.peaking(fs, 750.0, 0.85, -1.4) }
            if (3_400.0 < nyquistSafe) out += { Biquad.peaking(fs, 3_400.0, 0.85, 3.8) }
            if (10_500.0 < nyquistSafe) out += { Biquad.highShelf(fs, 10_500.0, 4.8) }
        }
        return out
    }

    /**
     * The input gain: the user's preamp, less automatic headroom equal to the
     * largest boost in the chain, so boosting a band can't push peaks into
     * the limiter.
     */
    val preampGain: Float
        get() {
            val boosts = buildList {
                if (eqActive) {
                    addAll(equalizer.bands)
                    add(equalizer.bassDb)
                    add(equalizer.trebleDb)
                }
                add((bassBoost / 1000.0 * MAX_BASS_BOOST_DB).toFloat())
                // Clarity's biggest lift, less what the soft limiter can absorb
                // without being heard, so it doesn't just make everything quieter.
                if (clarity) add(CLARITY_HEADROOM_DB)
            }
            val headroom = boosts.maxOrNull()?.coerceAtLeast(0f) ?: 0f
            // AutoEq's preamp already makes room for its own boosts.
            val user = (if (eqActive) equalizer.preampDb else 0f) + (headphone?.preampDb?.coerceAtMost(0f) ?: 0f)
            if (outputGainDb <= BitPerfectUsb.SILENT_DB) return 0f
            return dbToGain(user - headroom + outputGainDb.coerceAtMost(0f))
        }
    val balance: Float get() = if (equalizer.enabled) equalizer.balance.coerceIn(-1f, 1f) else 0f

    companion object {
        /** √2: neighbouring 2/3-octave bands overlap at about -3 dB. */
        const val BAND_Q = 1.4142
        const val CLARITY_HEADROOM_DB = 3.3f
        const val MAX_BASS_BOOST_DB = 12.0
    }
}

fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

/**
 * The app's audio chain, inserted ahead of ExoPlayer's own speed/pitch
 * processing: preamp, equalizer bands, tone shelves, bass boost, stereo
 * widening and balance, then a soft limiter only when one of those can
 * boost. With everything off it copies samples through untouched, so the
 * default path is bit-identical to the decoder's output. Loudness
 * normalization isn't here: it's one fixed gain per track from YouTube's own
 * measurement, applied by the service.
 *
 * [params] is swapped from the main thread; the filters are rebuilt on the
 * audio thread the next time a buffer arrives.
 */
@OptIn(UnstableApi::class)
class DspAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var params: DspParams = DspParams()

    private var applied: DspParams? = null
    private var channels = 0
    private var sampleRate = 0
    private var isFloat = false
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        isFloat = enc == C.ENCODING_PCM_FLOAT
        frame = FloatArray(channels)
        applied = null
        return inputAudioFormat
    }

    private fun rebuild(p: DspParams) {
        val stages = p.stages(sampleRate)
        filters = Array(channels) { Array(stages.size) { i -> stages[i]() } }
        applied = p
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 asks for output before any input arrives, and hands over its
        // shared empty buffer to do it. Until this processor first allocates,
        // that is also its own output buffer, and copying it into itself
        // throws. Nothing to process either way.
        if (!inputBuffer.hasRemaining()) return
        val p = params
        if (p !== applied) rebuild(p)
        val size = inputBuffer.remaining()
        // The song's loudness for the on-screen lights, read without moving the buffer.
        if (com.opentune.playback.AudioLevels.listening && channels > 0) {
            val view = inputBuffer.duplicate().order(inputBuffer.order())
            val base = view.position()
            val width = if (isFloat) 4 else 2
            com.opentune.playback.AudioLevels.measure(size / (channels * width), channels, sampleRate) { i ->
                if (isFloat) view.getFloat(base + i * 4) else view.getShort(base + i * 2) / 32768f
            }
        }
        val out = replaceOutputBuffer(size)
        if (silenced) {
            // Casting: the phone keeps time for the queue while another device makes the sound.
            repeat(size) { out.put(0) }
            inputBuffer.position(inputBuffer.limit())
            out.flip()
            return
        }
        if (p.isNeutral || channels == 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val bytesPerFrame = channels * if (isFloat) 4 else 2
        val frames = size / bytesPerFrame
        val pre = p.preampGain
        val balance = p.balance
        val leftGain = if (balance > 0) 1 - balance else 1f
        val rightGain = if (balance < 0) 1 + balance else 1f
        val limit = p.canBoost
        repeat(frames) {
            for (c in 0 until channels) {
                frame[c] = if (isFloat) inputBuffer.getFloat() else inputBuffer.getShort() / 32768f
            }
            processFrame(p, pre, leftGain, rightGain, limit)
            for (c in 0 until channels) {
                val s = frame[c]
                if (isFloat) out.putFloat(s) else out.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        // Trailing partial frame, if a buffer ever ends mid-frame.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun processFrame(p: DspParams, pre: Float, leftGain: Float, rightGain: Float, limit: Boolean) {
        for (c in 0 until channels) {
            var x = (frame[c] * pre).toDouble()
            for (f in filters[c]) x = f.process(x)
            frame[c] = x.toFloat()
        }
        if (channels == 2) {
            if (p.spatial) {
                val mid = (frame[0] + frame[1]) * 0.5f
                val side = (frame[0] - frame[1]) * 0.5f * SPATIAL_WIDTH
                frame[0] = mid + side
                frame[1] = mid - side
            }
            frame[0] *= leftGain
            frame[1] *= rightGain
        }
        if (limit) for (c in 0 until channels) frame[c] = softLimit(frame[c])
    }

    override fun onFlush() {
        filters.forEach { ch -> ch.forEach { it.reset() } }
        com.opentune.playback.AudioLevels.flush()
    }

    override fun onReset() {
        filters = emptyArray()
        applied = null
    }

    companion object {
        /** While true, every processor puts out silence; set while casting. */
        @Volatile var silenced = false
        const val SPATIAL_WIDTH = 1.6f
        private const val KNEE = 0.85f
        /** Never quite full scale, so the output can't clip after conversion. */
        const val CEILING = 0.98f

        /** Transparent below the knee, then rounds peaks toward [CEILING] instead of clipping them. */
        fun softLimit(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            return sign(x) * (KNEE + (CEILING - KNEE) * tanh(((a - KNEE) / (CEILING - KNEE)).toDouble()).toFloat())
        }
    }
}
