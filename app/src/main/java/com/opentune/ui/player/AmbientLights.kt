package com.opentune.ui.player

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.opentune.playback.AudioLevels
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Soft light in the lower part of the player: a few wide glows in the
 * song's colours, drifting slowly on paths of their own so they meet, mix
 * and part again, fading upward into the gradient above. The music lifts
 * them a little; paused, they keep drifting, dimmer. [animate] false holds
 * them still.
 */
@Composable
fun AmbientLights(
    colors: List<Color>,
    playing: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    /** Spread over the whole area, softer and with no wash, for behind the lyrics. */
    field: Boolean = false,
) {
    // Each colour eases into the next song's rather than jumping.
    val shown = colors.take(GLOWS).let { if (it.size < GLOWS) it + List(GLOWS - it.size) { i -> it[i % it.size] } else it }
        .mapIndexed { i, c -> animateColorAsState(c, tween(1_400), label = "glow$i").value }
    DisposableEffect(animate) {
        if (animate) AudioLevels.listening = true
        onDispose { if (animate) AudioLevels.listening = false }
    }
    val time = remember { mutableFloatStateOf(0f) }
    val swell = remember { mutableFloatStateOf(0f) }
    val bright = remember { mutableFloatStateOf(if (playing) 1f else 0.6f) }
    LaunchedEffect(animate, playing) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        var peak = 0.05f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                // Slower while paused, as if the room went quiet.
                time.floatValue += dt * if (playing) 1f else 0.4f
                bright.floatValue += ((if (playing) 1f else 0.6f) - bright.floatValue) * (dt * 1.5f).coerceAtMost(1f)
                val level = if (playing) AudioLevels.at(System.nanoTime())?.let { it.bass * 0.7f + it.level * 0.3f } ?: 0f else 0f
                peak = maxOf(level, peak * (1f - dt * 0.3f)).coerceAtLeast(0.02f)
                val target = (level / peak).coerceIn(0f, 1f)
                // Heavily smoothed: the light breathes with the music, it doesn't flash.
                swell.floatValue += (target - swell.floatValue) * (dt * if (target > swell.floatValue) 3f else 1.2f).coerceAtMost(1f)
            }
        }
    }
    Canvas(
        modifier
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp, BlurredEdgeTreatment.Unbounded) else Modifier)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (field) return@drawWithContent
                // Fades out toward the top so it melts into the gradient.
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color.Black.copy(alpha = 0.35f), 0.6f to Color.Black, 1f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val t = time.floatValue
        val lift = 1f + 0.15f * swell.floatValue
        val light = bright.floatValue
        // A pale wash rising from the bottom edge, the way light pools at the foot of a sky.
        val wash = lerp(shown[1], Color.White, 0.35f)
        if (!field) drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to wash.copy(alpha = 0.10f * light * lift),
                1f to wash.copy(alpha = 0.38f * light * lift),
            ),
        )
        for (i in 0 until GLOWS) {
            val path = PATHS[i]
            // Two slow circles of different speeds make a path that doesn't repeat for a long while.
            val x = w * (path[0] + 0.24f * sin(t * path[2] + path[4]) + 0.1f * cos(t * path[3] * 1.7f + i))
            val rest = if (field) FIELD_Y[i] else path[1]
            val y = h * (rest + (if (field) 0.2f else 0.14f) * cos(t * path[3] + path[4]) + 0.05f * sin(t * path[2] * 1.3f + i * 2))
            val r = w * (0.72f + 0.08f * sin(t * 0.21f + i)) * lift
            val c = lerp(shown[i], Color.White, 0.15f)
            val a = ((if (field) 0.34f else 0.42f) * light).coerceIn(0f, 1f)
            // A falloff close to a bell curve, so no glow shows an edge.
            drawCircle(
                Brush.radialGradient(
                    0f to c.copy(alpha = a),
                    0.2f to c.copy(alpha = a * 0.86f),
                    0.4f to c.copy(alpha = a * 0.55f),
                    0.6f to c.copy(alpha = a * 0.26f),
                    0.8f to c.copy(alpha = a * 0.07f),
                    1f to Color.Transparent,
                    center = Offset(x, y),
                    radius = r,
                ),
                r,
                Offset(x, y),
                blendMode = BlendMode.Screen,
            )
        }
    }
}

private const val GLOWS = 5

/** Where each glow rests across the height when it fills the whole area. */
private val FIELD_Y = floatArrayOf(0.12f, 0.38f, 0.88f, 0.62f, 0.25f)

/** Each glow's resting place (x, y as fractions), its two speeds, and where along its path it starts. */
private val PATHS = arrayOf(
    floatArrayOf(0.15f, 0.85f, 0.11f, 0.083f, 0f),
    floatArrayOf(0.85f, 0.75f, 0.093f, 0.127f, 1.9f),
    floatArrayOf(0.5f, 1.05f, 0.071f, 0.104f, 3.7f),
    floatArrayOf(0.65f, 0.55f, 0.137f, 0.061f, (PI * 1.3).toFloat()),
    floatArrayOf(0.3f, 0.6f, 0.059f, 0.118f, 5.1f),
)
