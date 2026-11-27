package com.opentune.data.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class LengthTextTest {
    @Test fun aSongLengthReadsAsAListingWritesIt() {
        assertEquals("3:45", lengthText(225_400))
        assertEquals("0:09", lengthText(9_999))
        assertEquals("1:02:03", lengthText(3_723_000))
    }

    @Test fun theOtherDevicesReadItBackToTheSameLength() {
        val track = CTrack("abcdefghijk", "Paper Lanterns", length = lengthText(201_000))
        assertEquals(201_000L, track.lengthMs)
    }
}
