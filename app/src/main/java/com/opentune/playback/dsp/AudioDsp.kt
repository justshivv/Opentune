package com.opentune.playback.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.settings.EqualizerSettings
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

        fun lowShelf(sampleRate: Double, freq: Double, gainDb: Double): Biquad = shelf(sampleRate, freq, gainDb, low = true)

        fun highShelf(sampleRate: Double, freq: Double, gainDb: Double): Biquad = shelf(sampleRate, freq, gainDb, low = false)

        private fun shelf(sampleRate: Double, freq: Double, gainDb: Double, low: Boolean): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * freq / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / 2 * sqrt(2.0) // shelf slope S = 1
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
) {
    private val eqActive: Boolean
        get() = equalizer.enabled && (
            equalizer.bands.any { it != 0f } || equalizer.preampDb != 0f ||
                equalizer.bassDb != 0f || equalizer.trebleDb != 0f || equalizer.balance != 0f
            )

    val isNeutral: Boolean get() = !eqActive && bassBoost == 0 && !spatial

    /**
     * Whether anything in the chain can push a sample past full scale. Only
     * then does the soft limiter run; otherwise peaks are left exactly as
     * mastered.
     */
    val canBoost: Boolean
        get() = spatial || bassBoost > 0 || (eqActive && (
            equalizer.preampDb > 0 || equalizer.bassDb > 0 || equalizer.trebleDb > 0 || equalizer.bands.any { it > 0 }
            ))

    /** The filter stages to run, given the sample rate. */
    fun stages(sampleRate: Int): List<() -> Biquad> {
        val fs = sampleRate.toDouble()
        val nyquistSafe = fs * 0.45
        val out = mutableListOf<() -> Biquad>()
        if (eqActive) {
            equalizer.bands.forEachIndexed { i, gain ->
                val f = EQ_BANDS_HZ.getOrNull(i)?.toDouble() ?: return@forEachIndexed
                if (gain != 0f && f < nyquistSafe) out += { Biquad.peaking(fs, f, BAND_Q, gain.toDouble()) }
            }
            if (equalizer.bassDb != 0f) out += { Biquad.lowShelf(fs, 120.0, equalizer.bassDb.toDouble()) }
            if (equalizer.trebleDb != 0f && 8_000.0 < nyquistSafe) out += { Biquad.highShelf(fs, 8_000.0, equalizer.trebleDb.toDouble()) }
        }
        if (bassBoost > 0) out += { Biquad.lowShelf(fs, 100.0, bassBoost / 1000.0 * MAX_BASS_BOOST_DB) }
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
            }
            val headroom = boosts.maxOrNull()?.coerceAtLeast(0f) ?: 0f
            val user = if (eqActive) equalizer.preampDb else 0f
            return dbToGain(user - headroom)
        }
    val balance: Float get() = if (equalizer.enabled) equalizer.balance.coerceIn(-1f, 1f) else 0f

    companion object {
        const val BAND_Q = 1.1
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
        val p = params
        if (p !== applied) rebuild(p)
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
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
    }

    override fun onReset() {
        filters = emptyArray()
        applied = null
    }

    companion object {
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
