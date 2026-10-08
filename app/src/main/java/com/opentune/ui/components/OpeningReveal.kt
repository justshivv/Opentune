package com.opentune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.delay

/** Plays once per launch of the app, not each time the screen is rebuilt. */
object Opening {
    var played = false
}

/** The splash's near-black, which the system splash screen uses too. */
val OPENING_FIELD = Color(0xFF050406)

/**
 * The opening: the mark takes over from the system splash at the same
 * size, settles with a soft glow and a glint of light across it, then the
 * screen dives into it. The white mark turns into a window onto the app and
 * rushes toward you until the app fills the screen, as if flying through
 * the logo. [onDive] reports the dive, 0 to 1, so the app behind can come
 * forward as it's revealed; [onDone] removes this.
 */
@Composable
fun OpeningReveal(onDive: (Float) -> Unit, onDone: () -> Unit) {
    val mark = remember { markPath() }
    val settle = remember { Animatable(0f) }
    val dive = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val report by rememberUpdatedState(onDive)
    LaunchedEffect(Unit) {
        settle.animateTo(1f, tween(SETTLE_MS, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        delay(HOLD_MS)
        // Slow to start and then rushing, like falling in.
        dive.animateTo(1f, tween(DIVE_MS, easing = CubicBezierEasing(0.6f, 0f, 0.85f, 0.35f))) { report(value) }
        report(1f)
        done()
    }
    Canvas(
        Modifier
            .fillMaxSize()
            // Its own layer, so the window cut through it shows the app below.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val d = dive.value
        val st = settle.value
        // The system splash draws the 108-unit icon 240dp across; start exactly there.
        val base = 240.dp.toPx() / MARK_VIEWPORT
        val breathe = 1f + 0.05f * kotlin.math.sin(Math.PI.toFloat() * st)
        val s = base * breathe * exp(ln(ZOOM) * d * d)
        val window = smooth(0.04f, 0.28f, d)
        val fade = 1f - smooth(0.8f, 1f, d)

        drawRect(OPENING_FIELD, alpha = fade)
        // A warm glow that comes up behind the mark while it settles and goes as it dives.
        val glow = 0.55f * st * (1f - smooth(0f, 0.4f, d))
        if (glow > 0.01f) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF6B2F4F).copy(alpha = glow), Color.Transparent), center, size.minDimension * 0.6f),
                size.minDimension * 0.6f,
                center,
            )
        }
        translate(center.x - MARK_VIEWPORT / 2f * s, center.y - MARK_VIEWPORT / 2f * s) {
            scale(s, s, pivot = Offset.Zero) {
                drawPath(mark, Color.White, alpha = 1f - window)
                // A band of light that runs across the mark once as it settles.
                if (st in 0.05f..0.98f && d == 0f) {
                    val x = -30f + 170f * st
                    drawPath(
                        mark,
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color(0xFFFFE9F2).copy(alpha = 0.9f), Color.Transparent),
                            start = Offset(x - 18f, 30f),
                            end = Offset(x + 18f, 78f),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                }
                // Then the mark becomes a window onto the app.
                if (window > 0f) drawPath(mark, Color.Black, alpha = window, blendMode = BlendMode.DstOut)
            }
        }
    }
}

private fun smooth(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val SETTLE_MS = 620
private const val HOLD_MS = 120L
private const val DIVE_MS = 900
/** How many times its size the mark grows to by the end of the dive, enough for its band to fill the screen. */
private const val ZOOM = 90f
