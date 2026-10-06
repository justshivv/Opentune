package com.opentune.data

import com.opentune.data.settings.LyricsSettings
import com.opentune.data.settings.LyricsSource
import com.opentune.data.settings.LyricsSourceEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSettingsTest {
    @Test
    fun savedOrderIsKept() {
        val s = LyricsSettings(sources = listOf(LyricsSourceEntry(LyricsSource.YOUTUBE_MUSIC), LyricsSourceEntry(LyricsSource.LRCLIB, enabled = false)))
        // A source added in a later version (KuGou) joins at the end of a saved order.
        assertEquals(listOf(LyricsSource.YOUTUBE_MUSIC, LyricsSource.LRCLIB, LyricsSource.KUGOU), s.ordered.map { it.source })
        assertEquals(false, s.ordered[1].enabled)
    }

    @Test
    fun missingSourcesAreAppendedAndDuplicatesDropped() {
        val s = LyricsSettings(sources = listOf(LyricsSourceEntry(LyricsSource.YOUTUBE_MUSIC), LyricsSourceEntry(LyricsSource.YOUTUBE_MUSIC, enabled = false)))
        assertEquals(listOf(LyricsSource.YOUTUBE_MUSIC, LyricsSource.LRCLIB, LyricsSource.KUGOU), s.ordered.map { it.source })
        assertEquals(true, s.ordered.first().enabled)
    }
}
