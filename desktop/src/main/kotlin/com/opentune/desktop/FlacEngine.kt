package com.opentune.desktop

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ThreadLocalRandom
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.roundToInt
import org.jflac.FLACDecoder
import org.jflac.io.RandomFileInputStream
import org.jflac.metadata.StreamInfo

/** A song playing, whichever way: JavaFX for YouTube, JioSaavn and servers, [FlacEngine] for lossless files. */
internal interface Engine {
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun positionMs(): Long
    fun durationMs(): Long
    /** 0 to 1, the sleep timer's fade included. */
    fun setVolume(v: Double)
    fun dispose()
}

/** Where decoded samples go: the sound card, or in tests a stand-in. */
internal interface PcmOut {
    /** Opens for [format], or for 16-bit at the same rate when the card won't take more; the format it opened. */
    fun open(format: AudioFormat): AudioFormat
    /** Blocks until written, or returns early when stopped or flushed. */
    fun write(bytes: ByteArray, off: Int, len: Int): Int
    fun start()
    fun stop()
    fun flush()
    fun drain()
    fun close()
    /** Frames played since it opened. */
    val framePosition: Long
    /** Sets the line's own gain; false when it has none, and the samples are scaled instead. */
    fun setGainDb(db: Float): Boolean
}

/** The computer's default output, through Java Sound, at the song's own rate and depth when it takes them. */
internal class SoundCard : PcmOut {
    private var line: SourceDataLine? = null

    override fun open(format: AudioFormat): AudioFormat {
        val tries = listOf(format, AudioFormat(format.sampleRate, 16, format.channels, true, false)).distinct()
        var last: Exception? = null
        for (f in tries) {
            try {
                val l = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, f)) as SourceDataLine
                // Half a second of sound in hand: enough to ride out a busy moment, little to wait for on a seek.
                l.open(f, (f.frameRate * f.frameSize / 2).toInt())
                line = l
                return f
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("The sound card won't play ${format.sampleRate.toInt()} Hz: ${last?.message}")
    }

    override fun write(bytes: ByteArray, off: Int, len: Int) = line?.write(bytes, off, len) ?: 0
    override fun start() { line?.start() }
    override fun stop() { line?.stop() }
    override fun flush() { line?.flush() }
    override fun drain() { line?.drain() }
    override fun close() { line?.close() }
    override val framePosition: Long get() = line?.longFramePosition ?: 0

    override fun setGainDb(db: Float): Boolean {
        val gain = line?.takeIf { it.isControlSupported(FloatControl.Type.MASTER_GAIN) }?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
            ?: return false
        gain.value = db.coerceIn(gain.minimum, gain.maximum)
        return true
    }
}

/**
 * A FLAC file from an add-on, played bit for bit where the volume is full:
 * decoded here (jflac) and sent to the sound card at the file's own sample
 * rate and bit depth. The file is saved to a temporary file as it arrives;
 * playing starts as soon as the first frames are in, and a seek within the
 * saved part, or anywhere once it has all arrived, is immediate.
 *
 * [startAtMs] is read until the first sound goes out, so taking over from
 * another engine starts where that one has got to by then; [onStarted] says
 * when, for the other to stop.
 */
internal class FlacEngine(
    /** The file's bytes from the start, e.g. an HTTP response body. */
    private val source: () -> InputStream,
    private val startAtMs: () -> Long,
    autoplay: Boolean,
    private val onStarted: () -> Unit,
    private val onEnd: () -> Unit,
    private val onError: (String) -> Unit,
    private val out: PcmOut = SoundCard(),
) : Engine {
    private val lock = Object()
    private val file: File = File.createTempFile("opentune-", ".flac").apply { deleteOnExit() }
    @Volatile private var arrived = 0L
    @Volatile private var complete = false
    @Volatile private var failed: String? = null
    @Volatile private var disposed = false
    @Volatile private var paused = !autoplay
    @Volatile private var seekRequest: Long? = null
    /** Where a seek is going, said as the position until its first sound plays. */
    @Volatile private var seekingTo: Long? = null
    @Volatile private var info: StreamInfo? = null
    @Volatile private var started = false
    /** Where the current run started, in samples, and the line's position then. */
    @Volatile private var runStart = 0L
    @Volatile private var lineBase = 0L
    @Volatile private var volume = 1.0
    @Volatile private var softGain = 1.0
    private var input: InputStream? = null

    /** The format the card opened, which can be 16-bit for a 24-bit file. */
    @Volatile var output: AudioFormat? = null
        private set

    private val download = Thread({ fetch() }, "flac-download").apply { isDaemon = true; start() }
    private val player = Thread({ run() }, "flac-play").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

    /** Whether the whole file is saved, so a seek anywhere is immediate. */
    internal val arrivedAll: Boolean get() = complete

    val sampleRate: Int get() = info?.sampleRate ?: 0
    val bits: Int get() = info?.bitsPerSample ?: 0

    private fun fetch() {
        try {
            source().use { stream ->
                RandomAccessFile(file, "rw").use { raf ->
                    input = stream
                    val buffer = ByteArray(1 shl 16)
                    while (!disposed) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        raf.write(buffer, 0, n)
                        arrived += n
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            }
            complete = !disposed
        } catch (e: Exception) {
            if (!disposed) failed = e.message ?: "the download stopped"
        } finally {
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** The saved file, read as it grows: waits for more rather than ending early. */
    private inner class Arriving : InputStream() {
        private val raf = RandomAccessFile(file, "r")
        private var at = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            synchronized(lock) {
                while (at >= arrived && !complete && failed == null && !disposed) lock.wait(200)
            }
            if (disposed) throw IOException("closed")
            if (at >= arrived) return -1
            raf.seek(at)
            val n = raf.read(b, off, minOf(len.toLong(), arrived - at).toInt())
            if (n > 0) at += n
            return n
        }

        override fun close() = raf.close()
    }

    private fun run() {
        var target: Long? = null
        try {
            while (!disposed) {
                val ready = complete && target != null
                val stream: InputStream = if (ready) RandomFileInputStream(file) else Arriving()
                stream.use {
                    val decoder = FLACDecoder(stream)
                    decoder.readMetadata()
                    val si = decoder.streamInfo ?: throw IOException("no STREAMINFO")
                    if (info == null) open(si)
                    var at = 0L
                    if (ready) at = decoder.seek(target!!).coerceAtLeast(0)
                    val outcome = play(decoder, si, at, target)
                    target = outcome ?: return
                }
            }
        } catch (e: Exception) {
            if (!disposed) onError(e.message ?: "couldn't play the file")
        } finally {
            out.close()
            if (disposed) file.delete()
        }
    }

    private fun open(si: StreamInfo) {
        if (si.bitsPerSample != 16 && si.bitsPerSample != 24) throw IOException("${si.bitsPerSample}-bit FLAC isn't supported")
        info = si
        val opened = out.open(AudioFormat(si.sampleRate.toFloat(), si.bitsPerSample, si.channels, true, false))
        output = opened
        applyVolume()
        if (!paused) out.start()
    }

    /**
     * Plays from [from] (the first sample [decoder] gives) until the end, a
     * seek or the end of the engine. Returns the sample to start again from
     * on a seek; null when done.
     */
    private fun play(decoder: FLACDecoder, si: StreamInfo, from: Long, fixed: Long?): Long? {
        val inFrame = si.channels * si.bitsPerSample / 8
        val outFrame = si.channels * (output!!.sampleSizeInBits / 8)
        var sample = from
        // Before the first sound, follow the other engine; after it, a seek's own target.
        var target = fixed ?: msToSample(startAtMs())
        var emitting = false
        var pcm: org.jflac.util.ByteData? = null
        var converted = ByteArray(0)
        while (true) {
            if (disposed) return null
            seekRequest?.let { request ->
                seekRequest = null
                out.flush()
                return request
            }
            val frame = decoder.readNextFrame()
            if (frame == null) {
                if (disposed) return null
                failed?.let { throw IOException("the file stopped arriving: $it") }
                out.drain()
                if (!disposed) onEnd()
                return null
            }
            pcm = decoder.decodeFrame(frame, pcm)
            val frames = pcm.len / inFrame
            if (!emitting && fixed == null && !started) target = msToSample(startAtMs())
            val end = sample + frames
            if (end <= target) {
                sample = end
                continue
            }
            val skip = (target - sample).coerceAtLeast(0).toInt()
            if (!emitting) {
                emitting = true
                out.flush()
                runStart = sample + skip
                lineBase = out.framePosition
                seekingTo = null
            }
            sample = end
            var bytes = pcm.data
            var off = skip * inFrame
            var len = (frames - skip) * inFrame
            if (outFrame != inFrame) {
                // The card took 16-bit only: 24-bit samples rounded with TPDF dither.
                val n = (frames - skip) * si.channels
                if (converted.size < n * 2) converted = ByteArray(n * 2)
                to16(bytes, off, n, converted)
                bytes = converted; off = 0; len = n * 2
            } else if (softGain != 1.0) {
                bytes = bytes.copyOfRange(off, off + len); off = 0
                scale(bytes, si.bitsPerSample, softGain)
            }
            if (!write(bytes, off, len)) return seekRequest.also { seekRequest = null; out.flush() }
            if (!started) {
                started = true
                onStarted()
            }
        }
    }

    /** Writes it all, waiting through a pause; false when a seek or the end cut it short. */
    private fun write(bytes: ByteArray, off: Int, len: Int): Boolean {
        var at = off
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

    private fun msToSample(ms: Long): Long = info?.let { ms.coerceAtLeast(0) * it.sampleRate / 1000 } ?: 0

    override fun play() {
        paused = false
        if (info != null) out.start()
        synchronized(lock) { lock.notifyAll() }
    }

    override fun pause() {
        paused = true
        out.stop()
    }

    override fun seek(ms: Long) {
        val si = info ?: return
        val to = ms.coerceIn(0, durationMs())
        seekingTo = to
        seekRequest = to * si.sampleRate / 1000
        out.flush()
        synchronized(lock) { lock.notifyAll() }
    }

    override fun positionMs(): Long {
        val si = info ?: return startAtMs()
        if (!started) return startAtMs()
        seekingTo?.let { return it }
        val frames = runStart + (out.framePosition - lineBase)
        return frames * 1000 / si.sampleRate
    }

    override fun durationMs(): Long = info?.let { it.totalSamples * 1000 / it.sampleRate } ?: 0

    override fun setVolume(v: Double) {
        volume = v.coerceIn(0.0, 1.0)
        applyVolume()
    }

    /** The line's own gain where it has one; else the samples are scaled, which full volume leaves exact. */
    private fun applyVolume() {
        if (info == null) return
        val db = if (volume <= 0.0) -80f else (20 * log10(volume)).toFloat()
        softGain = if (out.setGainDb(db)) 1.0 else volume
    }

    override fun dispose() {
        disposed = true
        out.stop()
        out.flush()
        runCatching { input?.close() }
        synchronized(lock) { lock.notifyAll() }
        download.interrupt()
    }

    internal companion object {
        /** 24-bit little-endian samples to 16-bit, with triangular dither so the rounding is noise, not distortion. */
        fun to16(src: ByteArray, off: Int, count: Int, dst: ByteArray) {
            val random = ThreadLocalRandom.current()
            for (i in 0 until count) {
                val p = off + i * 3
                val v = (src[p].toInt() and 0xff) or ((src[p + 1].toInt() and 0xff) shl 8) or (src[p + 2].toInt() shl 16)
                val dither = random.nextInt(256) - random.nextInt(256)
                val s = ((v + dither) / 256.0).roundToInt().coerceIn(-32768, 32767)
                dst[i * 2] = s.toByte()
                dst[i * 2 + 1] = (s shr 8).toByte()
            }
        }

        /** Scales little-endian 16- or 24-bit samples in place. */
        fun scale(b: ByteArray, bits: Int, gain: Double) {
            if (bits == 16) {
                var i = 0
                while (i + 1 < b.size) {
                    val v = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)) * gain
                    val s = v.roundToInt().coerceIn(-32768, 32767)
                    b[i] = s.toByte(); b[i + 1] = (s shr 8).toByte()
                    i += 2
                }
            } else {
                var i = 0
                while (i + 2 < b.size) {
                    val v = ((b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or (b[i + 2].toInt() shl 16)) * gain
                    val s = v.roundToInt().coerceIn(-8_388_608, 8_388_607)
                    b[i] = s.toByte(); b[i + 1] = (s shr 8).toByte(); b[i + 2] = (s shr 16).toByte()
                    i += 3
                }
            }
        }
    }
}
