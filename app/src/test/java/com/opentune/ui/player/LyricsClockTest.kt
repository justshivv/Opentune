package com.opentune.ui.player

import com.opentune.data.lyrics.activeIndex
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Ported from BitChord's LyricClockTest (github.com/kushagrasinghx/BitChord,
 * GPL-3.0). Both apps are GPL-3.0.
 */
class LyricsClockTest {
    private val frameMs = 1000.0 / 60

    /**
     * A song playing at [rate] from [startMs], read every 500 ms. Each reading is
     * stamped when it is taken, then reaches the frame loop up to [maxDelayMs]
     * later — the way a reading did when the main thread was busy.
     */
    private class Playback(
        val startMs: Long = 44_500L,
        val rate: Double = 1.0,
        val maxDelayMs: Double = 60.0,
        val stamped: Boolean = true,
        seed: Int = 7,
    ) {
        private val random = Random(seed)
        private var nextPollMs = 0.0
        private val inFlight = ArrayDeque<Pair<Double, Pair<Long, Double>>>()
        var report: Long = startMs
            private set
        var sampledAt: Double = if (stamped) 0.0 else Double.NaN
            private set

        fun trueAt(nowMs: Double) = startMs + nowMs * rate

        fun deliver(nowMs: Double) {
            while (nextPollMs <= nowMs) {
                val reading = trueAt(nextPollMs).toLong()
                val arrives = nextPollMs + random.nextDouble() * maxDelayMs
                inFlight.addLast(arrives to (reading to nextPollMs))
                nextPollMs += 500.0
            }
            while (inFlight.isNotEmpty() && inFlight.first().first <= nowMs) {
                val (reading, at) = inFlight.removeFirst().second
                report = reading
                if (stamped) sampledAt = at
            }
        }
    }

    private class Run(val frames: List<Double>, val shown: List<Long>, val truth: List<Double>)

    /** Frames at 60 Hz, with a stall of up to [maxStallMs] — no frames at all — now and then. */
    private fun run(playback: Playback, seconds: Double, maxStallMs: Double = 0.0, seed: Int = 11): Run {
        val random = Random(seed)
        val clock = LyricClock(playback.startMs)
        val frames = ArrayList<Double>()
        val shown = ArrayList<Long>()
        val truth = ArrayList<Double>()
        var now = 0.0
        while (now <= seconds * 1000) {
            playback.deliver(now)
            frames += now
            shown += clock.frame(now, playback.report, playback.sampledAt, discontinuity = 0)
            truth += playback.trueAt(now)
            now += frameMs
            if (maxStallMs > 0 && random.nextDouble() < 0.02) now += random.nextDouble() * maxStallMs
        }
        return Run(frames, shown, truth)
    }

    private fun assertNeverBack(run: Run) {
        for (i in 1 until run.shown.size) {
            assertTrue("went back at ${run.frames[i]}", run.shown[i] >= run.shown[i - 1])
        }
    }

    @Test fun lateReadingsNeverMoveTheWordsBack() {
        assertNeverBack(run(Playback(maxDelayMs = 800.0), seconds = 90.0, maxStallMs = 600.0))
    }

    @Test fun lateReadingsDoNotPullTheWordsOffTheSong() {
        val run = run(Playback(maxDelayMs = 800.0), seconds = 60.0, maxStallMs = 600.0)
        for (i in run.frames.indices) {
            if (run.frames[i] < 2_000) continue
            val error = run.shown[i] - run.truth[i]
            assertTrue("off by $error at ${run.frames[i]}", abs(error) < 40.0)
        }
    }

    @Test fun lateReadingsNeverStallTheWords() {
        val run = run(Playback(maxDelayMs = 800.0), seconds = 60.0)
        val window = 15
        for (i in window until run.shown.size) {
            val moved = run.shown[i] - run.shown[i - window]
            val elapsed = run.frames[i] - run.frames[i - window]
            assertTrue("stalled near ${run.frames[i]}: $moved ms in $elapsed", moved >= elapsed * 0.5)
        }
    }

    @Test fun unstampedReadingsNeverMoveTheWordsBack() {
        assertNeverBack(run(Playback(maxDelayMs = 800.0, stamped = false), seconds = 60.0, maxStallMs = 600.0))
    }

    @Test fun learnsAFasterPlaybackRate() {
        val run = run(Playback(rate = 1.25, maxDelayMs = 300.0), seconds = 30.0)
        for (i in run.frames.indices) {
            if (run.frames[i] < 4_000) continue
            val error = run.shown[i] - run.truth[i]
            assertTrue("off by $error at ${run.frames[i]}", abs(error) < 40.0)
        }
        assertNeverBack(run)
    }

    private fun steady(
        until: Double,
        clock: LyricClock = LyricClock(10_000),
        discontinuity: (Double) -> Long = { 0L },
        readingAt: (Double) -> Long = { 10_000 + it.toLong() },
        onFrame: (now: Double, shown: Long) -> Unit = { _, _ -> },
    ): Long {
        var now = 0.0
        var shown = 0L
        while (now <= until) {
            val taken = (now / 500).toLong() * 500.0
            shown = clock.frame(now, readingAt(taken), taken, discontinuity(now))
            onFrame(now, shown)
            now += frameMs
        }
        return shown
    }

    @Test fun oneWrongReadingIsIgnored() {
        var previous = Long.MIN_VALUE
        val last = steady(until = 8_000.0, readingAt = { taken ->
            10_000 + taken.toLong() + when (taken) {
                3_000.0 -> 1_500
                5_000.0 -> -1_500
                else -> 0
            }
        }) { now, shown ->
            assertTrue("went back at $now", shown >= previous)
            if (now > 1_000) assertTrue("followed the bad reading at $now", abs(shown - (10_000 + now)) < 40)
            previous = shown
        }
        assertTrue(abs(last - 18_000) < 40)
    }

    @Test fun announcedBackwardSeekGlidesBackAndLands() {
        var shown = 0L
        var largestStep = 0L
        val seekAt = 4_000.0
        steady(
            until = 5_000.0,
            discontinuity = { if (it >= seekAt) 1L else 0L },
            readingAt = { taken -> 10_000 + taken.toLong() - if (taken >= seekAt) 1_000 else 0 },
        ) { now, next ->
            if (now > seekAt) largestStep = maxOf(largestStep, abs(next - shown))
            shown = next
        }
        assertTrue("cut rather than glided: $largestStep ms in a frame", largestStep < 150)
        assertTrue("never landed: $shown", abs(shown - 14_000) < 40)
    }

    @Test fun announcedFarSeekJumpsAtOnce() {
        val clock = LyricClock(30_000)
        clock.frame(0.0, 30_000, 0.0, 0)
        clock.frame(16.0, 30_000, 0.0, 0)
        assertEquals(4_003L, clock.frame(33.0, 4_000, 30.0, 1))
    }

    @Test fun unannouncedSmallRewindSlowsDownInsteadOfReversing() {
        var previous = Long.MIN_VALUE
        val last = steady(
            until = 12_000.0,
            readingAt = { taken -> 10_000 + taken.toLong() - if (taken >= 4_000) 800 else 0 },
        ) { now, shown ->
            assertTrue("went back at $now", shown >= previous)
            previous = shown
        }
        assertTrue("never met the song: $last", abs(last - (22_000 - 800)) < 40)
    }

    @Test fun unannouncedFarJumpIsFollowed() {
        val last = steady(
            until = 8_000.0,
            readingAt = { taken -> 10_000 + taken.toLong() - if (taken >= 4_000) 20_000 else 0 },
        )
        assertTrue("stayed lost: $last", abs(last - (18_000 - 20_000)) < 40)
    }

    /**
     * "Skip non-music parts" jumps a music video's 10 s skit at 100 s: one seek, to 110 s.
     * The lyrics are placed on the video's clock (LyricsGaps), so the clock follows the
     * player as it is: the jump lands at once, not as a 10 s catch-up, and the line after
     * the skit lights when the song reaches it, not before.
     */
    @Test fun aSkippedNonMusicStretchLandsAsAJump() {
        val gaps = listOf(com.opentune.data.lyrics.LyricsGaps.Gap(100_000, 110_000))
        // On the song's clock: the skit falls between them, at the song's 100 s.
        val before = com.opentune.data.lyrics.LyricLine(95_000, 100_000, "and the gulls go quiet", emptyList(), wordSynced = false)
        val after = com.opentune.data.lyrics.LyricLine(101_000, 110_000, "till the ferry bell returns", emptyList(), wordSynced = false)
        val placed = (com.opentune.data.lyrics.LyricsGaps.onto(
            com.opentune.data.lyrics.Lyrics.Synced(listOf(before, after), "Test"), gaps,
        ) as com.opentune.data.lyrics.Lyrics.Synced).lines
        val clock = LyricClock(97_000)
        fun reading(at: Double) = 97_000 + at.toLong() + if (at >= 3_000) 10_000 else 0
        var now = 0.0
        var jumpShown = Long.MIN_VALUE
        var lit = Double.NaN
        while (now <= 6_000) {
            val shown = clock.frame(now, reading(now), now, discontinuity = if (now >= 3_000) 1 else 0)
            if (now >= 3_000 && jumpShown == Long.MIN_VALUE) jumpShown = shown
            if (lit.isNaN() && placed.getOrNull(placed.activeIndex(shown))?.text == after.text) lit = now
            now += frameMs
        }
        assertTrue("glided into the jump: $jumpShown", abs(jumpShown - reading(3_000.0 + frameMs)) < 40)
        assertTrue("the line after the skit lit at $lit", abs(lit - 4_000) < 40)
    }

    /**
     * Skip silence cuts 1.5 s out of the song at 5 s. Announced as a jump, the
     * words are on it within a glide, and keep time after it instead of
     * running fast on a rate learned across the cut.
     */
    @Test fun aSkippedSilenceAnnouncedAsAJumpIsTakenAndTimeIsKept() {
        val clock = LyricClock(10_000)
        fun reading(at: Double) = 10_000 + at.toLong() + if (at >= 5_000) 1_500 else 0
        var now = 0.0
        var worst = 0.0
        while (now <= 12_000) {
            val shown = clock.frame(now, reading(now), now, discontinuity = if (now >= 5_000) 1 else 0)
            // Past the glide, the words keep to the song.
            if (now >= 5_400) worst = maxOf(worst, abs(shown - reading(now).toDouble()))
            now += frameMs
        }
        assertTrue("off by $worst ms after the skip", worst < 40)
    }
}
