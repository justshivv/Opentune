package com.opentune.data

import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.liteModeApplied
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lite mode gives back the switches it turned off, as the user had them. */
class LiteModeAppliedTest {
    @Test fun turningLiteOffRestoresTheUsersSwitches() {
        val ui = InterfaceSettings().copy(movingCover = false, blurLyrics = false)
        val on = liteModeApplied(ui, true)
        assertTrue(on.liteMode)
        assertFalse(on.coverGlow)
        val off = liteModeApplied(on, false)
        assertFalse(off.liteMode)
        // As the user had them: these two stay off, the glow Lite turned off comes back.
        assertFalse(off.movingCover)
        assertFalse(off.blurLyrics)
        assertTrue(off.coverGlow)
    }

    @Test fun turningLiteOffWhenItWasNeverOnChangesNothingElse() {
        val ui = InterfaceSettings().copy(movingCover = false)
        val off = liteModeApplied(ui, false)
        assertFalse(off.liteMode)
        assertFalse(off.movingCover)
    }

    @Test fun turningLiteOffWithoutASnapshotRestoresTheDefaults() {
        // Saved by an older build: Lite is on, its switches are in effect, and there is no snapshot.
        val ui = InterfaceSettings().copy(liteMode = true, reduceBlur = true, movingCover = false)
        val off = liteModeApplied(ui, false)
        assertFalse(off.liteMode)
        assertFalse(off.reduceBlur)
        assertTrue(off.movingCover)
    }
}
