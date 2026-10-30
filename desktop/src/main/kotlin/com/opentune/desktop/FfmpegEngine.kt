package com.opentune.desktop

import com.opentune.data.settings.AppSettings
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import kotlin.math.log10
import kotlin.math.roundToInt
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber

/** Whether FFmpeg's libraries load on this computer; the engine below needs them. */
internal object Ffmpeg {
    val available: Boolean by lazy {
        runCatching {
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            FFmpegLogCallback.set()
        }.onFailure { System.err.println("FFmpeg: not available: $it") }.isSuccess
    }
}

/**
 * A song decoded by FFmpeg and sent to the sound card, for what JavaFX can't
 * play: YouTube's Opus stream, the best it has, and any other format a source
 * gives. The equalizer, bass and treble, balance and speed go through FFmpeg's
 * audio filters, as JavaFX applies them on its own streams.
 *
 * Its callbacks follow the JavaFX engine's: [onReady] with the length once
 * it's known, [onStarted] at the first sound, [onPlaying] as it plays and
 * pauses, [onEnd] at the end and [onError] when it can't.
 */
internal class FfmpegEngine(
    private val address: String,
    private val startAtMs: () -> Long,
    autoplay: Boolean,
    private val onReady: (Long) -> Unit,
    private val onStarted: () -> Unit,
    private val onPlaying: (Boolean) -> Unit,
    private val onEnd: () -> Unit,
    private val onError: (String?) -> Unit,
    private val out: PcmOut = SoundCard(),
) : Engine {
    private val lock = Object()
    @Volatile private var disposed = false
    @Volatile private var paused = !autoplay
    @Volatile private var seekRequest: Long? = null
    @Volatile private var seekingTo: Long? = null
    @Volatile private var started = false
    @Volatile private var rate = 0
    @Volatile private var duration = 0L
    /** Where the current run started, in frames, and the line's position then. */
    @Volatile private var runStart = 0L
    @Volatile private var lineBase = 0L
    @Volatile private var volume = 1.0
    @Volatile private var softGain = 1.0
    /** Played frames per second of the song: above one when sped up. */
    @Volatile private var speed = 1.0
    /** Set when the sound settings change, so the filters are built again with them. */
    @Volatile private var effectsChanged = false

    private val player = Thread({ run() }, "ffmpeg-play").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

    private fun run() {
        var grabber: FFmpegFrameGrabber? = null
        var filter: FFmpegFrameFilter? = null
        try {
            if (!Ffmpeg.available) throw IllegalStateException("FFmpeg isn't available")
            val g = FFmpegFrameGrabber(address).also { grabber = it }
            g.sampleMode = FrameGrabber.SampleMode.SHORT
            g.audioChannels = 2
            g.setOption("reconnect", "1")
            g.setOption("reconnect_streamed", "1")
            g.start()
            rate = g.sampleRate.takeIf { it > 0 } ?: 48_000
            duration = (g.lengthInTime / 1000).coerceAtLeast(0)
            onReady(duration)
            val opened = out.open(AudioFormat(rate.toFloat(), 16, 2, true, false))
            if (opened.sampleSizeInBits != 16) throw IllegalStateException("the sound card won't take 16-bit")
            applyVolume()
            if (!paused) out.start()
            filter = effects(rate)
            val first = startAtMs().takeIf { it > 500 }
            if (first != null) seekTo(g, first)
            var at = msToFrame(first ?: 0)
            var emitting = false
            var bytes = ByteArray(0)
            while (!disposed) {
                if (effectsChanged) {
                    effectsChanged = false
                    filter?.let { f -> runCatching { f.stop(); f.release() } }
                    filter = effects(rate)
                }
                seekRequest?.let { request ->
                    seekRequest = null
                    out.flush()
                    seekTo(g, request)
                    filter?.let { f -> runCatching { f.stop(); f.release() }; filter = effects(rate) }
                    at = msToFrame(request)
                    emitting = false
                }
                val frame: Frame = g.grabSamples() ?: break
                val pieces = filter?.let { f -> f.push(frame); generateSequence { f.pullSamples() }.toList() } ?: listOf(frame)
                for (piece in pieces) {
                    val samples = piece.samples?.firstOrNull() as? ShortBuffer ?: continue
                    val count = samples.remaining()
                    if (count == 0) continue
                    if (!emitting) {
                        emitting = true
                        out.flush()
                        runStart = at
                        lineBase = out.framePosition
                        seekingTo = null
                    }
                    if (bytes.size < count * 2) bytes = ByteArray(count * 2)
                    val gain = softGain
                    for (i in 0 until count) {
                        val s = if (gain == 1.0) samples.get(i).toInt() else (samples.get(i) * gain).roundToInt().coerceIn(-32768, 32767)
                        bytes[i * 2] = s.toByte()
                        bytes[i * 2 + 1] = (s shr 8).toByte()
                    }
                    at += (count / 2 * speed).toLong()
                    if (!write(bytes, count * 2)) break
                    if (!started) {
                        started = true
                        onStarted()
                        onPlaying(!paused)
                    }
                }
            }
            if (!disposed) {
                out.drain()
                if (!disposed) onEnd()
            }
        } catch (e: Exception) {
            if (!disposed) onError(e.message ?: "FFmpeg couldn't play it")
        } finally {
            runCatching { filter?.stop(); filter?.release() }
            runCatching { grabber?.stop(); grabber?.release() }
            out.close()
        }
    }

    private fun seekTo(g: FFmpegFrameGrabber, ms: Long) {
        g.setAudioTimestamp(ms.coerceAtLeast(0) * 1000)
    }

    /**
     * The phone's sound settings as FFmpeg filters, or null when they'd change
     * nothing, so a plain song goes straight through.
     */
    private fun effects(rate: Int): FFmpegFrameFilter? {
        val chain = filterChain(AppSettings.sound.value.speed, AppSettings.equalizer.value)
        speed = AppSettings.sound.value.speed.toDouble().coerceIn(0.5, 2.0)
        if (chain == null) return null
        return FFmpegFrameFilter(chain, 2).apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            sampleRate = rate
            start()
        }
    }

    /** Writes it all, waiting through a pause; false when a seek or the end cut it short. */
    private fun write(bytes: ByteArray, len: Int): Boolean {
        var at = 0
        var left = len
        while (left > 0) {
            if (disposed || seekRequest != null) return false
            if (paused) {
                synchronized(lock) { while (paused && !disposed && seekRequest == null) lock.wait(200) }
                continue
            }
            val n = out.write(bytes, at, left)
            at += n
            left -= n
        }
        return true
    }

    private fun msToFrame(ms: Long): Long = ms.coerceAtLeast(0) * rate / 1000

    override fun play() {
        paused = false
        if (rate > 0) out.start()
        synchronized(lock) { lock.notifyAll() }
        if (started) onPlaying(true)
    }

    override fun pause() {
        paused = true
        out.stop()
        if (started) onPlaying(false)
    }

    override fun seek(ms: Long) {
        if (rate == 0) return
        val to = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        seekingTo = to
        seekRequest = to
        out.flush()
        synchronized(lock) { lock.notifyAll() }
    }

    override fun positionMs(): Long {
        if (rate == 0 || !started) return startAtMs()
        seekingTo?.let { return it }
        val frames = runStart + ((out.framePosition - lineBase) * speed).toLong()
        return frames * 1000 / rate
    }

    override fun durationMs(): Long = duration

    /** The equalizer or speed changed in Settings: the song carries on with the new ones. */
    fun refreshEffects() {
        effectsChanged = true
    }

    override fun setVolume(v: Double) {
        volume = v.coerceIn(0.0, 1.0)
        applyVolume()
    }

    /** The line's own gain where it has one; else the samples are scaled. */
    private fun applyVolume() {
        if (rate == 0) return
        val db = if (volume <= 0.0) -80f else (20 * log10(volume)).toFloat()
        softGain = if (out.setGainDb(db)) 1.0 else volume
    }

    override fun dispose() {
        disposed = true
        out.stop()
        out.flush()
        synchronized(lock) { lock.notifyAll() }
    }

    internal companion object {
        /**
         * The FFmpeg filter chain for [speed] and the equalizer [eq]: a peaking
         * filter two-thirds of an octave wide per band (lowered by the biggest
         * boost so it can't clip, as the phone does), bass and treble on the
         * bands below 120 Hz and above 8 kHz, balance as a pan, and tempo
         * without a change of pitch. Null when none of it would change a thing.
         */
        fun filterChain(speed: Float, eq: com.opentune.data.settings.EqualizerSettings): String? {
            val parts = mutableListOf<String>()
            if (eq.enabled) {
                val gains = com.opentune.data.settings.EQ_BANDS_HZ.mapIndexed { i, hz ->
                    (eq.bands.getOrNull(i) ?: 0f) + eq.preampDb +
                        (if (hz <= 120f) eq.bassDb else 0f) + (if (hz >= 8_000f) eq.trebleDb else 0f)
                }
                val headroom = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
                com.opentune.data.settings.EQ_BANDS_HZ.forEachIndexed { i, hz ->
                    val g = gains[i] - headroom
                    if (kotlin.math.abs(g) >= 0.05f) parts += "equalizer=f=${hz.roundToInt()}:t=o:w=0.667:g=${"%.2f".format(java.util.Locale.ROOT, g)}"
                }
                val balance = eq.balance.coerceIn(-1f, 1f)
                if (kotlin.math.abs(balance) >= 0.01f) {
                    val left = if (balance > 0) 1f - balance else 1f
                    val right = if (balance < 0) 1f + balance else 1f
                    parts += "pan=stereo|c0=${"%.3f".format(java.util.Locale.ROOT, left)}*c0|c1=${"%.3f".format(java.util.Locale.ROOT, right)}*c1"
                }
            }
            val tempo = speed.coerceIn(0.5f, 2f)
            if (kotlin.math.abs(tempo - 1f) >= 0.01f) parts += "atempo=${"%.3f".format(java.util.Locale.ROOT, tempo)}"
            return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
        }
    }
}
