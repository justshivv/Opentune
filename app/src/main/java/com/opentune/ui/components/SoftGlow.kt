package com.opentune.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow

/**
 * Stops for a radial glow that fades out with no edge: from [inner] at the
 * centre to [outer] by the middle, its alpha easing from [peak] down to
 * nothing as (1 - t^[shape])², which meets zero flat, so no rim shows where
 * it ends. It fades in its own colour, not toward Color.Transparent, which is
 * transparent black and greys the fade. A larger [shape] keeps it bright
 * further out.
 */
fun glowStops(inner: Color, peak: Float, outer: Color = inner, shape: Float = 2f): Array<Pair<Float, Color>> =
    Array(GLOW_STOPS + 1) { i ->
        val t = i / GLOW_STOPS.toFloat()
        val fall = (1f - t.pow(shape)).let { it * it }
        t to lerp(inner, outer, (t * 2f).coerceAtMost(1f)).copy(alpha = (peak * fall).coerceIn(0f, 1f))
    }

private const val GLOW_STOPS = 10
