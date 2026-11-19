package com.opentune.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Where the bus plays: interleaved stereo floats at [rate]. The native output, or
 * Java Sound where that won't load.
 */
internal interface BusDevice {
    val rate: Int
    /** Said in Settings: "WASAPI, shared, 48 kHz" and the like. */
    val description: String
    /** Takes up to [frames] frames from [off] (in samples); how many it took. */
    fun write(buf: FloatArray, off: Int, frames: Int): Int
    /** Frames written and not yet heard. */
    val latencyFrames: Int
    fun start()
    fun stop()
    fun flush()
    fun close()
    /** The system stopped it on its own: unplugged, or taken. */
    val lost: Boolean
}

/** The bus on miniaudio: shared mode, the device's own rate, floats all the way. */
internal class NativeBusDevice(private val out: NativeOutput) : BusDevice {
    override val rate = out.rate
    override val description = "${NativeAudio.backend}, ${if (out.exclusive) "exclusive" else "shared"}, ${out.rate / 1000.0} kHz".replace(".0 kHz", " kHz")
    override fun write(buf: FloatArray, off: Int, frames: Int) = out.offer(buf, off, frames)
    // The ring and about a period inside the device.
    override val latencyFrames: Int get() = out.queued + rate / 100
    override fun start() = out.start()
    override fun stop() = out.stop()
    override fun flush() = out.flush()
    override fun close() = out.close()
    override val lost: Boolean get() = out.lost
}

/** The bus on Java Sound, 16-bit, where the native output isn't there. */
internal class JavaSoundBusDevice(device: String, wantRate: Int = 48_000) : BusDevice {
    private val line: SourceDataLine
    override val rate: Int
    private var bytes = ByteArray(0)

    init {
        var opened: Pair<SourceDataLine, Int>? = null
        var last: Exception? = null
        for (r in listOf(wantRate, 44_100).distinct()) {
            try {
                val format = AudioFormat(r.toFloat(), 16, 2, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val mixer = device.takeIf { it.isNotBlank() }?.let { name ->
                    AudioSystem.getMixerInfo().firstOrNull { it.name == name }?.let(AudioSystem::getMixer)?.takeIf { it.isLineSupported(info) }
                }
                val l = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as SourceDataLine
                l.open(format, r * 4 / 5)
                opened = l to r
                break
            } catch (e: Exception) {
                last = e
            }
        }
        val (l, r) = opened ?: throw java.io.IOException("The sound card won't open: ${last?.message}")
        line = l
        rate = r
    }

    override val description = "Java Sound, ${rate / 1000.0} kHz".replace(".0 kHz", " kHz")

    override fun write(buf: FloatArray, off: Int, frames: Int): Int {
        val n = frames * 2
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (buf[off + i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = s.toByte()
            bytes[i * 2 + 1] = (s shr 8).toByte()
        }
        // Java Sound blocks until it's taken it all.
        return line.write(bytes, 0, n * 2) / 4
    }

    override val latencyFrames: Int get() = (line.bufferSize - line.available()) / 4
    override fun start() = line.start()
    override fun stop() = line.stop()
    override fun flush() = line.flush()
    override fun close() = line.close()
    override val lost = false
}

/**
 * Every song plays through here: one output, opened once at the device's own rate, and
 * a mixer that sums the songs on it in floats. That is what lets one song follow another
 * with no gap at all ([chain]), or fade into it ([crossfade]), whatever each came from:
 * each song is decoded and resampled to [rate] into a [Voice] of its own.
 *
 * The master volume, the sleep timer's fade and each song's loudness are applied here, on
 * the float mix, with a soft limiter above full scale; at full volume with nothing to apply,
 * samples go through as they came.
 */
internal object AudioBus {
    const val CHANNELS = 2
    /** Frames mixed at a time: 10 ms at 48 kHz. */
    private const val CHUNK = 480

    private val lock = Object()
    @Volatile private var device: BusDevice? = null
    private val voices = CopyOnWriteArrayList<Voice>()
    @Volatile private var paused = true
    @Volatile private var thread: Thread? = null
    @Volatile var volume = 1f
    /** Counts chunks handed to the device, so a voice can tell when its last one has gone. */
    @Volatile internal var cycle = 0L
        private set
    /** Opens the device in tests and pictures without sound: a stand-in. */
    @Volatile internal var deviceFactory: (() -> BusDevice)? = null

    /** Called (off the mixer thread) when the device went away and the bus opened another. */
    @Volatile var onDeviceChanged: (() -> Unit)? = null

    /** The output open now, for Settings; null when nothing has played yet. */
    val description: String? get() = device?.description

    /** The rate everything is resampled to: the device's own. Opens the device if need be. */
    val rate: Int get() = ensureOpen().rate

    private fun ensureOpen(): BusDevice = synchronized(lock) {
        device ?: openDevice().also {
            device = it
            if (!paused) it.start()
            if (thread == null) thread = Thread(::mix, "audio-bus").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        }
    }

    private fun openDevice(): BusDevice {
        deviceFactory?.let { return it() }
        val chosen = AudioDevices.selected.value
        if (NativeAudio.available) {
            // The device's own rate and format; miniaudio converts floats to it.
            runCatching { return NativeBusDevice(NativeAudio.open(chosen, NativeAudio.Format.F32, 0, CHANNELS, exclusive = false, ringMs = 120)) }
                .onFailure { System.err.println("[audio] ${chosen.ifBlank { "default device" }}: ${it.message}") }
            if (chosen.isNotBlank()) runCatching { return NativeBusDevice(NativeAudio.open("", NativeAudio.Format.F32, 0, CHANNELS, false, 120)) }
        }
        return JavaSoundBusDevice(chosen)
    }

    /** A new voice for a song. [queued] waits for [chain] or [crossfade] to bring it in. */
    fun voice(queued: Boolean = false): Voice {
        val d = ensureOpen()
        val v = Voice(d.rate, if (queued) Voice.State.QUEUED else Voice.State.PLAYING)
        voices += v
        wake()
        return v
    }

    /** [to] starts at the very sample [from] ends on. */
    fun chain(from: Voice, to: Voice) {
        from.then = to
        wake()
    }

    /**
     * [to] comes in over [frames] as [from] goes out, on equal-power curves, starting [after]
     * frames of [from] from now (to the nearest 10 ms), so the fade lands where it's meant to.
     */
    fun crossfade(from: Voice, to: Voice, frames: Int, after: Int = 0) {
        from.then = null
        val length = frames.coerceAtLeast(1)
        from.ramp(Voice.Ramp(from.mixed + after.coerceAtLeast(0), length, out = true))
        to.ramp(Voice.Ramp(0, length, out = false))
        from.fadeInto = to
        wake()
    }

    fun pause() {
        paused = true
        device?.stop()
    }

    fun resume() {
        paused = false
        ensureOpen().start()
        wake()
    }

    /**
     * Closes the device, for an output that needs it alone (bit-perfect FLAC) or another
     * chosen in Settings; the next voice opens it again.
     */
    fun release() = synchronized(lock) {
        device?.let { runCatching { it.stop(); it.close() } }
        device = null
    }

    internal fun remove(v: Voice) {
        voices -= v
    }

    /** Drops what the device holds when [v] is all that's playing, so a seek is heard at once. */
    internal fun flushFor(v: Voice) {
        if (voices.none { it !== v && it.state == Voice.State.PLAYING }) device?.flush()
    }

    internal val latency: Int get() = device?.latencyFrames ?: 0

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun mix() {
        val buf = FloatArray(CHUNK * CHANNELS)
        while (true) {
            val d = device
            if (d == null || paused) {
                synchronized(lock) { lock.wait(50) }
                continue
            }
            if (d.lost) {
                System.err.println("[audio] the device stopped; opening the output again")
                release()
                ensureOpen()
                onDeviceChanged?.let { cb -> Thread(cb, "audio-reopen").start() }
                continue
            }
            buf.fill(0f)
            var frames = 0
            // A crossfade whose time has come brings its next song in.
            for (v in voices) v.fadeInto?.let { next ->
                if (v.fadeDue) {
                    v.fadeInto = null
                    if (next.state == Voice.State.QUEUED) {
                        next.state = Voice.State.PLAYING
                        next.onFadeIn?.let { cb -> Thread(cb, "audio-fade-in").start() }
                    }
                }
            }
            // Taken before mixing: a voice a chain starts in this chunk is mixed by the one it follows.
            val playing = voices.filter { it.state == Voice.State.PLAYING }
            for (v in playing) frames = maxOf(frames, v.mixInto(buf, 0, CHUNK))
            if (frames == 0) {
                // Nothing to play yet (a song loading, or between songs): wait for it.
                synchronized(lock) { lock.wait(5) }
                continue
            }
            finish(buf, frames * CHANNELS, volume)
            var at = 0
            while (at < frames && device === d) {
                val n = d.write(buf, at * CHANNELS, frames - at)
                at += n
                if (n == 0) {
                    if (paused) synchronized(lock) { lock.wait(20) } else LockSupport.parkNanos(2_000_000)
                }
            }
            cycle++
        }
    }

    /** The master volume, then a soft limit above full scale so loud songs and boosts don't clip. */
    internal fun finish(buf: FloatArray, samples: Int, volume: Float) {
        for (i in 0 until samples) {
            var s = buf[i] * volume
            val a = abs(s)
            if (a > LIMIT) s = (if (s < 0) -1f else 1f) * (LIMIT + (1f - LIMIT) * tanh((a - LIMIT) / (1f - LIMIT)))
            buf[i] = s
        }
    }

    /** Where the limiter starts bending. Below it, samples pass unchanged. */
    private const val LIMIT = 0.95f

    /** For tests: back to no device and no voices. */
    internal fun reset() {
        voices.forEach { it.close() }
        voices.clear()
        release()
        paused = true
        volume = 1f
    }
}

/**
 * One song on the [AudioBus]: its decoder writes stereo floats at the bus's rate in, the
 * mixer takes them out. Holds about two seconds, so a decoder runs that far ahead and a
 * queued song is ready the moment the one before it ends.
 */
internal class Voice(val rate: Int, @Volatile var state: State) {
    enum class State { QUEUED, PLAYING, DONE, CLOSED }

    /** A fade: from [startFrame] (in frames mixed) over [length] frames, in or [out]. */
    data class Ramp(val startFrame: Long, val length: Int, val out: Boolean)

    private val lock = Object()
    private val ring = FloatArray(rate * 2 * AudioBus.CHANNELS)
    private var readAt = 0L
    private var writeAt = 0L
    @Volatile private var ended = false
    /** Counts flushes, so a write waiting for room gives up when one comes. */
    @Volatile private var flushes = 0
    @Volatile private var ramp: Ramp? = null
    /** The song that starts when this one ends, sample for sample. */
    @Volatile var then: Voice? = null
    /** The song a crossfade brings in when this one's fade-out starts. */
    @Volatile var fadeInto: Voice? = null
    /** Whether this voice's fade-out has begun (or it has nothing left to fade). */
    internal val fadeDue: Boolean get() = ramp?.let { it.out && mixed >= it.startFrame } ?: false || state != State.PLAYING
    /** Linear gain for the song's loudness, from Player. */
    @Volatile var gain = 1f
    /** Frames the mixer has taken. */
    @Volatile var mixed = 0L
        private set
    /** The bus's chunk count when this voice finished: done once the bus has moved past it. */
    @Volatile private var doneCycle = Long.MAX_VALUE

    /** Called once this voice has faded all the way out. */
    @Volatile var onFadedOut: (() -> Unit)? = null

    /** Called once a crossfade has started bringing this voice in. */
    @Volatile var onFadeIn: (() -> Unit)? = null

    fun ramp(r: Ramp?) { ramp = r }

    /** Frames heard so far: what was mixed, less what still waits in the device. */
    val framePosition: Long get() = (mixed - if (state == State.PLAYING) AudioBus.latency else 0).coerceAtLeast(0)

    /** Frames written and not yet mixed. */
    val buffered: Int get() = synchronized(lock) { (writeAt - readAt).toInt() / AudioBus.CHANNELS }

    /** Whether a queued voice has a good start waiting: half a second, or all of a short song. */
    val primed: Boolean get() = buffered >= rate / 2 || ended

    /**
     * Writes [frames] frames from [off] (in samples), waiting for room; false when a
     * flush or close cut it short.
     */
    fun write(samples: FloatArray, off: Int, frames: Int): Boolean {
        val mine = flushes
        var at = off
        var left = frames * AudioBus.CHANNELS
        synchronized(lock) {
            while (left > 0) {
                if (state == State.CLOSED || flushes != mine) return false
                val room = ring.size - (writeAt - readAt).toInt()
                if (room == 0) { lock.wait(20); continue }
                val n = minOf(room, left)
                for (i in 0 until n) ring[((writeAt + i) % ring.size).toInt()] = samples[at + i]
                writeAt += n
                at += n
                left -= n
            }
        }
        return true
    }

    /** No more is coming: once what's here is played, the voice is done (and [then] starts). */
    fun endOfInput() {
        ended = true
    }

    /** Waits until everything written has been mixed and heard, or the voice is closed. */
    fun awaitEnd() {
        synchronized(lock) { while (state != State.DONE && state != State.CLOSED) lock.wait(50) }
        // The chunk it finished in, handed to the device; then what the device still holds, heard in a moment.
        if (state == State.DONE) {
            val handed = System.nanoTime() + 500_000_000L
            while (AudioBus.cycle <= doneCycle && System.nanoTime() < handed) LockSupport.parkNanos(1_000_000)
            val until = System.nanoTime() + 1_000_000_000L
            while (AudioBus.latency > 0 && System.nanoTime() < until && then?.state != State.PLAYING) LockSupport.parkNanos(5_000_000)
        }
    }

    /** Drops what's waiting (a seek), and what the device holds when nothing else is playing. */
    fun flush() {
        synchronized(lock) {
            readAt = writeAt
            ended = false
            flushes++
            lock.notifyAll()
        }
        AudioBus.flushFor(this)
    }

    /** Starts a voice made queued, on its own (no song before it to follow). */
    fun start() {
        if (state == State.QUEUED) state = State.PLAYING
    }

    fun close() {
        synchronized(lock) {
            state = State.CLOSED
            lock.notifyAll()
        }
        AudioBus.remove(this)
    }

    /**
     * Adds up to [frames] frames into [dst] from [off] (in frames); how many it gave. At its
     * end it hands the rest of the chunk to [then], so the next song starts on the next sample.
     */
    internal fun mixInto(dst: FloatArray, off: Int, frames: Int): Int {
        var done = 0
        synchronized(lock) {
            val have = ((writeAt - readAt) / AudioBus.CHANNELS).toInt()
            val n = minOf(have, frames)
            val r = ramp
            val g = gain
            for (f in 0 until n) {
                val e = g * envelope(r, mixed + f)
                val base = ((readAt + f * AudioBus.CHANNELS) % ring.size).toInt()
                val d = (off + f) * AudioBus.CHANNELS
                dst[d] += ring[base] * e
                dst[d + 1] += ring[(base + 1) % ring.size] * e
            }
            readAt += n.toLong() * AudioBus.CHANNELS
            mixed += n
            done = n
            if (n > 0) lock.notifyAll()
            if (r != null && r.out && mixed >= r.startFrame + r.length && state == State.PLAYING) {
                state = State.DONE
                lock.notifyAll()
                onFadedOut?.let { cb -> Thread(cb, "audio-faded").start() }
                return done
            }
            if (n < frames && ended && writeAt == readAt && state == State.PLAYING) {
                doneCycle = AudioBus.cycle
                state = State.DONE
                lock.notifyAll()
            } else {
                return done
            }
        }
        // Done: the next song takes the rest of this chunk, from its first sample.
        val next = then ?: return done
        if (next.state != State.QUEUED && next.state != State.PLAYING) return done
        next.state = State.PLAYING
        return done + next.mixInto(dst, off + done, frames - done)
    }

    private fun envelope(r: Ramp?, frame: Long): Float {
        r ?: return 1f
        val t = ((frame - r.startFrame).toDouble() / r.length).coerceIn(0.0, 1.0)
        return if (r.out) cos(t * PI / 2).toFloat() else sin(t * PI / 2).toFloat()
    }
}
