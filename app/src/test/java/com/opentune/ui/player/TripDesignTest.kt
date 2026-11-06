package com.opentune.ui.player

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trip mode's looks are generated: always within what the shader draws well, and never the same twice. */
class TripDesignTest {
    private val designs = List(600) { TripDesigns.generate(Random(it.toLong())) }

    @Test fun everyDesignIsFiniteAndTheRightSize() {
        designs.forEach { d ->
            assertEquals(TripDesign.SIZE, d.values.size)
            assertTrue(d.values.all { it.isFinite() })
        }
    }

    @Test fun kaleidoscopesKeepToWhatDrawsWell() {
        designs.filter { it.kaleidoscope }.forEach { d ->
            val v = d.values
            assertEquals(0f, v[0], 0f)
            assertTrue("petals ${v[1]}", v[1] in 4f..10f && v[1] == v[1].toInt().toFloat())
            assertTrue("reach ${v[2]}", v[2] in 0.09f..0.30f)
            assertTrue("rings ${v[3]}", v[3] in 2.2f..4.2f)
            assertTrue("span ${v[5]}", v[5] in 0.25f..0.9f)
            assertTrue("curve ${v[6]}", v[6] in 1.0f..1.7f)
            // A whole number, or the ripple wouldn't fit the petal and the picture would tear.
            assertTrue("ripple ${v[9]}", v[9] == 2f || v[9] == 3f)
        }
    }

    @Test fun fluidsHaveSeparateLobes() {
        designs.filterNot { it.kaleidoscope }.forEach { d ->
            val v = d.values
            assertEquals(1f, v[0], 0f)
            val n = v[1].toInt()
            assertTrue(n in 2..3)
            val lobes = (0 until n).map { i -> v.copyOfRange(16 + i * 4, 20 + i * 4) }
            lobes.forEach { assertTrue("size ${it[2]}", it[2] in 0.035f..0.075f) }
            // Not on top of each other, unless a second had to be mirrored from the first.
            for (i in lobes.indices) for (j in i + 1 until lobes.size) {
                val dx = lobes[i][0] - lobes[j][0]; val dy = lobes[i][1] - lobes[j][1]
                assertTrue("lobes ${dx * dx + dy * dy} apart", dx * dx + dy * dy > 0.04f)
            }
            // The arms are a whole number so the spiral closes on itself.
            assertTrue(v[9] in 2f..5f && v[9] == v[9].toInt().toFloat())
            assertTrue(v[15] != 0f)
        }
    }

    @Test fun bothKindsComeUpAboutEquallyOften() {
        val share = designs.count { it.kaleidoscope } / designs.size.toFloat()
        assertTrue("kaleidoscopes: $share", share in 0.40f..0.60f)
    }

    @Test fun noTwoDesignsAreAlike() {
        val distinct = designs.map { it.values.toList() }.toSet().size
        assertEquals(designs.size, distinct)
    }

    @Test fun theSameSeedMakesTheSameDesign() {
        val a = TripDesigns.generate(Random(42)); val b = TripDesigns.generate(Random(42))
        assertEquals(a.kaleidoscope, b.kaleidoscope)
        assertTrue(a.values.contentEquals(b.values))
    }

    @Test fun aboutHalfAreBuiltRoundTheCoversColour() {
        val anchored = List(600) { TripDesigns.generate(Random(1_000L + it), anchorHue = 0.123f) }.count { it.hue == 0.123f }
        assertTrue("anchored: $anchored", anchored in 220..380)
        // With no cover, none are.
        assertEquals(0, designs.count { it.hue == 0.123f })
    }

    @Test fun everyColourOnTheWheelTurnsUp() {
        val buckets = designs.map { (it.hue * 8).toInt().coerceIn(0, 7) }.toSet()
        assertEquals(8, buckets.size)
    }
}
