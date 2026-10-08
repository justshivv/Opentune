package com.opentune.playback

import kotlin.math.max
import kotlin.math.sqrt

/**
 * How loud the music is right now, and how much bass is in it, for effects
 * that move with the song. The audio processor measures each slice of sound
 * as it goes by ([measure]); since a slice is heard some time after it's
 * processed, once the sink's buffer has played out, each one is stamped with
 * when it will be heard, and [at] answers for a moment on the screen's
 * clock. Nothing is measured unless something is [listening].
 */
object AudioLevels {
    /** Set while an effect is on screen; measuring costs nothing otherwise. */
    @Volatile var listening = false

    /** Roughly how long processed sound waits in the output buffer before it's heard. */
    private const val DELAY_NS = 320_000_000L
    /** One measurement per slice this long. */
    private const val SLICE_FRAMES = 1024
    private const val SLOTS = 256

    private val times = LongArray(SLOTS)
    private val levels = FloatArray(SLOTS)
    private val basses = FloatArray(SLOTS)
    private var head = -1
    private var lastEnd = 0L

    // Running state of the measurement, kept between buffers.
    private var low = 0f
    private var sum = 0.0
    private var lowSum = 0.0
    private var count = 0

    /** A moment's loudness (0 to about 1) and bass (likewise). */
    data class Level(val level: Float, val bass: Float)

    /**
     * Measures one buffer of interleaved [channels]-channel samples read by
     * [sample] (index to value in -1..1), [frames] frames at [sampleRate].
     */
    inline fun measure(frames: Int, channels: Int, sampleRate: Int, sample: (Int) -> Float) {
        if (!listening || channels <= 0 || sampleRate <= 0) return
        // A one-pole low-pass at about 150 Hz keeps the kick and the bass line.
        val alpha = (2.0 * Math.PI * 150.0 / sampleRate).toFloat().coerceIn(0f, 1f)
        for (f in 0 until frames) {
            var mono = 0f
            for (c in 0 until channels) mono += sample(f * channels + c)
            mono /= channels
            add(mono, alpha, sampleRate)
        }
    }

    @PublishedApi
    internal fun add(mono: Float, alpha: Float, sampleRate: Int) {
        low += alpha * (mono - low)
        sum += mono * mono
        lowSum += low * low
        count++
        if (count >= SLICE_FRAMES) {
            push(sqrt(sum / count).toFloat(), sqrt(lowSum / count).toFloat(), count * 1_000_000_000L / sampleRate)
            sum = 0.0; lowSum = 0.0; count = 0
        }
    }

    @Synchronized
    private fun push(level: Float, bass: Float, durationNs: Long) {
        val now = System.nanoTime()
        // Slices that come in a burst (the buffer filling at the start) play one after another.
        val start = max(now + DELAY_NS, lastEnd)
        head = (head + 1) % SLOTS
        times[head] = start
        levels[head] = level
        basses[head] = bass
        lastEnd = start + durationNs
    }

    /** Forgets what's queued, after a seek or a new song, so old sound isn't shown. */
    @Synchronized
    fun flush() {
        head = -1
        lastEnd = 0L
        low = 0f
        sum = 0.0; lowSum = 0.0; count = 0
    }

    /** The level being heard at [nowNs] (System.nanoTime), or null when nothing is queued for then. */
    @Synchronized
    fun at(nowNs: Long): Level? {
        if (head < 0) return null
        var i = head
        repeat(SLOTS) {
            val t = times[i]
            if (t == 0L) return null
            if (t <= nowNs) {
                // Sound that ended a while ago is silence, not the last thing heard.
                return if (nowNs - t > 400_000_000L) null else Level(levels[i], basses[i])
            }
            i = (i - 1 + SLOTS) % SLOTS
        }
        return null
    }
}
