package com.opentune.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.locks.LockSupport

/** otaudio's functions (desktop/native/otaudio.c), as JNA calls them. */
@Suppress("FunctionName")
internal interface OtAudio : Library {
    fun ot_init(nullBackend: Int): Int
    fun ot_backend(): String
    fun ot_refresh_devices(): Int
    fun ot_device_name(i: Int): String
    fun ot_device_is_default(i: Int): Int
    fun ot_open(device: String?, format: Int, rate: Int, channels: Int, exclusive: Int, ringMs: Int, error: ByteArray, errorLen: Int): Pointer?
    fun ot_format(o: Pointer): Int
    fun ot_rate(o: Pointer): Int
    fun ot_channels(o: Pointer): Int
    fun ot_exclusive(o: Pointer): Int
    fun ot_write(o: Pointer, data: Pointer, frames: Int): Int
    fun ot_queued(o: Pointer): Int
    fun ot_room(o: Pointer): Int
    fun ot_start(o: Pointer): Int
    fun ot_stop(o: Pointer): Int
    fun ot_flush(o: Pointer)
    fun ot_flushing(o: Pointer): Int
    fun ot_played(o: Pointer): Long
    fun ot_underruns(o: Pointer): Long
    fun ot_lost(o: Pointer): Int
    fun ot_set_volume(o: Pointer, volume: Float)
    fun ot_close(o: Pointer)
}

/**
 * The sound output through miniaudio: WASAPI on Windows, Core Audio on macOS,
 * PulseAudio (and PipeWire through it) or ALSA on Linux. Null when the library
 * didn't build or won't load here; the app then plays through Java Sound.
 *
 * -Dopentune.audio=null uses miniaudio's silent backend, which plays in real
 * time to nothing: the tests' sound card.
 */
internal object NativeAudio {
    enum class Format(val code: Int, val bytes: Int) { F32(1, 4), S16(2, 2), S24(3, 3), S32(4, 4) }

    data class Device(val name: String, val isDefault: Boolean)

    val lib: OtAudio? by lazy {
        if (System.getProperty("opentune.audio") == "javasound") return@lazy null
        runCatching {
            val l = Native.load("otaudio", OtAudio::class.java)
            val r = l.ot_init(if (System.getProperty("opentune.audio") == "null") 1 else 0)
            check(r == 0) { "miniaudio didn't start ($r)" }
            System.err.println("[audio] miniaudio on ${l.ot_backend()}")
            l
        }.onFailure { System.err.println("[audio] native output not available, using Java Sound: ${it.message}") }.getOrNull()
    }

    val available: Boolean get() = lib != null

    /** "WASAPI", "Core Audio", "PulseAudio", "ALSA"…, or blank without the library. */
    val backend: String get() = lib?.ot_backend().orEmpty()

    /** Whether the backend can hold a device for this app alone. */
    val exclusiveSupported: Boolean get() = backend == "WASAPI" || backend == "ALSA"

    fun devices(): List<Device> {
        val l = lib ?: return emptyList()
        // Read under the same lock as the refresh: the names live in one native table another refresh rewrites.
        return synchronized(this) {
            val n = l.ot_refresh_devices()
            (0 until n).map { Device(l.ot_device_name(it), l.ot_device_is_default(it) != 0) }
        }
    }

    /**
     * Opens [device] (blank for the system's default) for [format] at [rate] and [channels];
     * 0 takes the device's own. Throws with the system's reason when it won't open.
     */
    fun open(device: String, format: Format?, rate: Int, channels: Int, exclusive: Boolean, ringMs: Int): NativeOutput {
        val l = lib ?: throw IllegalStateException("no native output")
        val error = ByteArray(256)
        // Under the device list's lock: looking a device up by name can read the list afresh.
        val handle = synchronized(this) { l.ot_open(device.ifBlank { null }, format?.code ?: 0, rate, channels, if (exclusive) 1 else 0, ringMs, error, error.size) }
            ?: throw java.io.IOException(String(error).substringBefore('\u0000').ifBlank { "the device won't open" })
        return NativeOutput(l, handle)
    }
}

/** One open output: a device with a ring buffer in front of it. */
internal class NativeOutput(private val l: OtAudio, private var handle: Pointer?) {
    private val h: Pointer get() = handle ?: throw IllegalStateException("closed")
    val format: NativeAudio.Format = NativeAudio.Format.entries.first { it.code == l.ot_format(h) }
    val rate: Int = l.ot_rate(h)
    val channels: Int = l.ot_channels(h)
    val exclusive: Boolean = l.ot_exclusive(h) != 0
    val frameBytes = format.bytes * channels
    private var buffer = Memory(64L * 1024)

    /** Copies what fits of [frames] frames of [bytes] from [off]; how many frames did. Never blocks. */
    fun offer(bytes: ByteArray, off: Int, frames: Int): Int {
        val len = frames.toLong() * frameBytes
        if (buffer.size() < len) buffer = Memory(len)
        buffer.write(0, bytes, off, len.toInt())
        return l.ot_write(h, buffer, frames)
    }

    /** The same for float samples, [frames] frames from [off] (in samples). */
    fun offer(samples: FloatArray, off: Int, frames: Int): Int {
        val len = frames.toLong() * frameBytes
        if (buffer.size() < len) buffer = Memory(len)
        buffer.write(0, samples, off, frames * channels)
        return l.ot_write(h, buffer, frames)
    }

    /**
     * Writes all [frames], waiting for room as the device plays; stops early, with how many
     * went, when [stop] says so.
     */
    inline fun writeAll(frames: Int, stop: () -> Boolean, offerFrom: (Int, Int) -> Int): Int {
        var done = 0
        while (done < frames) {
            if (stop()) break
            val n = offerFrom(done, frames - done)
            done += n
            if (n == 0) LockSupport.parkNanos(2_000_000)
        }
        return done
    }

    val queued: Int get() = handle?.let { l.ot_queued(it) } ?: 0
    val room: Int get() = handle?.let { l.ot_room(it) } ?: 0
    val played: Long get() = handle?.let { l.ot_played(it) } ?: 0
    val underruns: Long get() = handle?.let { l.ot_underruns(it) } ?: 0
    val lost: Boolean get() = handle?.let { l.ot_lost(it) != 0 } ?: false
    val flushing: Boolean get() = handle?.let { l.ot_flushing(it) != 0 } ?: false

    fun start() { handle?.let { l.ot_start(it) } }
    fun stop() { handle?.let { l.ot_stop(it) } }

    /** Drops what's waiting, and waits (briefly) until the device has. */
    fun flush() {
        val it = handle ?: return
        l.ot_flush(it)
        val until = System.nanoTime() + 100_000_000
        while (l.ot_flushing(it) != 0 && System.nanoTime() < until) LockSupport.parkNanos(1_000_000)
    }

    fun setVolume(v: Float) { handle?.let { l.ot_set_volume(it, v.coerceIn(0f, 1f)) } }

    @Synchronized
    fun close() {
        handle?.let { l.ot_close(it) }
        handle = null
    }
}

/**
 * [PcmOut] on the native output, for the bit-perfect FLAC path: the file's own rate and
 * depth, exclusive where the system allows it and the device takes the format. Volume is
 * the device's own, so full volume leaves the samples untouched.
 *
 * Many USB DACs take 24-bit only in a 32-bit word when held exclusively: packed 24-bit is
 * tried first, then the same samples in 32-bit words (the low byte zero, so still bit for
 * bit), and only then shared mode ([outputTries]).
 */
internal class NativePcmOut(private val device: String, private val exclusive: Boolean) : PcmOut {
    @Volatile private var out: NativeOutput? = null
    @Volatile private var stopped = false
    @Volatile private var cut = false
    /** The file's 24-bit samples go out as 32-bit words. */
    private var widen = false
    private var wide = ByteArray(0)

    /** Whether the device opened for this app alone. */
    val isExclusive: Boolean get() = out?.exclusive == true

    override fun open(format: javax.sound.sampled.AudioFormat): javax.sound.sampled.AudioFormat {
        val rate = format.sampleRate.toInt()
        val tries = outputTries(format.sampleSizeInBits, exclusive && NativeAudio.exclusiveSupported)
            ?: throw java.io.IOException("${format.sampleSizeInBits}-bit isn't supported")
        var last: Exception? = null
        for ((f, ex) in tries) {
            try {
                val o = NativeAudio.open(device, f, rate, format.channels, ex, 500)
                // Shared mode converts to what's asked; a device that gives back another format is closed and passed over.
                if (o.format != f || o.rate != rate || o.channels != format.channels) { o.close(); continue }
                out = o
                widen = format.sampleSizeInBits == 24 && f == NativeAudio.Format.S32
                System.err.println("[audio] bit-perfect output: ${NativeAudio.backend}, ${if (o.exclusive) "exclusive" else "shared"}, ${f.name} at $rate Hz for a ${format.sampleSizeInBits}-bit file")
                // What the engine is to write: the file's own samples when they're widened here.
                val bits = if (widen) 24 else f.bytes * 8
                return javax.sound.sampled.AudioFormat(rate.toFloat(), bits, format.channels, true, false)
            } catch (e: Exception) {
                last = e
            }
        }
        throw java.io.IOException("The device won't play ${rate} Hz: ${last?.message}")
    }

    override fun write(bytes: ByteArray, off: Int, len: Int): Int {
        val o = out ?: return 0
        cut = false
        if (!widen) {
            val frames = len / o.frameBytes
            val done = o.writeAll(frames, stop = { stopped || cut }) { from, count -> o.offer(bytes, off + from * o.frameBytes, count) }
            return done * o.frameBytes
        }
        val inFrame = 3 * o.channels
        val frames = len / inFrame
        if (wide.size < frames * o.frameBytes) wide = ByteArray(frames * o.frameBytes)
        widen24(bytes, off, frames * o.channels, wide)
        val done = o.writeAll(frames, stop = { stopped || cut }) { from, count -> o.offer(wide, from * o.frameBytes, count) }
        return done * inFrame
    }

    override fun start() { stopped = false; out?.start() }
    override fun stop() { stopped = true; out?.stop() }
    override fun flush() { cut = true; out?.flush() }

    override fun drain() {
        val o = out ?: return
        while (o.queued > 0 && !stopped) LockSupport.parkNanos(5_000_000)
        // What the device itself holds: a period or two.
        Thread.sleep(40)
    }

    override fun close() { out?.close(); out = null }
    override val framePosition: Long get() = out?.played ?: 0

    override fun setGainDb(db: Float): Boolean {
        val o = out ?: return false
        o.setVolume(if (db <= -80f) 0f else Math.pow(10.0, db / 20.0).toFloat())
        return true
    }
}

/**
 * What [NativePcmOut] asks the device for, in order, for a [bits]-bit file: the file's own
 * format, held exclusively first when [exclusive]; 24-bit also as 32-bit words; then 16-bit
 * shared as the last resort. Null for a depth it can't play.
 */
internal fun outputTries(bits: Int, exclusive: Boolean): List<Pair<NativeAudio.Format, Boolean>>? {
    val own = when (bits) {
        16 -> listOf(NativeAudio.Format.S16)
        24 -> listOf(NativeAudio.Format.S24, NativeAudio.Format.S32)
        32 -> listOf(NativeAudio.Format.S32)
        else -> return null
    }
    return buildList {
        if (exclusive) own.forEach { add(it to true) }
        own.forEach { add(it to false) }
        if (bits == 24) add(NativeAudio.Format.S16 to false)
    }
}

/** [count] packed little-endian 24-bit samples from [off] as 32-bit words in [dst], the low byte zero: the same value, bit for bit. */
internal fun widen24(src: ByteArray, off: Int, count: Int, dst: ByteArray) {
    for (i in 0 until count) {
        val p = off + i * 3
        val q = i * 4
        dst[q] = 0
        dst[q + 1] = src[p]
        dst[q + 2] = src[p + 1]
        dst[q + 3] = src[p + 2]
    }
}
