package com.opentune.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A soft light in [color] along the left and right edges of the screen and
 * a wider wash along the bottom, as if the cover were lighting the room.
 * It breathes slowly while [playing] and dims when paused; with [still] it
 * holds steady. Draws only, so it never takes a touch.
 */
@Composable
fun EdgeGlow(color: Color?, playing: Boolean, still: Boolean, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(color ?: Color.Transparent, tween(900), label = "glowTint")
    val strength by animateFloatAsState(if (color == null) 0f else if (playing) 1f else 0.45f, tween(700), label = "glowStrength")
    val breath = if (playing && !still) {
        val v by rememberInfiniteTransition(label = "glow").animateFloat(
            0.7f, 1f, infiniteRepeatable(tween(3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glowBreath",
        )
        v
    } else {
        1f
    }
    Canvas(modifier.fillMaxSize()) {
        val a = strength * breath
        if (a <= 0.01f) return@Canvas
        val side = 34.dp.toPx()
        val edge = tint.copy(alpha = 0.34f * a)
        drawRect(Brush.horizontalGradient(listOf(edge, Color.Transparent), startX = 0f, endX = side), size = Size(side, size.height))
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, edge), startX = size.width - side, endX = size.width),
            topLeft = Offset(size.width - side, 0f),
            size = Size(side, size.height),
        )
        val floor = 140.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, tint.copy(alpha = 0.22f * a)), startY = size.height - floor, endY = size.height),
            topLeft = Offset(0f, size.height - floor),
            size = Size(size.width, floor),
        )
    }
}
