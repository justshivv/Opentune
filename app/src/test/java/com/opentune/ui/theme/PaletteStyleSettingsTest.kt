package com.opentune.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.toDynamicScheme
import com.materialkolor.scheme.DynamicScheme
import com.opentune.data.settings.PaletteStyleOption
import com.opentune.data.settings.SEED_COLORS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The palette styles Settings offers, rendered as the theme renders them, for every accent
 * (and a dull one): each looks unlike every other, and the two that were dropped did not.
 */
class PaletteStyleSettingsTest {
    private val seeds = SEED_COLORS + 0xFF607D8B.toInt() + 0xFFB0A090.toInt()

    /** The six accent roles a palette style decides: primary, secondary and tertiary, each with its container. */
    private fun accents(s: DynamicScheme) = listOf(
        s.primary, s.primaryContainer, s.secondary, s.secondaryContainer, s.tertiary, s.tertiaryContainer,
    )

    private fun lab(argb: Int): DoubleArray {
        val c = (0..2).map { i ->
            val v = ((argb shr (16 - 8 * i)) and 255) / 255.0
            if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        val x = f((0.4124 * c[0] + 0.3576 * c[1] + 0.1805 * c[2]) / 0.95047)
        val y = f(0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2])
        val z = f((0.0193 * c[0] + 0.1192 * c[1] + 0.9505 * c[2]) / 1.08883)
        return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }

    private fun deltaE(a: Int, b: Int): Double {
        val p = lab(a); val q = lab(b)
        return sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))
    }

    /** How many of the accent roles tell [a] from [b] at a glance (CIELAB ΔE over 6, averaged over seeds, light and dark). */
    private fun rolesThatDiffer(a: PaletteStyle, b: PaletteStyle): Int {
        val sums = DoubleArray(6)
        var n = 0
        for (seed in seeds) for (dark in listOf(false, true)) {
            val x = accents(Color(seed).toDynamicScheme(dark, a))
            val y = accents(Color(seed).toDynamicScheme(dark, b))
            for (k in 0 until 6) sums[k] += deltaE(x[k], y[k])
            n++
        }
        return sums.count { it / n > 6.0 }
    }

    @Test fun everyOfferedStyleLooksUnlikeTheOthers() {
        val offered = PaletteStyleOption.entries.map { it.toPaletteStyle() }
        for (i in offered.indices) for (j in i + 1 until offered.size) {
            val differ = rolesThatDiffer(offered[i], offered[j])
            assertTrue("${offered[i]} and ${offered[j]} differ in only $differ of 6 accent roles", differ >= 4)
        }
    }

    @Test fun eachDroppedStyleNearlyCopiedTheOneItMovesTo() {
        assertEquals(setOf("Content", "Rainbow"), PaletteStyleOption.dropped.keys)
        for ((name, kept) in PaletteStyleOption.dropped) {
            val differ = rolesThatDiffer(PaletteStyle.valueOf(name), kept.toPaletteStyle())
            assertTrue("$name and ${kept.name} differ in $differ of 6 accent roles", differ <= 2)
        }
    }

    @Test fun everyOfferedStyleIsOneTheThemeCanDraw() {
        // toPaletteStyle maps by name; a name materialkolor lacks would crash the theme.
        PaletteStyleOption.entries.forEach { it.toPaletteStyle() }
    }
}
