package com.opentune.data

import com.opentune.data.settings.LyricsSettings
import com.opentune.data.settings.LyricsSource
import com.opentune.data.settings.LyricsSourceEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSettingsTest {
    @Test
    fun simpMusicJoinsExistingInstallsWithoutChangingTheirChoices() {
        val previous = LyricsSource.entries.filter { it != LyricsSource.SIMPMUSIC }
            .reversed().map { LyricsSourceEntry(it, enabled = it != LyricsSource.LYRICSPLUS) }
        val settings = LyricsSettings(sources = previous)
        assertEquals(previous, settings.ordered.dropLast(1))
        assertEquals(LyricsSourceEntry(LyricsSource.SIMPMUSIC), settings.ordered.last())
    }

    @Test
    fun savedOrderIsKept() {
        val s = LyricsSettings(sources = listOf(LyricsSourceEntry(LyricsSource.YOUTUBE_MUSIC), LyricsSourceEntry(LyricsSource.LRCLIB, enabled = false)))
        // lrc.red and Paxsenix go in first; other sources added in later versions (LyricsPlus, Better Lyrics, SimpMusic, KuGou, NetEase, Unison, YouTube captions) join at the end of a saved order.
        assertEquals(listOf(LyricsSource.LRC_RED, LyricsSource.PAXSENIX, LyricsSource.YOUTUBE_MUSIC, LyricsSource.LRCLIB, LyricsSource.LYRICSPLUS, LyricsSource.BETTERLYRICS, LyricsSource.SIMPMUSIC, LyricsSource.KUGOU, LyricsSource.NETEASE, LyricsSource.UNISON, LyricsSource.YOUTUBE_CAPTIONS), s.ordered.map { it.source })
        assertEquals(false, s.ordered[3].enabled)
    }

    @Test
    fun missingSourcesAreAppendedAndDuplicatesDropped() {
        val s = LyricsSettings(sources = listOf(LyricsSourceEntry(LyricsSource.YOUTUBE_MUSIC), LyricsSourceEntry(LyricsSource.YOUTUBE_MUSIC, enabled = false)))
        assertEquals(listOf(LyricsSource.LRC_RED, LyricsSource.PAXSENIX, LyricsSource.YOUTUBE_MUSIC, LyricsSource.LRCLIB, LyricsSource.LYRICSPLUS, LyricsSource.BETTERLYRICS, LyricsSource.SIMPMUSIC, LyricsSource.KUGOU, LyricsSource.NETEASE, LyricsSource.UNISON, LyricsSource.YOUTUBE_CAPTIONS), s.ordered.map { it.source })
        assertEquals(true, s.ordered[2].enabled)
    }
}
