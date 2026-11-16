package com.opentune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** The splash's black, which the system splash screen and the icon use too. */
val OPENING_FIELD = Color.Black

/**
 * The opening: the mark takes over from the system splash at the same
 * size, settles with a glint of light across it, then the screen dives into
 * it. The white mark turns into a window onto the app, outlined in white,
 * and rushes toward you until the app fills the screen, as if flying
 * through the logo. The app behind the window never moves: it is simply
 * revealed. [onDive] reports the dive, 0 to 1, so the app behind can
 * come forward as it's revealed. Its motion is driven by the drawn
 * frames themselves, each frame counting for at most a frame and a half
 * of time: when the app is busy and a frame comes late, the opening slows
 * down instead of freezing and leaping — it breathes through the busy
 * moments at the app's start, and runs at full speed the rest of the
 * time. [onDone] removes this.
 */
@Composable
fun OpeningReveal(onDive: (Float) -> Unit, onDone: () -> Unit) {
    val mark = remember { markPath() }
    val settle = remember { Animatable(0f) }
    val dive = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val report by rememberUpdatedState(onDive)
    LaunchedEffect(Unit) {
        runPhase(settle, SETTLE_MS, CubicBezierEasing(0.2f, 0f, 0f, 1f))
        delay(HOLD_MS)
        runPhase(dive, DIVE_MS, CubicBezierEasing(0.6f, 0f, 0.85f, 0.35f)) { report(it) }
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
        // The mark grows a touch as it settles and never comes back: the
        // dive continues straight from that size, so the whole opening is
        // one motion with no false start in it.
        val breathe = 1f + 0.05f * st
        val s = base * breathe * exp(ln(ZOOM) * d * d)
        val window = smooth(0.04f, 0.28f, d)
        val fade = 1f - smooth(0.8f, 1f, d)

        drawRect(OPENING_FIELD, alpha = fade)
        // A white rim that stays the same width on screen however big the mark gets.
        val rim = Stroke(width = RIM.toPx() / s, join = StrokeJoin.Round)
        translate(center.x - MARK_VIEWPORT / 2f * s, center.y - MARK_VIEWPORT / 2f * s) {
            scale(s, s, pivot = Offset.Zero) {
                drawPath(mark, Color.White, alpha = 1f - window)
                // A band of light that runs across the mark once as it settles.
                if (st in 0.05f..0.98f && d == 0f) {
                    val x = -30f + 170f * st
                    drawPath(
                        mark,
                        Brush.linearGradient(
                            listOf(Color(0x00DADADA), Color(0xFFDADADA).copy(alpha = 0.9f), Color(0x00DADADA)),
                            start = Offset(x - 18f, 30f),
                            end = Offset(x + 18f, 78f),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                }
                // Then the mark becomes a window onto the app.
                if (window > 0f) drawPath(mark, Color.Black, alpha = window, blendMode = BlendMode.DstOut)
                // Its white edge outlines the window, so the way in shows even when the
                // app behind is as dark as the field around it.
                if (fade > 0f) drawPath(mark, Color.White, alpha = fade, style = rim)
            }
        }
    }
}

/**
 * Advances [target] over [ms] of counted frame time: each drawn frame
 * adds its real length, capped, so a late frame slows the phase instead
 * of skipping it forward.
 */
private suspend fun runPhase(target: Animatable<Float, AnimationVector1D>, ms: Int, easing: Easing, onProgress: (Float) -> Unit = {}) {
    var progress = 0f
    var last = -1L
    while (progress < 1f) {
        val t = withFrameNanos { it }
        if (last >= 0) progress += (t - last).coerceAtMost(25_000_000L).toFloat() / (ms * 1_000_000f)
        last = t
        target.snapTo(easing.transform(progress.coerceAtMost(1f)))
        onProgress(target.value)
    }
    target.snapTo(1f)
}

private fun smooth(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The width of the white edge around the mark. */
private val RIM = 2.5.dp
private const val SETTLE_MS = 620
private const val HOLD_MS = 60L
private const val DIVE_MS = 900
/** How many times its size the mark grows to by the end of the dive, enough for its band to fill the screen. */
private const val ZOOM = 90f
