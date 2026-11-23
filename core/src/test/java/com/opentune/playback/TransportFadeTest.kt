package com.opentune.playback

import com.opentune.playback.TransportFade.Phase
import com.opentune.playback.TransportFade.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportFadeTest {
    private val len = 1_000L

    @Test fun pauseFadesDownThenPausesAndIsShownPausedAtOnce() {
        val f = TransportFade()
        assertEquals(Step.NONE, f.pause(len, canFade = true, playing = true))
        assertEquals(Phase.PAUSING, f.phase)
        // The session shows paused from the press, while the player still plays.
        assertFalse(f.shownPlayWhenReady(actual = true))
        assertEquals(Step.NONE, f.tick(500, sounding = true))
        assertTrue(f.level in 0.1f..0.9f)
        assertEquals(Step(pause = true), f.tick(500, sounding = true))
        // Paused, the volume is back to full for whatever plays next.
        assertEquals(Phase.IDLE, f.phase)
        assertEquals(1f, f.level, 0f)
    }

    @Test fun aSecondPauseMidFadePausesNow() {
        val f = TransportFade()
        f.pause(len, true, true)
        f.tick(300, true)
        assertEquals(Step(pause = true), f.pause(len, true, true))
        assertEquals(Phase.IDLE, f.phase)
    }

    @Test fun aStallDoesNotHoldThePauseUp() {
        val f = TransportFade()
        f.pause(len, true, true)
        assertEquals(Step(pause = true), f.tick(1_000, sounding = false))
    }

    @Test fun playMidPauseTurnsBackFromWhereItIs() {
        val f = TransportFade()
        f.pause(len, true, true)
        f.tick(400, true)
        val at = f.level
        assertEquals(Step.NONE, f.play(len, canFade = true, playing = true))
        assertEquals(Phase.RISING, f.phase)
        assertTrue(f.shownPlayWhenReady(actual = true))
        assertEquals(at, f.level, 0f)
        // Back up over what the pause had taken: 400 ms, not the whole second.
        f.tick(399, true)
        assertEquals(Phase.RISING, f.phase)
        f.tick(2, true)
        assertEquals(Phase.IDLE, f.phase)
        assertEquals(1f, f.level, 0f)
    }

    @Test fun playFromPausedStartsSilentAndRisesOnlyOnceHeard() {
        val f = TransportFade()
        assertEquals(Step(play = true), f.play(len, canFade = true, playing = false))
        assertEquals(0f, f.level, 0f)
        // Still loading: it stays silent rather than coming in late at full volume.
        f.tick(2_000, sounding = false)
        assertEquals(0f, f.level, 0f)
        f.tick(500, sounding = true)
        assertTrue(f.level in 0.1f..0.9f)
        f.tick(500, sounding = true)
        assertEquals(Phase.IDLE, f.phase)
    }

    @Test fun skipFadesDownChangesThenRises() {
        val f = TransportFade()
        assertEquals(Step.NONE, f.skip(len, canFade = true, sounding = true))
        assertEquals(Phase.SKIPPING, f.phase)
        // The skip goes down in half the length.
        assertEquals(Step.NONE, f.tick(TransportFade.skipOutMs(len) - 10, true))
        assertEquals(Step(change = true), f.tick(10, true))
        assertEquals(Phase.RISING, f.phase)
        assertEquals(0f, f.level, 0f)
        f.tick(len, sounding = true)
        assertEquals(Phase.IDLE, f.phase)
    }

    @Test fun aSkipOutIsShortWhateverTheLength() {
        assertEquals(150L, TransportFade.skipOutMs(300))
        assertEquals(500L, TransportFade.skipOutMs(1_000))
        assertEquals(1_000L, TransportFade.skipOutMs(3_000))
    }

    @Test fun skipPressedAgainMakesBothNowAndRises() {
        val f = TransportFade()
        f.skip(len, true, true)
        f.tick(100, true)
        assertEquals(Step(change = true), f.skip(len, true, true))
        assertEquals(Phase.RISING, f.phase)
        assertEquals(0f, f.level, 0f)
    }

    @Test fun skipWhileStillRisingIntoASilentSongChangesAndKeepsRising() {
        val f = TransportFade()
        f.play(len, true, playing = false)
        assertEquals(Step(change = true), f.skip(len, true, sounding = false))
        assertEquals(Phase.RISING, f.phase)
        assertEquals(0f, f.level, 0f)
    }

    @Test fun skipWhilePausingPausesOnTheNewSong() {
        val f = TransportFade()
        f.pause(len, true, true)
        assertEquals(Step(change = true, pause = true), f.skip(len, true, true))
        assertEquals(Phase.IDLE, f.phase)
    }

    @Test fun pauseWhileSkippingMakesTheSkipAndPauses() {
        val f = TransportFade()
        f.skip(len, true, true)
        assertEquals(Step(change = true, pause = true), f.pause(len, true, true))
    }

    @Test fun nothingFadesWhereItCannot() {
        // Bit-perfect output, casting, a blend under way: everything acts at once.
        val f = TransportFade()
        assertEquals(Step(pause = true), f.pause(len, canFade = false, playing = true))
        assertEquals(Step(play = true), f.play(len, canFade = false, playing = false))
        assertEquals(Step(change = true), f.skip(len, canFade = false, sounding = true))
        assertEquals(Phase.IDLE, f.phase)
        assertEquals(1f, f.level, 0f)
    }

    @Test fun silenceNeedsNoFade() {
        val f = TransportFade()
        // Paused already, or not yet heard: a pause or skip is made at once.
        assertEquals(Step(pause = true), f.pause(len, true, playing = false))
        assertEquals(Step(change = true), f.skip(len, true, sounding = false))
        // Already playing: play changes nothing.
        assertEquals(Step(play = true), f.play(len, true, playing = true))
        assertEquals(Phase.IDLE, f.phase)
    }

    @Test fun theSystemPausingMidFadeWins() {
        // Audio focus lost, headphones unplugged, the sleep timer: the fade lets go at full volume.
        val rising = TransportFade()
        rising.play(len, true, false)
        rising.tick(300, true)
        assertEquals(Step.NONE, rising.interrupt())
        assertEquals(Phase.IDLE, rising.phase)
        assertEquals(1f, rising.level, 0f)

        // A pause or skip on its way is still made.
        val pausing = TransportFade()
        pausing.pause(len, true, true)
        assertEquals(Step(pause = true), pausing.interrupt())
        val skipping = TransportFade()
        skipping.skip(len, true, true)
        assertEquals(Step(change = true), skipping.interrupt())
        assertEquals(Step.NONE, TransportFade().interrupt())
    }

    @Test fun theCurveRunsEvenlyFromSilenceToFull() {
        val f = TransportFade()
        f.play(len, true, false)
        var last = f.level
        repeat(10) {
            f.tick(100, true)
            assertTrue(f.level >= last)
            last = f.level
        }
        assertEquals(1f, f.level, 0f)
        // Equal power: halfway through, the level is about 0.71, not 0.5.
        val g = TransportFade()
        g.play(len, true, false)
        g.tick(500, true)
        assertEquals(0.707f, g.level, 0.01f)
    }

    @Test fun releaseLetsGoWithoutAnything() {
        val f = TransportFade()
        f.skip(len, true, true)
        f.release()
        assertEquals(Phase.IDLE, f.phase)
        assertEquals(Step.NONE, f.tick(1_000, true))
    }
}
