package com.opentune.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTrackingTest {
    @Test fun latinGreekAndCyrillicKeepTheTightTracking() {
        assertTrue(tightTrackingFits("I will always love you"))
        assertTrue(tightTrackingFits("Te quiero, ¿sabes? 123"))
        assertTrue(tightTrackingFits("Σ' αγαπώ"))
        assertTrue(tightTrackingFits("Я люблю тебя"))
    }

    @Test fun joinedAndOtherScriptsGetTheFontsOwnSpacing() {
        // Urdu and Arabic, the scripts users reported words running together in.
        assertFalse(tightTrackingFits("میں تم سے پیار کرتا ہوں"))
        assertFalse(tightTrackingFits("أحبك دائما"))
        assertFalse(tightTrackingFits("मैं तुमसे प्यार करता हूँ"))
        // A mixed line counts as the script that needs room.
        assertFalse(tightTrackingFits("Love you جانم"))
    }
}
