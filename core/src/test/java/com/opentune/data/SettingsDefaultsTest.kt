package com.opentune.data

import com.opentune.data.settings.LyricsSource
import com.opentune.data.settings.SettingsState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    @Test fun lyricsPreviewDefaultsOnAndExplicitOffSurvivesBackup() {
        assertTrue(json.decodeFromString<SettingsState>("{}").ui.lyricsPreview)
        val off = json.decodeFromString<SettingsState>("""{"ui":{"lyricsPreview":false}}""")
        assertFalse(off.ui.lyricsPreview)
        assertFalse(json.decodeFromString<SettingsState>(json.encodeToString(off)).ui.lyricsPreview)
    }

    @Test fun missingSavedSettingsUseTheNewDefaults() {
        for (body in listOf("{}", """{"playback":{},"lyrics":{}}""")) {
            val settings = json.decodeFromString<SettingsState>(body)
            assertTrue(settings.playback.stopOnTaskRemoved)
            assertFalse(settings.playback.pauseListeningHistory)
            assertEquals(com.opentune.data.history.ListeningHistoryTarget.YOUTUBE_MUSIC, settings.playback.listeningHistoryTarget)
            assertFalse(settings.playback.losslessUnmeteredOnly)
            assertTrue(settings.lyrics.preferWordSynced)
            assertTrue(settings.lyrics.romanize)
            assertEquals(LyricsSource.entries.toList(), settings.lyrics.ordered.map { it.source })
            assertTrue(settings.lyrics.ordered.all { it.enabled })
        }
        // The iOS 26 look: Liquid Glass and the dock that minimizes.
        val ui = json.decodeFromString<SettingsState>("{}").ui
        assertTrue(ui.liquidGlass)
        assertEquals(com.opentune.data.settings.GlassStyle.LIQUID, ui.glassStyle)
        assertEquals(com.opentune.data.settings.DockMotion.MINIMIZE, ui.dockMotion)
    }

    @Test fun aPlayerBackgroundThatWasDroppedReadsBackAsTheDefault() {
        val settings = json.decodeFromString<SettingsState>("""{"theme":{"playerBackground":"PLAIN","pureBlack":false}}""")
        assertEquals(com.opentune.data.settings.PlayerBackground.BLUR, settings.theme.playerBackground)
        assertFalse(settings.theme.pureBlack)
        // Blur was dropped once and is back: an old choice of it holds again.
        assertEquals(
            com.opentune.data.settings.PlayerBackground.BLUR,
            json.decodeFromString<SettingsState>("""{"theme":{"playerBackground":"BLUR"}}""").theme.playerBackground,
        )
        assertEquals(
            com.opentune.data.settings.PlayerBackground.MESH,
            json.decodeFromString<SettingsState>("""{"theme":{"playerBackground":"MESH"}}""").theme.playerBackground,
        )
    }

    @Test fun aDroppedDockMotionReadsBackAsTheDefault() {
        for (old in listOf("FOLD", "GLIDE", "RETRACT", "CASCADE")) {
            val settings = json.decodeFromString<SettingsState>("""{"ui":{"dockMotion":"$old","dockLens":"JELLY"}}""")
            assertEquals(com.opentune.data.settings.DockMotion.MINIMIZE, settings.ui.dockMotion)
            // The rest of the file still reads.
            assertEquals(com.opentune.data.settings.DockLens.JELLY, settings.ui.dockLens)
        }
    }

    @Test fun anUpdateAndBackupKeepExplicitSavedChoices() {
        val body = """{
            "playback":{"stopOnTaskRemoved":false,"losslessUnmeteredOnly":true,"pauseListeningHistory":true,"listeningHistoryTarget":"YOUTUBE_MUSIC"},
            "lyrics":{"preferWordSynced":false,"romanize":false,
                "sources":[{"source":"SIMPMUSIC","enabled":false}],
                "offsets":{"4NRXx6U8ABQ":500},"chosen":{"4NRXx6U8ABQ":"SIMPMUSIC"}}
        }"""
        val settings = json.decodeFromString<SettingsState>(body)
        assertFalse(settings.playback.stopOnTaskRemoved)
        assertTrue(settings.playback.pauseListeningHistory)
        assertEquals(com.opentune.data.history.ListeningHistoryTarget.YOUTUBE_MUSIC, settings.playback.listeningHistoryTarget)
        assertTrue(settings.playback.losslessUnmeteredOnly)
        assertFalse(settings.lyrics.preferWordSynced)
        assertFalse(settings.lyrics.romanize)
        // lrc.red and Paxsenix, new since this was saved, go in ahead; the saved entry comes next, still off.
        assertEquals(listOf(LyricsSource.LRC_RED, LyricsSource.PAXSENIX, LyricsSource.SIMPMUSIC), settings.lyrics.ordered.take(3).map { it.source })
        assertFalse(settings.lyrics.ordered[2].enabled)
        assertEquals(500L, settings.lyrics.offsets["4NRXx6U8ABQ"])
        assertEquals(LyricsSource.SIMPMUSIC, settings.lyrics.chosen["4NRXx6U8ABQ"])
        assertEquals(settings, json.decodeFromString<SettingsState>(json.encodeToString(settings)))
    }
}
