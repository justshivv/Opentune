package com.opentune.data

import com.opentune.data.settings.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InterfaceDesignSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    @Test fun oldBackupsKeepTheirDesignAndEveryIndividualAnimation() {
        val old = """{"playerStyle":"VINYL","dockMotion":"CASCADE","dockLens":"JELLY","pageTransition":"ZOOM","playerMotion":"BOUNCY","coverChange":"CUBE","reduceAnimation":true,"liquidGlass":false,"lyricsTextScale":1.3}"""
        val restored = json.decodeFromString<InterfaceSettings>(old)
        assertEquals(InterfaceDesign.CURRENT, restored.design.style)
        assertEquals(PlayerStyle.VINYL, restored.playerStyle)
        assertEquals(DockMotion.CASCADE, restored.dockMotion)
        assertEquals(CoverChange.CUBE, restored.coverChange)
        assertTrue(restored.reduceAnimation)
        assertFalse(restored.liquidGlass)
        assertEquals(1.3f, restored.lyricsTextScale, 0f)
    }

    @Test fun switchingAndRevertingOnlyChangesAdditiveFields() {
        val before = InterfaceSettings(playerStyle = PlayerStyle.CASSETTE, coverChange = CoverChange.TOSS,
            dockMotion = DockMotion.RETRACT, dockLens = DockLens.JELLY, playerMotion = PlayerMotion.ECHO,
            reduceAnimation = true, reduceBlur = true, hideVolumeBar = true, movingCover = false, hapticStrength = .2f)
        InterfaceDesign.entries.forEach { style ->
            val options = DesignOptions(style, DesignMotion.EXPRESSIVE, DesignDensity.COMPACT, DesignDockVisibility.ALWAYS)
            val changed = before.withDesign(options)
            assertEquals(before, changed.copy(design = before.design))
            assertEquals(changed, json.decodeFromString<InterfaceSettings>(json.encodeToString(changed)))
            assertEquals(before, changed.withDesign(DesignOptions()))
        }
    }

    @Test fun unknownFutureDesignValuesFallBackWithoutLosingKnownPreferences() {
        val restored = json.decodeFromString<InterfaceSettings>("""{"design":{"style":"FUTURE","motion":"FUTURE"},"hideVolumeBar":true}""")
        assertEquals(DesignOptions(), restored.design)
        assertTrue(restored.hideVolumeBar)
    }

    @Test fun completeBackupsRoundTripWithOptionalDesigns() {
        val state = SettingsState(ui = InterfaceSettings().withDesign(DesignOptions(InterfaceDesign.GLASS, DesignMotion.FLUID)))
        assertEquals(state, json.decodeFromString<SettingsState>(json.encodeToString(state)))
        assertEquals(InterfaceDesign.CURRENT, json.decodeFromString<SettingsState>("{}").ui.design.style)
    }
}
