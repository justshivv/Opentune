package com.opentune.ui.player

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** A draw-phase clock that freezes in place while paused, hidden or animations are disabled. */
@Composable
internal fun rememberEchoPhase(running: Boolean, periodMillis: Int): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running, periodMillis, lifecycle) {
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (currentCoroutineContext().isActive && ValueAnimator.areAnimatorsEnabled()) {
                val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                if (durationScale <= 0f) break
                withFrameNanos { now ->
                    if (previous != 0L) {
                        val elapsed = ((now - previous) / 1_000_000f).coerceIn(0f, 50f)
                        phase.floatValue = (phase.floatValue + elapsed / (periodMillis * durationScale)) % 1f
                    }
                    previous = now
                }
            }
        }
    }
    return phase
}

/**
 * Adapted from Echo Music's GLOW_ANIMATED in MiniPlayer.kt (GPL-3.0).
 * Source and revision: THIRD_PARTY_NOTICES.md. Uses OpenTune's palette and
 * light/dark surface to retain readable controls, without image blur passes.
 */
@Composable
internal fun EchoGlowBackground(running: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.inversePrimary)
    val phase = rememberEchoPhase(running, 20_000)
    val strength = if (scheme.surface.luminance() > 0.5f) 0.25f else 0.55f
    Canvas(modifier) {
        val p = phase.value
        fun color(index: Int): Color {
            val at = index + p * colors.size
            val start = floor(at).toInt()
            return lerp(colors[start % colors.size], colors[(start + 1) % colors.size], at - start)
        }
        fun oscillate(min: Float, max: Float, offset: Float): Float =
            min + (max - min) * ((sin(2f * PI.toFloat() * (p + offset)) + 1f) / 2f)
        drawRect(scheme.surface)
        drawRect(Brush.radialGradient(
            listOf(color(0).copy(alpha = strength), Color.Transparent),
            center = Offset(size.width * oscillate(0f, 1f, 0f), size.height * oscillate(0f, 0.5f, 0.1f)),
            radius = size.width.coerceAtLeast(1f) * 1.2f,
        ))
        drawRect(Brush.radialGradient(
            listOf(color(1).copy(alpha = strength * 0.875f), Color.Transparent),
            center = Offset(size.width * oscillate(1f, 0f, 0.2f), size.height * oscillate(0.5f, 1f, 0.3f)),
            radius = size.width.coerceAtLeast(1f),
        ))
    }
}
