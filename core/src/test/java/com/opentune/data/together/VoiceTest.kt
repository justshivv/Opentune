package com.opentune.data.together

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceFramesTest {
    @Test fun voiceFramesPackAndUnpack() {
        val frames = listOf(ByteArray(3) { 1 }, ByteArray(300) { 2 }, ByteArray(1) { 3 })
        val back = VoiceFrames.unpack(VoiceFrames.pack(frames))!!
        assertEquals(frames.size, back.size)
        frames.indices.forEach { assertArrayEquals(frames[it], back[it]) }
        assertNull(VoiceFrames.unpack(byteArrayOf(0, 9, 1)))
    }
}

class SpeechGateTest {
    @Test fun opensOnSpeechAndBridgesShortPauses() {
        val gate = SpeechGate(hangFrames = 5)
        // Quiet room noise: stays shut, and learns the floor.
        repeat(40) { org.junit.Assert.assertFalse(gate.next(120.0)) }
        // One loud click doesn't open it; two loud frames do.
        org.junit.Assert.assertFalse(gate.next(3_000.0))
        org.junit.Assert.assertFalse(gate.next(100.0))
        gate.next(3_000.0)
        org.junit.Assert.assertTrue(gate.next(3_200.0))
        // A short pause between words keeps it open...
        repeat(3) { org.junit.Assert.assertTrue(gate.next(130.0)) }
        gate.next(2_800.0)
        // ...and a real stop closes it after the hang.
        val after = (1..8).map { gate.next(120.0) }
        org.junit.Assert.assertTrue(after.first())
        org.junit.Assert.assertFalse(after.last())
    }

    @Test fun steadyNoiseDoesNotHoldItOpen() {
        val gate = SpeechGate(hangFrames = 5)
        repeat(30) { gate.next(150.0) }
        // A fan comes on, loud enough to open it at first.
        val open = (1..400).map { gate.next(1_500.0) }
        org.junit.Assert.assertTrue(open.take(5).any { it })
        org.junit.Assert.assertFalse("closes once the floor catches up", open.last())
    }

    @Test fun measuresLoudness() {
        val pcm = ByteArray(8)
        // Samples 1000, -1000, 1000, -1000, little-endian.
        listOf(1000, -1000, 1000, -1000).forEachIndexed { i, v -> pcm[2 * i] = (v and 0xFF).toByte(); pcm[2 * i + 1] = (v shr 8).toByte() }
        org.junit.Assert.assertEquals(1000.0, SpeechGate.rms(pcm, 8), 0.01)
    }
}
