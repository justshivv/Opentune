package com.opentune.data.autoeq

import com.opentune.data.settings.EqualizerSettings
import com.opentune.data.settings.FilterType
import com.opentune.playback.dsp.DspParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqTest {
    /** AutoEq's published profile for the Sennheiser HD 650, measured by oratory1990. */
    private val hd650 = """
        Preamp: -6.1 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
        Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
        Filter 3: ON PK Fc 118 Hz Gain -3.1 dB Q 0.50
        Filter 4: ON PK Fc 37 Hz Gain 0.7 dB Q 3.96
        Filter 5: ON PK Fc 3169 Hz Gain -1.7 dB Q 3.89
        Filter 6: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
        Filter 7: ON PK Fc 1227 Hz Gain -1.2 dB Q 2.53
        Filter 8: ON PK Fc 2055 Hz Gain 1.2 dB Q 3.23
        Filter 9: ON PK Fc 587 Hz Gain 0.4 dB Q 1.19
        Filter 10: ON PK Fc 5332 Hz Gain -1.1 dB Q 5.75
    """.trimIndent()

    @Test
    fun parsesAParametricProfile() {
        val eq = AutoEq.parseParametric(hd650, "Sennheiser HD 650", "oratory1990")
        assertEquals(-6.1f, eq.preampDb, 0.001f)
        assertEquals(10, eq.filters.size)
        assertEquals(FilterType.LOW_SHELF, eq.filters[0].type)
        assertEquals(105f, eq.filters[0].freqHz, 0f)
        assertEquals(6.4f, eq.filters[0].gainDb, 0.001f)
        assertEquals(FilterType.HIGH_SHELF, eq.filters[5].type)
        assertEquals(5.75f, eq.filters[9].q, 0.001f)
    }

    @Test
    fun readsIndexLinesWithBracketsInTheName() {
        val index = """
            # Index
            - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
            - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
            - [Steven Slate Audio VSX (passive plugin inactive))](./oratory1990/over-ear/Steven%20Slate%20Audio%20VSX%20(passive%20plugin%20inactive))) by oratory1990
        """.trimIndent()
        val entries = AutoEq.parseIndex(index)
        assertEquals(3, entries.size)
        assertEquals("./oratory1990/over-ear/Steven%20Slate%20Audio%20VSX%20(passive%20plugin%20inactive))", entries[2].path)
        assertEquals(AutoEq.Entry("Sennheiser HD 650", "oratory1990", "./oratory1990/over-ear/Sennheiser%20HD%20650"), entries[0])
        assertEquals("1MORE Aero (ANC Off)", entries[1].name)
        assertEquals("HypetheSonics", entries[1].source)
        assertEquals("./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)", entries[1].path)
    }

    @Test
    fun profileUrlEncodesEachPart() {
        val url = AutoEq.profileUrl(AutoEq.Entry("Sennheiser HD 650", "oratory1990", "./oratory1990/over-ear/Sennheiser%20HD%20650"))
        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/oratory1990/over-ear/" +
                "Sennheiser%20HD%20650/Sennheiser%20HD%20650%20ParametricEQ.txt",
            url,
        )
    }

    @Test
    fun searchNeedsEveryWord() {
        val all = listOf(
            AutoEq.Entry("Sennheiser HD 650", "a", "./a"),
            AutoEq.Entry("Sennheiser HD 600", "a", "./b"),
            AutoEq.Entry("Sony WH-1000XM5", "a", "./c"),
        )
        assertEquals(listOf("Sennheiser HD 650"), AutoEq.search(all, "hd 650").map { it.name })
        assertTrue(AutoEq.search(all, "  ").isEmpty())
    }

    @Test
    fun theDspRunsTheCorrectionWithItsPreampEvenWithTheEqOff() {
        val eq = AutoEq.parseParametric(hd650, "Sennheiser HD 650", "oratory1990")
        val params = DspParams(equalizer = EqualizerSettings(enabled = false, headphone = eq))
        assertFalse(params.isNeutral)
        assertEquals(10, params.stages(48_000).size)
        assertEquals(Math.pow(10.0, -6.1 / 20).toFloat(), params.preampGain, 0.001f)
        // The low shelf really lifts the bass: well above 0 dB at 30 Hz, before the preamp.
        val low = params.stages(48_000).map { it() }.sumOf { it.magnitudeDb(30.0, 48_000.0) }
        assertTrue("bass lift was $low dB", low > 4.0)
        // And without a profile the chain is untouched, as before.
        assertTrue(DspParams(equalizer = EqualizerSettings()).isNeutral)
    }
}
