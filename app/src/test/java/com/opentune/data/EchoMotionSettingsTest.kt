package com.opentune.data

import com.opentune.data.settings.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EchoMotionSettingsTest {
    @Test fun presetPreservesAccessibilityPlaybackAndOtherPreferences() {
        val before = SettingsState(
            theme = ThemeSettings(mode = ThemeMode.LIGHT, seedColor = 0xFF123456.toInt()),
            ui = InterfaceSettings(reduceAnimation = true, movingCover = false, controlStyle = ControlStyle.ORBIT),
            recentSearches = listOf("my music"),
        )
        val after = before.withEchoMotion()
        assertEquals(PlayerBackground.ECHO_GLOW, after.theme.playerBackground)
        assertEquals(ControlStyle.ECHO, after.ui.controlStyle)
        assertEquals(PlayerMotion.ECHO, after.ui.playerMotion)
        assertEquals(LyricsAnimation.ECHO, after.ui.lyricsAnimation)
        assertEquals(before, after.copy(
            theme = after.theme.copy(playerBackground = before.theme.playerBackground),
            ui = after.ui.copy(controlStyle = before.ui.controlStyle, playerMotion = before.ui.playerMotion, lyricsAnimation = before.ui.lyricsAnimation),
        ))
        assertEquals(after, after.withEchoMotion())
    }

    @Test fun newChoicesSurviveBackupAndOldDefaultsRemainUnchanged() {
        val preset = SettingsState().withEchoMotion()
        assertEquals(preset, Json.decodeFromString<SettingsState>(Json.encodeToString(preset)))
        assertEquals(SettingsState(), Json.decodeFromString<SettingsState>("{}"))
        assertEquals(ControlStyle.BLOOM, SettingsState().ui.controlStyle)
    }
}
