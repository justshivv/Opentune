package com.opentune.ui.library

import com.opentune.data.library.SmartMatch
import com.opentune.data.library.SmartPlaylists
import com.opentune.data.library.SmartRule
import com.opentune.data.library.SmartRules
import com.opentune.data.library.SmartSort
import com.opentune.data.library.SmartSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartSummaryTest {
    @Test fun describesTheRulesInOnePlainSentence() {
        assertEquals("Songs you played 3+ times in the last month",
            smartSummary(SmartPlaylists.presets.first { it.first == "On repeat" }.second))
        assertEquals("Liked songs you haven't played in the last 3 months",
            smartSummary(SmartPlaylists.presets.first { it.first == "Forgotten favourites" }.second))
        assertEquals("Lossless downloads by Moss Lantern",
            smartSummary(SmartRules(SmartSource.DOWNLOADS, conditions = listOf(SmartRule.Lossless, SmartRule.Artist(" Moss Lantern ")), sort = SmartSort.ARTIST, limit = null)))
        assertEquals("Songs you've played", smartSummary(SmartRules(SmartSource.HISTORY)))
        assertEquals("Every song on this phone", smartSummary(SmartRules()))
    }

    @Test fun joinsRulesWithAndOrOr() {
        assertEquals("Songs you played in the last 3 months and you haven't played in the last month",
            smartSummary(SmartRules(SmartSource.HISTORY, conditions = listOf(SmartRule.Plays(1, 90), SmartRule.NotPlayedFor(30)))))
        assertEquals("Songs by Moss Lantern or by Tide Pool Choir",
            smartSummary(SmartRules(match = SmartMatch.ANY, conditions = listOf(SmartRule.Artist("Moss Lantern"), SmartRule.Artist("Tide Pool Choir")))))
        // Under Any, liked is one of the choices, not what every song is.
        assertEquals("Songs you liked or you've played 10+ times",
            smartSummary(SmartRules(match = SmartMatch.ANY, conditions = listOf(SmartRule.Liked, SmartRule.Plays(10)))))
    }

    @Test fun saysNothingTwice() {
        // A lossless download is a download; liked songs from Liked are liked.
        assertEquals("Lossless downloads", smartSummary(SmartRules(conditions = listOf(SmartRule.Downloaded, SmartRule.Lossless))))
        assertEquals("Liked songs you haven't played in the last 3 months",
            smartSummary(SmartRules(SmartSource.LIKED, conditions = listOf(SmartRule.Liked, SmartRule.NotPlayedFor(90)))))
        assertEquals("Liked downloads", smartSummary(SmartRules(SmartSource.DOWNLOADS, conditions = listOf(SmartRule.Liked))))
    }

    @Test fun eachRuleReadsOnItsOwn() {
        assertEquals("Played 10+ times in the last month", ruleLabel(SmartRule.Plays(10, 30)))
        assertEquals("Played in the last week", ruleLabel(SmartRule.Plays(1, 7)))
        assertEquals("Played 3+ times", ruleLabel(SmartRule.Plays(3)))
        assertEquals("Not played in the last year", ruleLabel(SmartRule.NotPlayedFor(365)))
        assertEquals("New to you in the last 45 days", ruleLabel(SmartRule.NewWithin(45)))
        assertEquals("By Glass Harbour", ruleLabel(SmartRule.Artist("Glass Harbour ")))
    }

    @Test fun problemsAreSaidPlainly() {
        assertEquals("Give it a name to save it", smartProblem(" ", SmartRules()))
        assertEquals("Two rules can't both be met",
            smartProblem("x", SmartRules(conditions = listOf(SmartRule.Plays(1, 30), SmartRule.NotPlayedFor(30)))))
        assertEquals(null, smartProblem("x", SmartRules(match = SmartMatch.ANY, conditions = listOf(SmartRule.Plays(1, 30), SmartRule.NotPlayedFor(30)))))
    }
}
