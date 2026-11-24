package com.opentune.playback

import com.opentune.data.settings.EqualizerSettings
import com.opentune.data.settings.HeadphoneEq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OutputEqualizerTest {
    private val hd650 = HeadphoneEq("HD 650", "oratory1990", -6f, emptyList())
    private val bassy = List(15) { if (it < 3) 4f else 0f }

    @Test fun aChangeOnTheSameOutputIsKeptForIt() {
        val eq = EqualizerSettings(enabled = true, bands = bassy, perOutput = true)
        val next = OutputEqualizer.step(eq, "wired", "wired")
        assertEquals(eq.snapshot(), next.outputs["wired"])
        assertEquals(eq.snapshot(), next.snapshot())
    }

    @Test fun nothingChangedLeavesItAlone() {
        val eq = EqualizerSettings(enabled = true, perOutput = true).let { it.copy(outputs = mapOf("wired" to it.snapshot())) }
        assertSame(eq, OutputEqualizer.step(eq, "wired", "wired"))
    }

    @Test fun aNewOutputKeepsTheCurveButNotTheHeadphoneCorrection() {
        val eq = EqualizerSettings(enabled = true, bands = bassy, headphone = hd650, perOutput = true)
        val next = OutputEqualizer.step(eq, "wired", "bt:Car")
        assertNull(next.headphone)
        assertEquals(bassy, next.bands)
        assertEquals(hd650, next.outputs["wired"]?.headphone)
    }

    @Test fun goingBackBringsThatOutputsSettingsBack() {
        val onWired = EqualizerSettings(enabled = true, bands = bassy, headphone = hd650, perOutput = true)
        val inCar = OutputEqualizer.step(onWired, "wired", "bt:Car").copy(enabled = false)
        val carSaved = OutputEqualizer.step(inCar, "bt:Car", "bt:Car")
        val back = OutputEqualizer.step(carSaved, "bt:Car", "wired")
        assertEquals(onWired.snapshot(), back.snapshot())
        assertEquals(false, back.outputs["bt:Car"]?.enabled)
    }

    @Test fun theFirstOutputSeenAfterAStartGetsItsOwnSettings() {
        val saved = EqualizerSettings(enabled = true, bands = bassy, headphone = hd650, perOutput = true)
        val eq = saved.copy(outputs = mapOf("speaker" to EqualizerSettings().snapshot(), "wired" to saved.snapshot()))
        val next = OutputEqualizer.step(eq, null, "speaker")
        assertEquals(EqualizerSettings().snapshot(), next.snapshot())
    }
}
