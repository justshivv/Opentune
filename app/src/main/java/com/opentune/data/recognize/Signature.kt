package com.opentune.data.recognize

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * An audio fingerprint in the form Shazam's lookup reads: the strongest
 * peaks of a short recording's spectrum, in four frequency bands, packed
 * into its binary signature format.
 *
 * This follows SongRec (https://github.com/marin-m/SongRec, GPL-3.0), which
 * worked out the format: 16 kHz mono audio, a 2048-point FFT every 128
 * samples, the peaks spread across neighbouring bins and frames, then kept
 * only where a bin stands above everything around it in both frequency and
 * time.
 */
class Signature {
    private val samples = ShortArray(WINDOW)
    private var samplesAt = 0
    private val ffts = Array(RING) { FloatArray(BINS) }
    private var fftAt = 0
    private val spread = Array(RING) { FloatArray(BINS) }
    private var spreadAt = 0
    private var spreadWritten = 0

    /** How many samples have gone in. */
    var sampleCount = 0
        private set

    private val peaks = sortedMapOf<Int, MutableList<Peak>>()

    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    // Scratch for the FFT.
    private val re = DoubleArray(WINDOW)
    private val im = DoubleArray(WINDOW)

    // Samples short of a whole step, waiting for the next call.
    private val pending = ShortArray(STEP)
    private var pendingCount = 0

    /** Adds 16 kHz mono samples. Any that don't make up a whole 128 wait for the next call. */
    fun add(pcm: ShortArray, count: Int = pcm.size) {
        for (i in 0 until count) {
            pending[pendingCount++] = pcm[i]
            if (pendingCount == STEP) {
                for (k in 0 until STEP) samples[(samplesAt + k) % WINDOW] = pending[k]
                samplesAt = (samplesAt + STEP) % WINDOW
                sampleCount += STEP
                pendingCount = 0
                fft()
                spreadPeaks()
                if (spreadWritten >= 46) findPeaks()
            }
        }
    }

    /** How many peaks have been found so far; a few dozen is enough to look up. */
    val peakCount: Int get() = peaks.values.sumOf { it.size }

    private fun fft() {
        // The last 2048 samples, oldest first, through a Hann window.
        for (k in 0 until WINDOW) {
            re[k] = samples[(samplesAt + k) % WINDOW] * HANN[k]
            im[k] = 0.0
        }
        transform(re, im)
        val out = ffts[fftAt]
        for (k in 0 until BINS) {
            out[k] = max(((re[k] * re[k] + im[k] * im[k]) / (1 shl 17)).toFloat(), 1e-10f)
        }
        fftAt = (fftAt + 1) % RING
    }

    private fun spreadPeaks() {
        val origin = ffts[(fftAt - 1 + RING) % RING]
        val s = spread[spreadAt]
        origin.copyInto(s)
        for (p in 0 until BINS) {
            if (p < BINS - 2) s[p] = max(s[p], max(s[p + 1], s[p + 2]))
            var top = s[p]
            for (back in FORMER) {
                val former = spread[(spreadAt + back + RING) % RING]
                top = max(former[p], top)
                former[p] = top
            }
        }
        spreadAt = (spreadAt + 1) % RING
        spreadWritten++
    }

    private fun findPeaks() {
        val f46 = ffts[(fftAt - 46 + RING) % RING]
        val s49 = spread[(spreadAt - 49 + RING) % RING]
        for (bin in 10 until 1015) {
            val v = f46[bin]
            if (v < 1f / 64 || v < s49[bin - 1]) continue
            var near = 0f
            for (o in NEIGHBOURS) near = max(s49[bin + o], near)
            if (v <= near) continue
            var around = near
            for (o in OTHER_FRAMES) around = max(spread[((spreadAt + o) % RING + RING) % RING][bin - 1], around)
            if (v <= around) continue

            val pass = spreadWritten - 46
            val magnitude = ln(max(1f / 64, v)) * 1477.3f + 6144
            val before = ln(max(1f / 64, f46[bin - 1])) * 1477.3f + 6144
            val after = ln(max(1f / 64, f46[bin + 1])) * 1477.3f + 6144
            val variation1 = magnitude * 2 - before - after
            if (variation1 <= 0f) continue
            val variation2 = (after - before) * 32 / variation1
            val corrected = bin * 64 + variation2
            val hz = corrected * (16000f / 2 / 1024 / 64)
            val band = when {
                hz > 250 && hz < 520 -> 0
                hz > 520 && hz < 1450 -> 1
                hz > 1450 && hz < 3500 -> 2
                hz > 3500 && hz <= 5500 -> 3
                else -> continue
            }
            peaks.getOrPut(band) { mutableListOf() } += Peak(pass, magnitude.toInt(), corrected.toInt())
        }
    }

    /** The signature, in Shazam's binary format. */
    fun encode(): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, list) in peaks) {
            val body = ByteArrayOutputStream()
            var pass = 0
            for (p in list) {
                if (p.pass - pass >= 255) {
                    body.write(0xFF)
                    body.le32(p.pass)
                    pass = p.pass
                }
                body.write(p.pass - pass)
                body.le16(p.magnitude)
                body.le16(p.bin)
                pass = p.pass
            }
            val bytes = body.toByteArray()
            contents.le32(0x60030040 + band)
            contents.le32(bytes.size)
            contents.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { contents.write(0) }
        }
        val c = contents.toByteArray()
        val out = ByteArrayOutputStream()
        // The 48-byte header; the checksum goes in once the rest is known.
        out.le32(0xCAFE2580.toInt())
        out.le32(0)
        out.le32(c.size + 8)
        out.le32(0x94119C00.toInt())
        repeat(3) { out.le32(0) }
        out.le32(3 shl 27) // 16 kHz
        repeat(2) { out.le32(0) }
        out.le32((sampleCount + 16000 * 0.24).toInt())
        out.le32((15 shl 19) + 0x40000)
        out.le32(0x40000000)
        out.le32(c.size + 8)
        out.write(c)
        val all = out.toByteArray()
        val crc = CRC32().apply { update(all, 8, all.size - 8) }.value.toInt()
        for (k in 0 until 4) all[4 + k] = (crc ushr (8 * k)).toByte()
        return all
    }

    /** How long the recording is, in milliseconds. */
    val durationMs: Long get() = sampleCount * 1000L / 16000

    companion object {
        private const val WINDOW = 2048
        private const val BINS = 1025
        private const val STEP = 128
        private const val RING = 256
        private val FORMER = intArrayOf(-1, -3, -6)
        private val NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val OTHER_FRAMES = intArrayOf(-53, -45) + (165..200 step 7).toList() + (214..249 step 7).toList()
        /** numpy's hanning(2050) without its two zero ends. */
        private val HANN = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * (it + 1) / 2049) }

        private operator fun IntArray.plus(other: List<Int>): IntArray = (this.toList() + other).toIntArray()

        private fun ByteArrayOutputStream.le32(v: Int) {
            write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
        }

        private fun ByteArrayOutputStream.le16(v: Int) {
            write(v and 0xFF); write((v ushr 8) and 0xFF)
        }

        /** In-place radix-2 FFT. */
        private fun transform(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wr = cos(angle)
                val wi = sin(angle)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar
                        im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar
                        im[i + k] += ai
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
