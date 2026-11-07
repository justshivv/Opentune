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
}
