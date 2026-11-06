package com.opentune.ui.player

import kotlin.math.min
import kotlin.random.Random

/**
 * One look for Trip mode, made up on the spot rather than drawn by hand: a kaleidoscope (a
 * flower of any 4 to 10 petals, ringed in bands of colour) or a fluid (two or three glowing,
 * spiralling lobes of light), each with its own proportions, colour scheme and motion. A
 * new one is made every time Trip mode opens, every half minute or so while it plays, and
 * when the music surges, so it doesn't show the same picture twice.
 *
 * The numbers go to the shader as seven vec4s ([values]); see TRIP_SHADER for what each means.
 */
internal class TripDesign(val kaleidoscope: Boolean, val values: FloatArray) {
    /** The colour wheel position (0 to 1) the design is built round. */
    val hue: Float get() = values[HUE]

    companion object {
        const val SIZE = 28
        /** How many vec4s: four of settings, three of lobes. */
        const val VECS = SIZE / 4
        const val HUE = 4
    }
}

internal object TripDesigns {
    /**
     * A new design from [random]. With an [anchorHue] (the cover's main colour, 0 to 1), about
     * half the designs are built round it, so the picture often belongs to the song; the rest
     * pick any hue. The same [random] state always gives the same design.
     */
    fun generate(random: Random, anchorHue: Float? = null): TripDesign {
        val kaleidoscope = random.nextFloat() < 0.5f
        val hue = if (anchorHue != null && random.nextFloat() < 0.5f) anchorHue else random.nextFloat()
        return if (kaleidoscope) kaleidoscope(random, hue) else fluid(random, hue)
    }

    private fun Random.between(lo: Float, hi: Float) = lo + nextFloat() * (hi - lo)
    private fun Random.sign() = if (nextBoolean()) 1f else -1f

    private fun kaleidoscope(random: Random, hue: Float): TripDesign = with(random) {
        val folds = listOf(4, 5, 6, 6, 7, 8, 9, 10).random(this)
        val v = FloatArray(TripDesign.SIZE)
        // p0: kind, petals, how far the petals reach, how many rings
        v[0] = 0f; v[1] = folds.toFloat()
        // Many petals reach less, or the star's points run together.
        v[2] = between(0.14f, 0.30f) * min(1f, 6.5f / folds)
        v[3] = between(2.2f, 4.2f)
        // p1: hue, how far round the wheel the rings go (a little: two tones; a lot: a rainbow), how fast the colour
        // changes across a ring, how thin the dark seam between rings is
        v[4] = hue
        v[5] = if (nextInt(3) == 0) between(0.25f, 0.45f) else between(0.6f, 0.9f)
        v[6] = between(1.0f, 1.7f); v[7] = between(0.04f, 0.10f)
        // p2: spin, a second, finer ripple on the petals (its multiple and its weight), the glow at the middle
        v[8] = between(0.03f, 0.09f) * sign(); v[9] = listOf(2, 3).random(this).toFloat(); v[10] = between(0f, 0.5f); v[11] = between(0.10f, 0.35f)
        // p3: how much the picture twists with distance, how tightly, how fast the rings flow
        v[12] = between(0f, 0.45f); v[13] = between(2f, 6f); v[14] = between(0.28f, 0.50f)
        TripDesign(true, v)
    }

    private fun fluid(random: Random, hue: Float): TripDesign = with(random) {
        val v = FloatArray(TripDesign.SIZE)
        val want = listOf(2, 3).random(this)
        // Lobes kept well apart, or they run together into one white blot.
        val lobes = ArrayList<FloatArray>()
        var tries = 0
        while (lobes.size < want && tries++ < 200) {
            val x = between(-0.30f, 0.30f); val y = between(-0.46f, 0.46f)
            if (lobes.all { (x - it[0]) * (x - it[0]) + (y - it[1]) * (y - it[1]) > MIN_APART_SQ }) {
                lobes += floatArrayOf(x, y, between(0.035f, 0.075f), 0f)
            }
        }
        if (lobes.size < 2) { val a = lobes.first(); lobes += floatArrayOf(-a[0], -a[1], a[2], 0f) }
        // Each lobe's colour: all round the wheel, or two opposites, or neighbours.
        val scheme = nextInt(3)
        lobes.forEachIndexed { i, l ->
            l[3] = when (scheme) {
                0 -> hue + i.toFloat() / lobes.size + between(-0.05f, 0.05f)
                1 -> hue + (if (i % 2 == 1) 0.5f else 0f) + between(-0.04f, 0.04f)
                else -> hue + i * 0.10f
            }
        }
        // p0: kind, how many lobes
        v[0] = 1f; v[1] = lobes.size.toFloat()
        // p1: hue, how much colour varies inside a lobe
        v[4] = hue; v[5] = between(0.18f, 0.34f)
        // p2: arms of the spiral
        v[9] = listOf(2, 3, 4, 5).random(this).toFloat()
        // p3: the fine filaments (how far, how fine), how fast it flows, how hard the lobes twist and which way
        v[12] = between(0.05f, 0.10f); v[13] = between(4f, 6f); v[14] = between(0.8f, 1.2f); v[15] = between(3f, 8f) * sign()
        lobes.forEachIndexed { i, l -> l.copyInto(v, 16 + i * 4) }
        TripDesign(false, v)
    }

    /** Lobes closer than this (squared) are not allowed: a new one is tried elsewhere. */
    private const val MIN_APART_SQ = 0.12f
}
