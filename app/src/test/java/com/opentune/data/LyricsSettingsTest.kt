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
        assertEquals(listOf(LyricsSource.YOUTUBE_MUSIC, LyricsSource.LRCLIB), s.ordered.map { it.source })
        assertEquals(false, s.ordered.last().enabled)
    }

    @Test
    fun missingSourcesAreAppendedAndDuplicatesDropped() {
        val s = LyricsSettings(sources = listOf(LyricsSourceEntry(LyricsSource.YOUTUBE_MUSIC), LyricsSourceEntry(LyricsSource.YOUTUBE_MUSIC, enabled = false)))
        assertEquals(listOf(LyricsSource.YOUTUBE_MUSIC, LyricsSource.LRCLIB), s.ordered.map { it.source })
        assertEquals(true, s.ordered.first().enabled)
    }
}
