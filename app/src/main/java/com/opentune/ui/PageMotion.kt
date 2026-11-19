package com.opentune.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import com.opentune.data.settings.PageTransition

/**
 * How pages come and go for each [PageTransition]: the page opened, the one
 * it covers, and the same two on the way back. [still] (reduced animation)
 * is a short cross-fade whatever the choice.
 */
internal object PageMotion {
    // Each style is its own idea, not a variation on a fade:
    // Push slides whole pages across (the one underneath edging away),
    // Fade through swaps in two beats, Depth moves along the Z axis, and
    // Rise stacks pages like cards.
    private val glide = spring(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = IntOffset.VisibilityThreshold)
    private val settle = spring(dampingRatio = 0.92f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)
    private val ease = FastOutSlowInEasing

    fun enter(t: PageTransition, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        t == PageTransition.SLIDE -> slideInHorizontally(glide) { it } + fadeIn(tween(120))
        t == PageTransition.FADE -> fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(260, delayMillis = 90, easing = ease), initialScale = 0.94f)
        t == PageTransition.ZOOM -> fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 1f, stiffness = 420f), initialScale = 0.86f)
        else -> slideInVertically(settle) { it / 3 } + fadeIn(tween(200))
    }

    fun exit(t: PageTransition, still: Boolean): ExitTransition = when {
        still -> fadeOut(tween(120))
        t == PageTransition.SLIDE -> slideOutHorizontally(glide) { -it / 4 } + fadeOut(tween(260))
        t == PageTransition.FADE -> fadeOut(tween(90))
        t == PageTransition.ZOOM -> fadeOut(tween(200)) + scaleOut(tween(320, easing = ease), targetScale = 1.08f)
        else -> fadeOut(tween(260)) + scaleOut(tween(320, easing = ease), targetScale = 0.94f)
    }

    fun popEnter(t: PageTransition, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        t == PageTransition.SLIDE -> slideInHorizontally(glide) { -it / 4 } + fadeIn(tween(220))
        t == PageTransition.FADE -> fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(260, delayMillis = 90, easing = ease), initialScale = 0.94f)
        t == PageTransition.ZOOM -> fadeIn(tween(240)) + scaleIn(tween(320, easing = ease), initialScale = 1.08f)
        else -> fadeIn(tween(240)) + scaleIn(tween(320, easing = ease), initialScale = 0.94f)
    }

    fun popExit(t: PageTransition, still: Boolean): ExitTransition = when {
        still -> fadeOut(tween(120))
        t == PageTransition.SLIDE -> slideOutHorizontally(glide) { it } + fadeOut(tween(260, delayMillis = 60))
        t == PageTransition.FADE -> fadeOut(tween(90))
        t == PageTransition.ZOOM -> fadeOut(tween(200)) + scaleOut(tween(280, easing = ease), targetScale = 0.86f)
        else -> fadeOut(tween(220)) + slideOutVertically(tween(280, easing = ease)) { it / 3 }
    }

    /**
     * The full player coming in and going, with motion reduced. Otherwise it grows out of the
     * mini player as one card (PlayerSheet), which has its own spring; the phone's choice of
     * how the player opens (PlayerMotion, still the desktop's) was only ever read here, where
     * reduced motion made it a fade whatever it said, so it's no longer offered.
     */
    val playerIn: EnterTransition = fadeIn(tween(160))
    val playerOut: ExitTransition = fadeOut(tween(140))
}
