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

    /** [seconds] of a stereo tone at [hz], [amplitude] loud. */
    private fun tone(hz: Double, amplitude: Float, seconds: Double) {
        val frames = (rate * seconds).toInt()
        AudioLevels.measure(frames, 2, rate) { i -> amplitude * sin(2 * PI * hz * (i / 2) / rate).toFloat() }
    }

    @Test fun soundIsHeardAfterTheBufferNotWhenProcessed() {
        val start = System.nanoTime()
        tone(60.0, 0.8f, 1.0)
        // Processed just now, so not heard yet.
        assertNull(AudioLevels.at(start))
        val heard = AudioLevels.at(start + 1_000_000_000L)
        assertNotNull(heard)
        assertTrue("bass ${heard!!.bass}", heard.bass > 0.3f)
    }

    @Test fun highNotesCarryLittleBass() {
        val start = System.nanoTime()
        tone(4_000.0, 0.8f, 1.0)
        val heard = AudioLevels.at(start + 1_000_000_000L)!!
        assertTrue("level ${heard.level}", heard.level > 0.4f)
        assertTrue("bass ${heard.bass}", heard.bass < 0.1f)
    }

    @Test fun nothingIsMeasuredUnlessSomethingListens() {
        AudioLevels.listening = false
        val start = System.nanoTime()
        tone(60.0, 0.8f, 1.0)
        assertNull(AudioLevels.at(start + 1_000_000_000L))
    }

    @Test fun aSeekForgetsWhatWasQueued() {
        val start = System.nanoTime()
        tone(60.0, 0.8f, 1.0)
        AudioLevels.flush()
        assertNull(AudioLevels.at(start + 1_000_000_000L))
    }

    @Test fun longAfterTheLastSoundIsSilence() {
        val start = System.nanoTime()
        tone(60.0, 0.8f, 0.1)
        assertNull(AudioLevels.at(start + 3_000_000_000L))
    }
}
