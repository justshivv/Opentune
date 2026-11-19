package com.opentune.ui.library

import com.opentune.data.library.SmartPlaylists
import com.opentune.data.library.SmartRules
import com.opentune.data.library.SmartSort
import com.opentune.data.library.SmartSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartSummaryTest {
    @Test fun describesTheRulesInOneLine() {
        assertEquals("Songs you've played · played 3+ times in a month · most played · up to 50",
            smartSummary(SmartPlaylists.presets.first { it.first == "On repeat" }.second))
        assertEquals("Downloads · lossless · by AP Dhillon · artist, a to z",
            smartSummary(SmartRules(SmartSource.DOWNLOADS, losslessOnly = true, artist = " AP Dhillon ", sort = SmartSort.ARTIST, limit = null)))
    }

    @Test fun saysNothingTwice() {
        // A lossless download is a download; liked songs from Liked are liked.
        assertEquals("Everything on this phone · lossless · most played",
            smartSummary(SmartRules(downloadedOnly = true, losslessOnly = true, limit = null)))
        assertEquals("Liked songs · not played for 3 months · longest since played · up to 50",
            smartSummary(SmartRules(SmartSource.LIKED, likedOnly = true, notPlayedForDays = 90, sort = SmartSort.LONGEST_UNPLAYED)))
    }
}
