package com.opentune.ui.player

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import com.opentune.data.settings.CoverChange
import kotlin.math.hypot
import kotlin.math.pow

/** A cover on screen: which art, and where its song sits in the queue. */
internal data class CoverShown(val url: String?, val index: Int)

/**
 * One cover giving way to the next, in the [change] chosen in Settings,
 * for the player's cover and for the full-screen cover alike. [content]
 * draws a cover; the modifier it's handed carries the motion, so it goes on
 * whatever should move.
 */
@Composable
internal fun CoverSwap(
    shown: CoverShown,
    change: CoverChange,
    modifier: Modifier = Modifier,
    content: @Composable (url: String?, motion: Modifier) -> Unit,
) {
    // Which way the queue moved with the last change, for the changes that have a direction.
    val way = androidx.compose.runtime.remember { Way(shown.index) }
    if (shown.index != way.last) {
        way.forward = shown.index >= way.last
        way.last = shown.index
    }
    AnimatedContent(
        targetState = shown,
        contentKey = { it.url },
        transitionSpec = { coverTransition(change, forward = targetState.index >= initialState.index) },
        label = "cover",
        modifier = modifier,
    ) { s ->
        val forward = way.forward
        // 0 while waiting to come in, 1 on screen, 2 once gone: the drawn
        // changes work from this, each on its own clock.
        val phase = if (change.drawn) {
            transition.animateFloat(
                transitionSpec = {
                    val coming = targetState == EnterExitState.Visible
                    when (change) {
                        CoverChange.FLIP -> tween(FLIP_MS / 2, delayMillis = if (coming) FLIP_MS / 2 else 0, easing = FastOutSlowInEasing)
                        // The new cover waits for the old one to be on its way, then lands with a small bounce.
                        CoverChange.TOSS -> if (coming) tween(520, delayMillis = 240, easing = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)) else tween(380, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.5f))
                        CoverChange.ZOOM -> tween(560, easing = if (coming) CubicBezierEasing(0.2f, 0f, 0f, 1f) else CubicBezierEasing(0.55f, 0f, 1f, 0.45f))
                        CoverChange.CUBE -> tween(620, easing = FastOutSlowInEasing)
                        CoverChange.REVEAL -> tween(if (coming) 680 else 680, easing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f))
                        else -> tween(560, easing = FastOutSlowInEasing)
                    }
                },
                label = "coverPhase",
            ) { when (it) { EnterExitState.PreEnter -> 0f; EnterExitState.Visible -> 1f; EnterExitState.PostExit -> 2f } }
        } else {
            null
        }
        // Coming in or staying, as opposed to on the way out; a spring can carry the phase past 1.
        val coming = transition.targetState == EnterExitState.Visible
        val motion = if (phase == null) Modifier else Modifier.graphicsLayer { drawn(change, phase.value, coming, forward) }
        content(s.url, motion)
    }
}

private class Way(var last: Int, var forward: Boolean = true)

/** Changes drawn frame by frame from the phase, rather than by Compose's enter and exit. */
private val CoverChange.drawn get() = this != CoverChange.FADE && this != CoverChange.CAROUSEL && this != CoverChange.DECK

private fun GraphicsLayerScope.drawn(change: CoverChange, p: Float, entering: Boolean, forward: Boolean) {
    val e = p.coerceIn(0f, 1f)           // how far in, while coming
    val x = (p - 1f).coerceIn(0f, 1f)    // how far out, while going
    val dir = if (forward) 1f else -1f
    val w = size.width
    when (change) {
        CoverChange.FLIP -> {
            rotationY = if (entering) -90f * (1f - e) else 90f * x
            cameraDistance = 14f * density
            alpha = if ((entering && e > 0f) || (!entering && x < 1f)) 1f else 0f
        }
        CoverChange.ZOOM -> {
            // The old cover rushes past you; the new one grows in from behind it.
            val s = if (entering) 0.72f + 0.28f * e else 1f + 0.5f * x
            scaleX = s; scaleY = s
            alpha = if (entering) e else 1f - x
        }
        CoverChange.DISSOLVE -> {
            // One cover melts into the other: blur out, blur in.
            val r = (if (entering) 1f - e else x) * 28f * density
            if (r > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) renderEffect = BlurEffect(r, r, TileMode.Decal)
            alpha = if (entering) e else 1f - x
            val s = if (entering) 1.04f - 0.04f * e else 1f + 0.03f * x
            scaleX = s; scaleY = s
        }
        CoverChange.CUBE -> {
            // Two faces of a cube: the turn hinges on the edge they share.
            cameraDistance = 10f * density
            if (entering) {
                transformOrigin = TransformOrigin(if (dir > 0) 0f else 1f, 0.5f)
                rotationY = dir * 90f * (1f - e)
                translationX = dir * w * (1f - e)
            } else {
                transformOrigin = TransformOrigin(if (dir > 0) 1f else 0f, 0.5f)
                rotationY = -dir * 90f * x
                translationX = -dir * w * x
            }
            alpha = if (entering) (e * 3f).coerceAtMost(1f) else (1f - x * 0.6f)
        }
        CoverChange.REVEAL -> {
            if (entering) {
                // The new cover opens out of the middle as a growing circle.
                shape = CircleReveal(e)
                clip = true
            } else {
                val s = 1f - 0.08f * x
                scaleX = s; scaleY = s
                alpha = 1f - 0.5f * x
            }
        }
        CoverChange.TOSS -> {
            if (entering) {
                // Dropped onto the table from just above, settling with a little bounce.
                translationY = -w * 0.28f * (1f - p)
                val s = 1.08f - 0.08f * p
                scaleX = s; scaleY = s
                rotationZ = dir * 4f * (1f - p)
                alpha = (p * 3f).coerceIn(0f, 1f)
            } else {
                // Flung off to the side, turning as it goes.
                translationX = -dir * w * 1.15f * x.pow(1.3f)
                translationY = w * 0.18f * x
                rotationZ = -dir * 22f * x
                alpha = 1f - x * 0.4f
            }
        }
        else -> Unit
    }
}

/** A circle from the middle, [fraction] of the way to the corners. */
private class CircleReveal(private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = hypot(size.width, size.height) / 2f * fraction.coerceIn(0f, 1.2f)
        return Outline.Generic(Path().apply { addOval(Rect(Offset(size.width / 2f, size.height / 2f), r)) })
    }
}

internal const val FLIP_MS = 520

/** How the two covers are kept and layered for each [CoverChange]. */
private fun coverTransition(change: CoverChange, forward: Boolean): ContentTransform = when (change) {
    CoverChange.FADE ->
        (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.94f)) togetherWith fadeOut(tween(300))
    CoverChange.CAROUSEL -> {
        val dir = if (forward) 1 else -1
        val slide = spring(dampingRatio = 0.86f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)
        (slideInHorizontally(slide) { dir * it } + scaleIn(tween(420), initialScale = 0.9f)) togetherWith
            (slideOutHorizontally(slide) { -dir * it } + scaleOut(tween(420), targetScale = 0.9f) + fadeOut(tween(420)))
    }
    CoverChange.DECK ->
        (slideInVertically(spring(dampingRatio = 0.7f, stiffness = 320f, visibilityThreshold = IntOffset.VisibilityThreshold)) { -it / 3 } +
            scaleIn(spring(dampingRatio = 0.7f, stiffness = 320f), initialScale = 1.08f) + fadeIn(tween(220))) togetherWith
            (scaleOut(tween(420, easing = FastOutSlowInEasing), targetScale = 0.82f) + fadeOut(tween(420)))
    // The drawn ones move themselves; both covers only need keeping until they finish.
    CoverChange.ZOOM -> EnterTransition.None togetherWith fadeOut(tween(1, delayMillis = 560))
    CoverChange.FLIP -> fadeIn(tween(1, delayMillis = FLIP_MS / 2)) togetherWith fadeOut(tween(1, delayMillis = FLIP_MS / 2))
    CoverChange.TOSS -> EnterTransition.None togetherWith fadeOut(tween(1, delayMillis = 720))
    else -> EnterTransition.None togetherWith fadeOut(tween(1, delayMillis = 700))
}
