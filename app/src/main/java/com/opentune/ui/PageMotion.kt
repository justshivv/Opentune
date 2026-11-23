package com.opentune.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
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
 * is a short cross-fade whatever the choice. Grow from cover pushes like
 * Push, except for an album or playlist opened from its cover (the grow* ones).
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
        t == PageTransition.SLIDE || t == PageTransition.GROW -> slideInHorizontally(glide) { it } + fadeIn(tween(120))
        t == PageTransition.FADE -> fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(260, delayMillis = 90, easing = ease), initialScale = 0.94f)
        t == PageTransition.ZOOM -> fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 1f, stiffness = 420f), initialScale = 0.86f)
        else -> slideInVertically(settle) { it / 3 } + fadeIn(tween(200))
    }

    fun exit(t: PageTransition, still: Boolean): ExitTransition = when {
        still -> fadeOut(tween(120))
        t == PageTransition.SLIDE || t == PageTransition.GROW -> slideOutHorizontally(glide) { -it / 4 } + fadeOut(tween(260))
        t == PageTransition.FADE -> fadeOut(tween(90))
        t == PageTransition.ZOOM -> fadeOut(tween(200)) + scaleOut(tween(320, easing = ease), targetScale = 1.08f)
        else -> fadeOut(tween(260)) + scaleOut(tween(320, easing = ease), targetScale = 0.94f)
    }

    fun popEnter(t: PageTransition, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        t == PageTransition.SLIDE || t == PageTransition.GROW -> slideInHorizontally(glide) { -it / 4 } + fadeIn(tween(220))
        t == PageTransition.FADE -> fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(260, delayMillis = 90, easing = ease), initialScale = 0.94f)
        t == PageTransition.ZOOM -> fadeIn(tween(240)) + scaleIn(tween(320, easing = ease), initialScale = 1.08f)
        else -> fadeIn(tween(240)) + scaleIn(tween(320, easing = ease), initialScale = 0.94f)
    }

    fun popExit(t: PageTransition, still: Boolean): ExitTransition = when {
        still -> fadeOut(tween(120))
        t == PageTransition.SLIDE || t == PageTransition.GROW -> slideOutHorizontally(glide) { it } + fadeOut(tween(260, delayMillis = 60))
        t == PageTransition.FADE -> fadeOut(tween(90))
        t == PageTransition.ZOOM -> fadeOut(tween(200)) + scaleOut(tween(280, easing = ease), targetScale = 0.86f)
        else -> fadeOut(tween(220)) + slideOutVertically(tween(280, easing = ease)) { it / 3 }
    }

    // Grow from cover: the page itself grows out of the cover (CoverMorph), so
    // here it only has to stay put while it does. The page behind sinks back a
    // little and dims, on the cover's clocks, and comes forward again on the way back.
    val growIn: EnterTransition = EnterTransition.None
    // Held, unfaded, for the length of the shrink: the page draws itself as it shrinks.
    val growOut: ExitTransition = fadeOut(tween(CoverClocks.CLOSE_MS), targetAlpha = 1f)
    val recede: ExitTransition = scaleOut(CoverClocks.open(0.001f), targetScale = 0.93f) + fadeOut(CoverClocks.open(0.001f), targetAlpha = 0.55f)
    val comeForward: EnterTransition = scaleIn(CoverClocks.close(), initialScale = 0.93f) + fadeIn(CoverClocks.close(), initialAlpha = 0.55f)

    /**
     * The full player coming in and going, with motion reduced. Otherwise it grows out of the
     * mini player as one card (PlayerSheet), which has its own spring; the phone's choice of
     * how the player opens (PlayerMotion, still the desktop's) was only ever read here, where
     * reduced motion made it a fade whatever it said, so it's no longer offered.
     */
    val playerIn: EnterTransition = fadeIn(tween(160))
    val playerOut: ExitTransition = fadeOut(tween(140))
}

/**
 * The clocks a page grows out of its cover on and shrinks back into it on
 * (see CoverMorph); the page behind keeps time with them.
 */
internal object CoverClocks {
    /** Opening: a soft spring of a little under half a second, which a quick back can turn round. */
    fun <T> open(threshold: T? = null): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 240f, visibilityThreshold = threshold)

    /**
     * Closing: a fixed ease rather than a spring. The page behind is composed
     * afresh on the way back, so its first frame can be slow; a spring covers
     * much of its distance early and would jump there, where this starts
     * gently. A back swipe scrubs it by time, which this follows evenly.
     */
    fun <T> close(): FiniteAnimationSpec<T> = tween(CLOSE_MS, easing = closeEasing)

    const val CLOSE_MS = 460
    private val closeEasing = CubicBezierEasing(0.3f, 0f, 0.12f, 1f)
}
