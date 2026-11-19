package com.opentune.data

import com.opentune.data.settings.GLASS_STYLE_VERSION
import com.opentune.data.settings.GlassStyle
import com.opentune.data.settings.LyricsSource
import com.opentune.data.settings.SettingsState
import com.opentune.data.settings.migrateGlassStyle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test fun preloadBoostOnWifiDefaultsOffAndSurvivesSerialization() {
        assertFalse(json.decodeFromString<SettingsState>("{}").playback.preloadBoostOnWifi)
        val off = json.decodeFromString<SettingsState>("""{"playback":{"preloadBoostOnWifi":false}}""")
        assertFalse(off.playback.preloadBoostOnWifi)
        assertFalse(json.decodeFromString<SettingsState>(json.encodeToString(off)).playback.preloadBoostOnWifi)
    }

    @Test fun missingSavedSettingsUseTheNewDefaults() {
        for (body in listOf("{}", """{"playback":{},"lyrics":{}}""")) {
            val settings = json.decodeFromString<SettingsState>(body)
            assertTrue(settings.playback.stopOnTaskRemoved)
            assertFalse(settings.playback.pauseListeningHistory)
            assertEquals(com.opentune.data.history.ListeningHistoryTarget.YOUTUBE_MUSIC, settings.playback.listeningHistoryTarget)
            assertFalse(settings.playback.losslessUnmeteredOnly)
            assertTrue(settings.playback.losslessWifiOnly)
            assertTrue(settings.playback.preloadUpcoming)
            assertFalse(settings.playback.preloadBoostOnWifi)
            assertTrue(settings.lyrics.preferWordSynced)
            assertTrue(settings.lyrics.romanize)
            assertEquals(LyricsSource.entries.toList(), settings.lyrics.ordered.map { it.source })
            assertTrue(settings.lyrics.ordered.all { it.enabled })
        }
        // The iOS 26 look: Liquid Glass and the dock that minimizes.
        val ui = json.decodeFromString<SettingsState>("{}").ui
        assertEquals(GlassStyle.WEB, ui.glassStyle)
        assertEquals(GLASS_STYLE_VERSION, ui.glassStyleVersion)
        assertEquals(com.opentune.data.settings.DockMotion.MINIMIZE, ui.dockMotion)
    }

    @Test fun liquidGlassIsTheNewDefaultBesideFrosted() {
        assertEquals(GlassStyle.WEB, com.opentune.data.settings.InterfaceSettings().glassStyle)
        assertEquals(listOf(GlassStyle.WEB, GlassStyle.LIQUID), GlassStyle.entries.toList())
        assertEquals("Liquid Glass", GlassStyle.WEB.label)
        assertEquals("Frosted", GlassStyle.LIQUID.label)
        // Saved by name: Frosted keeps the name older settings were written with.
        assertEquals(GlassStyle.LIQUID, json.decodeFromString<SettingsState>("""{"ui":{"glassStyle":"LIQUID","glassStyleVersion":2}}""").ui.glassStyle)
        assertEquals(GlassStyle.WEB, json.decodeFromString<SettingsState>("""{"ui":{"glassStyle":"WEB","glassStyleVersion":2}}""").ui.glassStyle)
    }

    private fun loaded(saved: String): SettingsState =
        json.decodeFromJsonElement(SettingsState.serializer(), migrateGlassStyle(json.parseToJsonElement(saved)))

    @Test fun settingsSavedWithTheOldDefaultGlassMoveToLiquidGlassOnce() {
        // Saved before the version was written, with the old default (every default is written out),
        // and with the Liquid Glass switch and the playlist grid since dropped.
        val old = """{"ui":{"glassStyle":"LIQUID","liquidGlass":true,"playlistGrid":false,"dockLens":"JELLY"}}"""
        val moved = loaded(old)
        assertEquals(GlassStyle.WEB, moved.ui.glassStyle)
        assertEquals(GLASS_STYLE_VERSION, moved.ui.glassStyleVersion)
        // The rest of the file reads as it was.
        assertEquals(com.opentune.data.settings.DockLens.JELLY, moved.ui.dockLens)
        // Written back and loaded again, nothing more moves.
        val saved = json.encodeToString(moved)
        assertEquals(moved, loaded(saved))
        // Frosted (the old Liquid) chosen after the move is kept from then on.
        val chosen = json.encodeToString(moved.copy(ui = moved.ui.copy(glassStyle = GlassStyle.LIQUID)))
        assertEquals(GlassStyle.LIQUID, loaded(chosen).ui.glassStyle)
        assertEquals(GlassStyle.LIQUID, loaded(json.encodeToString(loaded(chosen))).ui.glassStyle)
    }

    @Test fun aDroppedGlassStyleReadsBackAsLiquidGlassAndKeepsTheRest() {
        for (style in listOf("FROSTED", "CLEAR", "HEAVY", "TINTED", "SMOKE")) {
            val body = """{"theme":{"pureBlack":false},"ui":{"glassStyle":"$style","dockLens":"GLIDE","reduceBlur":true}}"""
            // Through the migration, as the app loads it...
            val s = loaded(body)
            assertEquals(GlassStyle.WEB, s.ui.glassStyle)
            assertEquals(GLASS_STYLE_VERSION, s.ui.glassStyleVersion)
            assertEquals(com.opentune.data.settings.DockLens.GLIDE, s.ui.dockLens)
            assertTrue(s.ui.reduceBlur)
            assertFalse(s.theme.pureBlack)
            // ...and decoded as it is (already versioned), where the unknown name falls back on its own.
            val plain = json.decodeFromString<SettingsState>(body.replace("\"dockLens\"", "\"glassStyleVersion\":2,\"dockLens\""))
            assertEquals(GlassStyle.WEB, plain.ui.glassStyle)
            assertEquals(com.opentune.data.settings.DockLens.GLIDE, plain.ui.dockLens)
            assertFalse(plain.theme.pureBlack)
        }
        // Nothing saved for the style, or no interface settings at all: the new default.
        assertEquals(GlassStyle.WEB, loaded("""{"ui":{"dockLens":"GLIDE"}}""").ui.glassStyle)
        assertEquals(GlassStyle.WEB, loaded("{}").ui.glassStyle)
    }

    @Test fun theGlassMigrationLeavesWhatItDoesNotMoveAsItIs() {
        val current = json.parseToJsonElement("""{"ui":{"glassStyle":"LIQUID","glassStyleVersion":2}}""")
        assertSame(current, migrateGlassStyle(current))
        val empty = json.parseToJsonElement("{}")
        assertSame(empty, migrateGlassStyle(empty))
        val notSettings = json.parseToJsonElement("[1,2]")
        assertSame(notSettings, migrateGlassStyle(notSettings))
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

    @Test fun settingsSavedBeforeTheWifiOnlyDefaultKeepFlacOffMobileData() {
        // An older version saved its old "Unmetered networks only" off; the new switch starts on all the same.
        val settings = json.decodeFromString<SettingsState>("""{"playback":{"losslessUnmeteredOnly":false}}""")
        assertTrue(settings.playback.losslessWifiOnly)
    }

    @Test fun pageButtonsDefaultToGlassAndAccentSurvivesBackup() {
        assertEquals(com.opentune.data.settings.PageButtons.GLASS, json.decodeFromString<SettingsState>("{}").ui.pageButtons)
        // Settings saved before the choice existed, or by a newer version with a look this one lacks, read back as glass.
        assertEquals(com.opentune.data.settings.PageButtons.GLASS, json.decodeFromString<SettingsState>("""{"ui":{"listBackground":"PLAIN"}}""").ui.pageButtons)
        assertEquals(com.opentune.data.settings.PageButtons.GLASS, json.decodeFromString<SettingsState>("""{"ui":{"pageButtons":"CHROME"}}""").ui.pageButtons)
        val accent = json.decodeFromString<SettingsState>("""{"ui":{"pageButtons":"ACCENT"}}""")
        assertEquals(com.opentune.data.settings.PageButtons.ACCENT, accent.ui.pageButtons)
        assertEquals(com.opentune.data.settings.PageButtons.ACCENT, json.decodeFromString<SettingsState>(json.encodeToString(accent)).ui.pageButtons)
    }
}
