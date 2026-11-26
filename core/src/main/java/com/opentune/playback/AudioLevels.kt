package com.opentune.playback

import kotlin.math.sqrt

/**
 * How loud the music is right now, how much bass is in it, and how hard it
 * just hit, for effects that move with the song. The main player's sink
 * measures each slice of sound as it goes by ([measure]) and stamps it with
 * the position in the song it belongs to; [at] answers for a position on the
 * song's own clock. When the sound was processed never enters into it, so a
 * song decoded in a burst (a download, a fast cache) reads exactly like one
 * that trickled in, and only the main player measures, so no other player's
 * decoding can poison what the screen reads. Nothing is measured unless
 * something is [listening].
 *
 * Positions are kept in microseconds, and a slice's start is carried in
 * floating point: at 44.1 kHz a slice is 23.219… ms, and stamping in whole
 * milliseconds would fall a fifth of a millisecond further behind the song
 * with every slice — a quarter of a second inside a minute, past any honest
 * read window, and the levels would read as silence from then on.
 */
object AudioLevels {
    /** Set while an effect is on screen; measuring costs nothing otherwise. */
    @Volatile var listening = false

    private var holders = 0

    /** For each thing on screen that wants the levels; [release] when it goes. Measuring runs while any hold. */
    @Synchronized
    fun hold() {
        holders++
        listening = true
    }

    @Synchronized
    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        listening = holders > 0
    }

    /** One measurement per slice this long. */
    private const val SLICE_FRAMES = 1024
    private const val SLOTS = 256
    /** A read with no slice this close is silence: the levels' clock and the
     * song's can sit a little apart (the sink learns one from the other), so
     * the nearest slice answers, within this window. */
    private const val READ_WINDOW_US = 700_000L
    /** A buffer starting further than this from where the gathering slice expects it is a jump, not a continuation. */
    private const val CONTINUITY_US = 25_000.0

    private val starts = LongArray(SLOTS)
    private val levels = FloatArray(SLOTS)
    private val basses = FloatArray(SLOTS)
    private val hits = FloatArray(SLOTS)
    private val dances = FloatArray(SLOTS)
    private val epochs = IntArray(SLOTS)
    private var head = -1
    private var epoch = 0

    // Running state of the measurement, kept between buffers.
    private var low = 0f
    private var mid = 0f
    private var sum = 0.0
    private var lowSum = 0.0
    private var midSum = 0.0
    private var highSum = 0.0
    private var count = 0
    /** Where the slice being gathered starts, in the song, microseconds. */
    private var sliceStartUs = 0.0
    /** Each band's recent energy: the line a slice must jump over to count as a hit. */
    private var lowAvg = 0f
    private var midAvg = 0f
    private var highAvg = 0f

    /** A moment's loudness (0 to about 1), its bass, and how hard the beat just hit (0 to 1). */
    data class Level(val level: Float, val bass: Float, val hit: Float, val dance: Float = 0f)

    /**
     * Measures one buffer of interleaved [channels]-channel samples read by
     * [sample] (index to value in -1..1), [frames] frames at [sampleRate],
     * the buffer starting at [startPositionUs] in the song.
     */
    inline fun measure(startPositionUs: Long, frames: Int, channels: Int, sampleRate: Int, sample: (Int) -> Float) {
        if (!listening || channels <= 0 || sampleRate <= 0 || frames <= 0) return
        begin(startPositionUs, sampleRate)
        // One-pole low-passes: about 150 Hz keeps the kick and the bass line;
        // about 1.5 kHz splits the snare and voice band from the hats above it.
        val alphaLow = (2.0 * Math.PI * 150.0 / sampleRate).toFloat().coerceIn(0f, 1f)
        val alphaMid = (2.0 * Math.PI * 1500.0 / sampleRate).toFloat().coerceIn(0f, 1f)
        for (f in 0 until frames) {
            var mono = 0f
            for (c in 0 until channels) mono += sample(f * channels + c)
            mono /= channels
            add(mono, alphaLow, alphaMid, sampleRate)
        }
    }

    /** A new buffer: takes its start as the gathering slice's, unless the slice runs on from the last buffer. */
    @PublishedApi
    internal fun begin(startPositionUs: Long, sampleRate: Int) {
        if (count == 0) {
            sliceStartUs = startPositionUs.toDouble()
            return
        }
        // The gathering slice began in an earlier buffer; this one should
        // continue it. A start that says otherwise (a jump the flush missed)
        // drops the part-gathered slice rather than stamping it wrong.
        val expected = sliceStartUs + count * 1_000_000.0 / sampleRate
        if (kotlin.math.abs(startPositionUs - expected) > CONTINUITY_US) {
            sum = 0.0; lowSum = 0.0; midSum = 0.0; highSum = 0.0; count = 0
            sliceStartUs = startPositionUs.toDouble()
        }
    }

    @PublishedApi
    internal fun add(mono: Float, alphaLow: Float, alphaMid: Float, sampleRate: Int) {
        low += alphaLow * (mono - low)
        mid += alphaMid * (mono - mid)
        val midBand = mid - low
        val highBand = mono - mid
        sum += mono * mono
        lowSum += low * low
        midSum += midBand * midBand
        highSum += highBand * highBand
        count++
        if (count >= SLICE_FRAMES) {
            push(
                sliceStartUs.toLong(),
                sqrt(sum / count).toFloat(),
                sqrt(lowSum / count).toFloat(),
                sqrt(midSum / count).toFloat(),
                sqrt(highSum / count).toFloat(),
            )
            sliceStartUs += count * 1_000_000.0 / sampleRate
            sum = 0.0; lowSum = 0.0; midSum = 0.0; highSum = 0.0; count = 0
        }
    }

    @Synchronized
    private fun push(startUs: Long, level: Float, lowE: Float, midE: Float, highE: Float) {
        // A band's hit: how far this slice's energy jumps over the band's own
        // recent level, measured against that level — a snare in a quiet
        // verse counts as surely as a kick in a loud chorus, and mere
        // loudness that stays loud counts for nothing.
        fun onset(e: Float, avg: Float): Float {
            val over = e - avg * 1.12f
            return if (over <= 0f) 0f else (over / (avg + 0.03f)).coerceAtMost(1.5f) / 1.5f
        }
        val hit = (onset(lowE, lowAvg) * 0.5f + onset(midE, midAvg) * 0.65f + onset(highE, highAvg) * 0.5f)
            .coerceIn(0f, 1f)
        lowAvg += 0.07f * (lowE - lowAvg)
        midAvg += 0.07f * (midE - midAvg)
        highAvg += 0.07f * (highE - highAvg)
        // The dance: the beat's envelope, advanced by the slice's own length —
        // quick to rise with a hit, slow to fall away — so a reader moving
        // with the song glides on a curve that was smooth before it saw it.
        val raw = (level * 0.35f + hit * 0.8f).coerceIn(0f, 1f)
        val prevDance = if (head >= 0 && epochs[head] == epoch) dances[head] else 0f
        val dtUs = if (head >= 0 && epochs[head] == epoch) (startUs - starts[head]).coerceIn(1L, 100_000L) else 21_333L
        val tauUs = if (raw > prevDance) 30_000f else 230_000f
        val dance = prevDance + (raw - prevDance) * (1f - kotlin.math.exp(-dtUs / tauUs))
        head = (head + 1) % SLOTS
        starts[head] = startUs
        levels[head] = level
        basses[head] = lowE
        hits[head] = hit
        dances[head] = dance
        epochs[head] = epoch
    }

    /** Forgets what's queued, after a seek or a new song, so old sound isn't shown. */
    @Synchronized
    fun flush() {
        epoch++
        head = -1
        low = 0f
        mid = 0f
        sum = 0.0; lowSum = 0.0; midSum = 0.0; highSum = 0.0; count = 0
        lowAvg = 0f; midAvg = 0f; highAvg = 0f
    }

    // Where the player jumped to, noted by the service from the player's own
    // thread (the sink's thread may not ask the player anything). The sink
    // pairs it with the next buffer it measures, and the stream's base falls
    // out of the pair.
    @Volatile
    private var pendingPositionUs: Long? = null

    @Volatile
    private var pendingAtNs = 0L

    /** The player jumped: the next stretch starts at [positionUs]. */
    fun notePosition(positionUs: Long) {
        pendingPositionUs = positionUs
        pendingAtNs = System.nanoTime()
    }

    /** The pending jump's position, handed out once — and only while it is
     * fresh: a note no stretch claimed within two seconds belongs to a jump
     * long past, and pairing it with a later stretch would misplace it. */
    fun takePendingPosition(): Long? {
        val p = pendingPositionUs
        pendingPositionUs = null
        return if (p != null && System.nanoTime() - pendingAtNs <= 2_000_000_000L) p else null
    }

    /** The level at [positionMs] in the song, or null when nothing is measured near then. */
    @Synchronized
    fun at(positionMs: Long): Level? {
        if (head < 0) return null
        val positionUs = positionMs * 1_000L
        var best = -1
        var bestDist = Long.MAX_VALUE
        var i = head
        repeat(SLOTS) {
            if (epochs[i] == epoch) {
                val d = kotlin.math.abs(starts[i] - positionUs)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            i = (i - 1 + SLOTS) % SLOTS
        }
        return if (best >= 0 && bestDist <= READ_WINDOW_US) Level(levels[best], basses[best], hits[best], dances[best]) else null
    }

    /** The level at [positionMs], gliding between the slices either side of
     * it, so a reader advancing with the song sees one continuous curve
     * instead of a staircase of 21 ms steps. */
    @Synchronized
    fun atSmooth(positionMs: Long): Level? {
        if (head < 0) return null
        val positionUs = positionMs * 1_000L
        var i = head
        var found = -1
        for (n in 0 until SLOTS) {
            if (epochs[i] == epoch && starts[i] <= positionUs) {
                found = i
                break
            }
            i = (i - 1 + SLOTS) % SLOTS
        }
        if (found < 0) return at(positionMs)
        val next = (found + 1) % SLOTS
        if (epochs[next] != epoch) return Level(levels[found], basses[found], hits[found], dances[found])
        val span = starts[next] - starts[found]
        if (span <= 0L || span > 100_000L) return Level(levels[found], basses[found], hits[found], dances[found])
        val t = ((positionUs - starts[found]).toFloat() / span).coerceIn(0f, 1f)
        return Level(
            levels[found] + (levels[next] - levels[found]) * t,
            basses[found] + (basses[next] - basses[found]) * t,
            hits[found] + (hits[next] - hits[found]) * t,
            dances[found] + (dances[next] - dances[found]) * t,
        )
    }
}
