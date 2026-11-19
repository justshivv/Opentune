package com.opentune.desktop

import com.opentune.data.settings.AppSettings
import java.nio.FloatBuffer
import kotlin.math.roundToInt
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber

/** Whether FFmpeg's libraries load on this computer; every song plays through them. */
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
 * A song decoded by FFmpeg onto the [AudioBus]: YouTube's Opus or AAC, JioSaavn's AAC, a
 * server's own file, an add-on's FLAC. Whatever it is, it comes out as stereo floats at the
 * bus's rate, through the equalizer, balance and speed as FFmpeg filters, into a [Voice].
 *
 * [queued] makes it ready without being heard, for the song after this one: decoded ahead
 * into its voice, which the bus starts at the exact end of the one before ([AudioBus.chain])
 * or fades in ([AudioBus.crossfade]).
 *
 * Its callbacks: [onReady] with the length once it's known, [onStarted] at its first sound
 * decoded, [onPlaying] as it plays and pauses, [onEnd] once it's all been heard, and
 * [onError] when it can't.
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
    /** Request headers, for a source that needs them (an add-on's file server). */
    private val headers: Map<String, String> = emptyMap(),
    queued: Boolean = false,
) : Engine {
    /** Where its sound goes on the bus. */
    val voice: Voice = AudioBus.voice(queued)
    private val rate = voice.rate
    @Volatile private var disposed = false
    @Volatile private var seekRequest: Long? = null
    @Volatile private var seekingTo: Long? = null
    @Volatile private var started = false
    @Volatile private var duration = 0L
    /** Where the current run started, in frames of the song, and the voice's position then. */
    @Volatile private var runStart = 0L
    @Volatile private var lineBase = 0L
    /** Played frames per second of the song: above one when sped up. */
    @Volatile private var speed = 1.0
    /** Set when the sound settings change, so the filters are built again with them. */
    @Volatile private var effectsChanged = false

    init {
        if (autoplay && !queued) AudioBus.resume()
    }

    private val player = Thread({ run() }, "ffmpeg-play").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

    private fun run() {
        var grabber: FFmpegFrameGrabber? = null
        var filter: FFmpegFrameFilter? = null
        try {
            if (!Ffmpeg.available) throw IllegalStateException("FFmpeg isn't available")
            val g = FFmpegFrameGrabber(address).also { grabber = it }
            g.sampleMode = FrameGrabber.SampleMode.FLOAT
            g.sampleRate = rate
            g.audioChannels = AudioBus.CHANNELS
            if (address.startsWith("http")) {
                g.setOption("reconnect", "1")
                g.setOption("reconnect_streamed", "1")
            }
            if (headers.isNotEmpty()) g.setOption("headers", headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" })
            g.start()
            duration = (g.lengthInTime / 1000).coerceAtLeast(0)
            onReady(duration)
            filter = effects()
            val first = startAtMs().takeIf { it > 500 }
            if (first != null) g.setAudioTimestamp(first * 1000)
            var at = msToFrame(first ?: 0)
            var emitting = false
            var floats = FloatArray(0)
            while (!disposed) {
                if (effectsChanged) {
                    effectsChanged = false
                    filter?.let { f -> runCatching { f.stop(); f.release() } }
                    filter = effects()
                }
                seekRequest?.let { request ->
                    seekRequest = null
                    g.setAudioTimestamp(request.coerceAtLeast(0) * 1000)
                    filter?.let { f -> runCatching { f.stop(); f.release() }; filter = effects() }
                    at = msToFrame(request)
                    emitting = false
                }
                val frame: Frame = g.grabSamples() ?: break
                val pieces = filter?.let { f -> f.push(frame); generateSequence { f.pullSamples() }.toList() } ?: listOf(frame)
                for (piece in pieces) {
                    val samples = piece.samples?.firstOrNull() as? FloatBuffer ?: continue
                    val count = samples.remaining()
                    if (count < AudioBus.CHANNELS) continue
                    if (!emitting) {
                        emitting = true
                        runStart = at
                        lineBase = voice.mixed
                        seekingTo = null
                    }
                    if (floats.size < count) floats = FloatArray(count)
                    samples.get(floats, 0, count)
                    val frames = count / AudioBus.CHANNELS
                    at += (frames * speed).toLong()
                    if (!voice.write(floats, 0, frames)) break
                    if (!started) {
                        started = true
                        onStarted()
                        onPlaying(true)
                    }
                }
            }
            if (!disposed && seekRequest == null) {
                voice.endOfInput()
                voice.awaitEnd()
                if (!disposed && voice.state == Voice.State.DONE) onEnd()
            }
        } catch (e: Exception) {
            if (!disposed) onError(e.message ?: "FFmpeg couldn't play it")
        } finally {
            runCatching { filter?.stop(); filter?.release() }
            runCatching { grabber?.stop(); grabber?.release() }
        }
    }

    /**
     * The phone's sound settings as FFmpeg filters, or null when they'd change
     * nothing, so a plain song goes straight through.
     */
    private fun effects(): FFmpegFrameFilter? {
        val chain = filterChain(AppSettings.sound.value.speed, AppSettings.equalizer.value)
        speed = AppSettings.sound.value.speed.toDouble().coerceIn(0.5, 2.0)
        if (chain == null) return null
        return FFmpegFrameFilter(chain, AudioBus.CHANNELS).apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            sampleRate = rate
            start()
        }
    }

    private fun msToFrame(ms: Long): Long = ms.coerceAtLeast(0) * rate / 1000

    override fun play() {
        AudioBus.resume()
        if (started) onPlaying(true)
    }

    override fun pause() {
        AudioBus.pause()
        if (started) onPlaying(false)
    }

    override fun seek(ms: Long) {
        val to = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        seekingTo = to
        seekRequest = to
        voice.flush()
    }

    override fun positionMs(): Long {
        if (!started) return startAtMs()
        seekingTo?.let { return it }
        val frames = runStart + ((voice.framePosition - lineBase).coerceAtLeast(0) * speed).toLong()
        return frames * 1000 / rate
    }

    override fun durationMs(): Long = duration

    /** The equalizer or speed changed in Settings: the song carries on with the new ones. */
    fun refreshEffects() {
        effectsChanged = true
    }

    /** The bus applies the volume to everything it plays (AudioBus.volume). */
    override fun setVolume(v: Double) = Unit

    override fun dispose() {
        disposed = true
        voice.close()
    }

    internal companion object {
        /**
         * The FFmpeg filter chain for [speed] and the equalizer [eq]: the preamp, a
         * peaking filter two-thirds of an octave wide per band, bass and treble as
         * shelves, a headphone curve's own filters, balance as a pan, and tempo
         * without a change of pitch. Boosts are taken back off the input first, as the
         * phone does, so the curve can't clip. Null when none of it would change a thing.
         */
        fun filterChain(speed: Float, eq: com.opentune.data.settings.EqualizerSettings): String? {
            val parts = mutableListOf<String>()
            fun f(v: Float) = "%.2f".format(java.util.Locale.ROOT, v)
            if (eq.enabled) {
                val bands = com.opentune.data.settings.EQ_BANDS_HZ.mapIndexed { i, hz -> hz to (eq.bands.getOrNull(i) ?: 0f) }
                val headphone = eq.headphone
                // The biggest lift anywhere, taken off the input with the preamp.
                val lift = (bands.map { it.second } + eq.bassDb + eq.trebleDb + (headphone?.filters?.map { it.gainDb } ?: emptyList()))
                    .maxOrNull()?.coerceAtLeast(0f) ?: 0f
                val pre = eq.preampDb + (headphone?.preampDb ?: 0f) - lift
                if (kotlin.math.abs(pre) >= 0.05f) parts += "volume=${f(pre)}dB"
                bands.forEach { (hz, g) ->
                    if (kotlin.math.abs(g) >= 0.05f) parts += "equalizer=f=${hz.roundToInt()}:t=o:w=0.667:g=${f(g)}"
                }
                if (kotlin.math.abs(eq.bassDb) >= 0.05f) parts += "lowshelf=f=120:t=q:w=0.707:g=${f(eq.bassDb)}"
                if (kotlin.math.abs(eq.trebleDb) >= 0.05f) parts += "highshelf=f=8000:t=q:w=0.707:g=${f(eq.trebleDb)}"
                headphone?.filters?.forEach { p ->
                    if (kotlin.math.abs(p.gainDb) < 0.05f) return@forEach
                    val q = p.q.coerceIn(0.1f, 20f)
                    val hz = p.freqHz.roundToInt().coerceIn(10, 22_000)
                    parts += when (p.type) {
                        com.opentune.data.settings.FilterType.PEAK -> "equalizer=f=$hz:t=q:w=${f(q)}:g=${f(p.gainDb)}"
                        com.opentune.data.settings.FilterType.LOW_SHELF -> "lowshelf=f=$hz:t=q:w=${f(q)}:g=${f(p.gainDb)}"
                        com.opentune.data.settings.FilterType.HIGH_SHELF -> "highshelf=f=$hz:t=q:w=${f(q)}:g=${f(p.gainDb)}"
                    }
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
