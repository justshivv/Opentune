package com.opentune.playback

import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioLevelsTest {
    private val rate = 44_100

    @Before fun on() { AudioLevels.flush(); AudioLevels.listening = true }
    @After fun off() { AudioLevels.listening = false; AudioLevels.flush() }

    /** [seconds] of a stereo tone at [hz], [amplitude] loud, starting at [atMs] in the song. */
    private fun tone(atMs: Long, hz: Double, amplitude: Float, seconds: Double) {
        val frames = (rate * seconds).toInt()
        AudioLevels.measure(atMs * 1_000L, frames, 2, rate) { i -> amplitude * sin(2 * PI * hz * (i / 2) / rate).toFloat() }
    }

    @Test fun soundIsReadAtItsOwnPosition() {
        tone(0L, 60.0, 0.8f, 1.0)
        val heard = AudioLevels.at(500L)
        assertNotNull(heard)
        assertTrue("bass ${heard!!.bass}", heard.bass > 0.3f)
        // Before the song starts, and long past the measured second: nothing.
        assertNull(AudioLevels.at(-1_000L))
        assertNull(AudioLevels.at(5_000L))
    }

    @Test fun smoothReadsGlideBetweenSlices() {
        tone(0L, 60.0, 0.1f, 1.0)
        tone(1_000L, 60.0, 0.9f, 1.0)
        val quiet = AudioLevels.atSmooth(500L)!!
        val seam = AudioLevels.atSmooth(1_000L)!!
        val loud = AudioLevels.atSmooth(1_500L)!!
        assertTrue("seam ${seam.level} between ${quiet.level} and ${loud.level}", seam.level > quiet.level && seam.level < loud.level)
        assertTrue("dance ${loud.dance}", loud.dance > 0.3f)
    }

    @Test fun highNotesCarryLittleBass() {
        tone(0L, 4_000.0, 0.8f, 1.0)
        val heard = AudioLevels.at(500L)!!
        assertTrue("level ${heard.level}", heard.level > 0.4f)
        assertTrue("bass ${heard.bass}", heard.bass < 0.1f)
    }

    @Test fun nothingIsMeasuredUnlessSomethingListens() {
        AudioLevels.listening = false
        tone(0L, 60.0, 0.8f, 1.0)
        assertNull(AudioLevels.at(500L))
    }

    @Test fun aSeekForgetsWhatWasQueued() {
        tone(0L, 60.0, 0.8f, 1.0)
        AudioLevels.flush()
        assertNull(AudioLevels.at(500L))
    }

    @Test fun aSnareHitsAsSurelyAsAKick() {
        // A quiet verse first, so each band's recent level settles low.
        tone(0L, 200.0, 0.05f, 1.0)
        // A snare-like burst: energy in the middle band, none in the bass.
        tone(1_000L, 1_200.0, 0.85f, 0.1)
        val snare = AudioLevels.at(1_010L)!!
        assertTrue("hit ${snare.hit}", snare.hit > 0.35f)
        // And a kick burst after quiet hits through the bass band.
        tone(2_000L, 200.0, 0.05f, 1.0)
        tone(3_000L, 55.0, 0.9f, 0.1)
        val kick = AudioLevels.at(3_010L)!!
        assertTrue("hit ${kick.hit}", kick.hit > 0.35f)
    }

    @Test fun steadyLoudnessIsNotAHit() {
        tone(0L, 200.0, 0.7f, 2.0)
        val later = AudioLevels.at(1_800L)!!
        assertTrue("hit ${later.hit}", later.hit < 0.2f)
    }

    @Test fun anotherSongMeasuredElsewherePoisonsNothing() {
        tone(0L, 60.0, 0.8f, 1.0)
        // A second song decoded far ahead, at its own positions (a preload
        // racing through a download): the first song's reads are untouched.
        tone(60_000L, 4_000.0, 0.8f, 1.0)
        val heard = AudioLevels.at(500L)!!
        assertTrue("bass ${heard.bass}", heard.bass > 0.3f)
    }
}
