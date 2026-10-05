package com.opentune.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun comparesNumberByNumber() {
        assertTrue(UpdateCheck.isNewer("1.10", "1.9"))
        assertTrue(UpdateCheck.isNewer("2.0.1", "2.0"))
        assertFalse(UpdateCheck.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("0.9", "1.0"))
    }
}
