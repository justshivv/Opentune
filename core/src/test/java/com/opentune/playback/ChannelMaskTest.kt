package com.opentune.playback

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Bit-perfect output asks the DAC for a song's own channel layout, stereo or surround. */
@RunWith(RobolectricTestRunner::class)
class ChannelMaskTest {
    @Test fun layouts() {
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, BitPerfectUsb.channelMask(2))
        assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, BitPerfectUsb.channelMask(6))
        assertEquals(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, BitPerfectUsb.channelMask(8))
        for (n in 1..8) assertEquals("$n channels", n, Integer.bitCount(BitPerfectUsb.channelMask(n)!!))
        assertNull(BitPerfectUsb.channelMask(12))
    }
}
