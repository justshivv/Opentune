package com.opentune.data.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixSnapshotsTest {
    private val hour = 60 * 60 * 1000L
    private fun saved(at: Long, vararg ids: String) =
        MixSnapshots.Saved(at, ids.map { MixSnapshots.SavedMix(it, it, "", emptyList()) })

    @Test fun keptForTwelveHours() {
        val s = saved(0, "genre:punjabi", "genre:bollywood")
        assertFalse(MixSnapshots.due(s, listOf("genre:punjabi", "genre:bollywood"), 11 * hour))
        // Mixes made again from the same genres in another order or with other songs: still kept.
        assertFalse(MixSnapshots.due(s, listOf("genre:bollywood"), 11 * hour))
        assertTrue(MixSnapshots.due(s, listOf("genre:punjabi"), 12 * hour))
    }

    @Test fun aNewGenreIsAddedStraightAway() {
        assertTrue(MixSnapshots.due(saved(0, "genre:punjabi"), listOf("genre:punjabi", "genre:hip-hop"), hour))
    }

    @Test fun nothingSavedOrAClockGoneBackIsDue() {
        assertTrue(MixSnapshots.due(null, listOf("a"), 0))
        assertTrue(MixSnapshots.due(saved(0), listOf("a"), hour))
        assertTrue(MixSnapshots.due(saved(5 * hour, "a"), listOf("a"), hour))
    }
}
