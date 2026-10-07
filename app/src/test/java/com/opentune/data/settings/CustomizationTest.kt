package com.opentune.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class CustomizationTest {
    @Test fun retiredSettingsDecodeAndMigrateWithoutLosingOtherPreferences() {
        val old = Json.decodeFromString<SettingsState>("""{
            "ui":{"liquidGlass":true,"dockMotion":"DISSOLVE","dockLens":"JELLY",
                  "playerStyle":"HALO","controlStyle":"GLASS","glassStyle":"CLEAR",
                  "pageTransition":"ZOOM","playerMotion":"BOUNCY","lyricsAnimation":"BOUNCE",
                  "reduceAnimation":true,"reduceBlur":true,"hapticStrength":0.2},
            "theme":{"paletteStyle":"FruitSalad","playerBackground":"BLUR"},
            "playback":{"crossfadeSeconds":7},"recentSearches":["night drive"]
        }""")
        val migrated = old.curated()
        assertFalse(migrated.ui.liquidGlass)
        assertTrue(migrated.ui.dockMotion in Customization.docks)
        assertTrue(migrated.ui.dockLens in Customization.lenses)
        assertTrue(migrated.ui.playerStyle in Customization.players)
        assertTrue(migrated.ui.controlStyle in Customization.controls)
        assertTrue(migrated.ui.glassStyle in Customization.glass)
        assertTrue(migrated.ui.pageTransition in Customization.pages)
        assertTrue(migrated.ui.playerMotion in Customization.playerMotion)
        assertTrue(migrated.ui.lyricsAnimation in Customization.lyrics)
        assertTrue(migrated.theme.paletteStyle in Customization.palettes)
        assertTrue(migrated.theme.playerBackground in Customization.backgrounds)
        assertEquals(old.playback, migrated.playback)
        assertEquals(old.recentSearches, migrated.recentSearches)
        assertEquals(old.ui.reduceAnimation, migrated.ui.reduceAnimation)
        assertEquals(old.ui.reduceBlur, migrated.ui.reduceBlur)
        assertEquals(old.ui.hapticStrength, migrated.ui.hapticStrength, 0f)
        assertEquals(migrated, migrated.curated())
    }

    @Test fun appearancePresetsPreserveAccessibilityAndMotionChoices() {
        val ui = InterfaceSettings(reduceAnimation = true, reduceBlur = true, hapticStrength = 0f, autoHideDock = false, dockMotion = DockMotion.RETRACT)
        AppearancePreset.entries.forEach { preset ->
            val result = preset.ui(ui)
            assertEquals(ui.copy(glassStyle = result.glassStyle), result)
            assertTrue(preset.matches(preset.theme(ThemeSettings()), result))
            assertTrue(preset.theme(ThemeSettings()).paletteStyle in Customization.palettes)
        }
    }

    @Test fun motionProfilesPreserveReducedMotionAndAlwaysVisibleDock() {
        val ui = InterfaceSettings(reduceAnimation = true, reduceBlur = true, autoHideDock = false, lyricsTextScale = 1.4f)
        MotionProfile.entries.forEach { profile ->
            val result = profile.apply(ui)
            assertTrue(result.reduceAnimation)
            assertTrue(result.reduceBlur)
            assertFalse(result.autoHideDock)
            assertEquals(ui.lyricsTextScale, result.lyricsTextScale, 0f)
            assertTrue(profile.matches(result))
            assertTrue(result.dockMotion in Customization.docks)
            assertTrue(result.playerMotion in Customization.playerMotion)
        }
    }

    @Test fun manualAdjustmentsAreRecognizedAsCustom() {
        val fluid = MotionProfile.FLUID.apply(InterfaceSettings())
        assertFalse(MotionProfile.FLUID.matches(fluid.copy(dockMotion = DockMotion.RETRACT)))
        val theme = AppearancePreset.PAPER.theme(ThemeSettings())
        val ui = AppearancePreset.PAPER.ui(InterfaceSettings())
        assertFalse(AppearancePreset.PAPER.matches(theme.copy(seedColor = 123), ui))
    }
}
