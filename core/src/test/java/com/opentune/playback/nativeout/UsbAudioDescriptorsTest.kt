package com.opentune.playback.nativeout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UsbAudioDescriptorsTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    /** A class 2 DAC: one clock, a 16-bit and a 24-bit (in 4 bytes) stereo setting, async with feedback. */
    private val uac2 = listOf(
        bytes(9, 2, 0, 0, 3, 1, 0, 0x80, 50),                 // configuration
        bytes(9, 4, 0, 0, 0, 1, 1, 0x20, 0),                  // interface 0: audio control
        bytes(9, 0x24, 1, 0x00, 0x02, 8, 64, 0, 0),           // header, bcdADC 2.00
        bytes(8, 0x24, 0x0A, 0x29, 3, 7, 0, 0),               // clock source id 0x29
        bytes(9, 4, 1, 0, 0, 1, 2, 0x20, 0),                  // interface 1 alt 0 (idle)
        bytes(9, 4, 1, 1, 2, 1, 2, 0x20, 0),                  // interface 1 alt 1
        bytes(16, 0x24, 1, 2, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0), // AS_GENERAL: PCM, 2 channels
        bytes(6, 0x24, 2, 1, 2, 16),                          // FORMAT_TYPE I: 2 bytes, 16 bits
        bytes(7, 5, 0x01, 0x05, 0x00, 0x02, 1),               // ISO OUT ep 1, async, 512 bytes, interval 1
        bytes(7, 5, 0x81, 0x11, 0x04, 0x00, 4),               // ISO IN ep 0x81, feedback, 4 bytes
        bytes(9, 4, 1, 2, 2, 1, 2, 0x20, 0),                  // interface 1 alt 2
        bytes(16, 0x24, 1, 2, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0),
        bytes(6, 0x24, 2, 1, 4, 24),                          // 4 bytes, 24 bits
        bytes(7, 5, 0x01, 0x05, 0x00, 0x04, 1),               // 1024 bytes
        bytes(7, 5, 0x81, 0x11, 0x04, 0x00, 4),
    ).reduce { a, b -> a + b }

    /** A class 1 DAC: 16-bit stereo at 44.1 and 48 kHz, adaptive, no feedback. */
    private val uac1 = listOf(
        bytes(9, 4, 0, 0, 0, 1, 1, 0, 0),
        bytes(10, 0x24, 1, 0x00, 0x01, 30, 0, 1, 1, 0),       // header, bcdADC 1.00
        bytes(9, 4, 1, 0, 0, 1, 2, 0, 0),
        bytes(9, 4, 1, 1, 1, 1, 2, 0, 0),
        bytes(7, 0x24, 1, 1, 1, 1, 0),                        // AS_GENERAL: PCM
        bytes(14, 0x24, 2, 1, 2, 2, 16, 2, 0x44, 0xAC, 0x00, 0x80, 0xBB, 0x00), // 2 ch, 2 bytes, 16 bits, 44100 & 48000
        bytes(9, 5, 0x02, 0x09, 0xC8, 0x00, 1, 0, 0),         // ISO OUT ep 2, adaptive, 200 bytes
    ).reduce { a, b -> a + b }

    @Test fun readsAClassTwoDac() {
        val d = UsbAudioDescriptors.parse(uac2)!!
        assertEquals(2, d.uacVersion)
        assertEquals(0, d.controlInterface)
        assertEquals(listOf(0x29), d.clockSources)
        assertEquals(2, d.streams.size)
        val hi = d.streams[1]
        assertEquals(1, hi.interfaceNumber); assertEquals(2, hi.alternate)
        assertEquals(2, hi.channels); assertEquals(4, hi.subslotBytes); assertEquals(24, hi.bits)
        assertEquals(0x01, hi.endpoint); assertEquals(1024, hi.maxPacket); assertEquals(1, hi.sync)
        assertEquals(0x81, hi.feedbackEndpoint); assertEquals(4, hi.feedbackPacket)
        assertEquals(8, UsbAudioDescriptors.packetsPerMs(hi, highSpeed = true))
    }

    @Test fun picksTheNarrowestSettingThatHoldsTheBits() {
        val d = UsbAudioDescriptors.parse(uac2)!!
        assertEquals(16, UsbAudioDescriptors.choose(d, 2, 16, 44_100)!!.bits)
        assertEquals(24, UsbAudioDescriptors.choose(d, 2, 24, 96_000)!!.bits)
        assertNull(UsbAudioDescriptors.choose(d, 2, 32, 96_000))
    }

    @Test fun readsAClassOneDacAndItsRates() {
        val d = UsbAudioDescriptors.parse(uac1)!!
        assertEquals(1, d.uacVersion)
        val s = d.streams.single()
        assertEquals(listOf(44_100, 48_000), s.rates)
        assertEquals(0x02, s.endpoint); assertEquals(200, s.maxPacket); assertEquals(2, s.sync); assertEquals(0, s.feedbackEndpoint)
        assertNotNull(UsbAudioDescriptors.choose(d, 2, 16, 48_000))
        // A rate it doesn't list isn't asked of it.
        assertNull(UsbAudioDescriptors.choose(d, 2, 16, 96_000))
    }
}
