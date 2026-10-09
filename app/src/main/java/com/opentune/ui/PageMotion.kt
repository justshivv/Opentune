package com.opentune.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.Spring
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
import com.opentune.data.settings.PlayerMotion

/**
 * How pages come and go for each [PageTransition]: the page opened, the one
 * it covers, and the same two on the way back. [still] (reduced animation)
 * is a short cross-fade whatever the choice.
 */
internal object PageMotion {
    fun enter(t: PageTransition, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        t == PageTransition.SLIDE -> fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 }
        t == PageTransition.FADE -> fadeIn(tween(240, delayMillis = 60))
        t == PageTransition.ZOOM -> fadeIn(tween(240)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.92f)
        else -> fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 8 }
    }

    fun exit(t: PageTransition, still: Boolean): ExitTransition = when {
        still -> fadeOut(tween(120))
        t == PageTransition.ZOOM -> fadeOut(tween(200)) + scaleOut(tween(320, easing = FastOutSlowInEasing), targetScale = 1.04f)
        else -> fadeOut(tween(160))
    }

    fun popEnter(t: PageTransition, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        t == PageTransition.ZOOM -> fadeIn(tween(240)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 1.04f)
        t == PageTransition.FADE -> fadeIn(tween(240, delayMillis = 60))
        else -> fadeIn(tween(220))
    }

    fun popExit(t: PageTransition, still: Boolean): ExitTransition = when {
        still -> fadeOut(tween(120))
        t == PageTransition.SLIDE -> fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { it / 10 }
        t == PageTransition.FADE -> fadeOut(tween(160))
        t == PageTransition.ZOOM -> fadeOut(tween(200)) + scaleOut(tween(280, easing = FastOutSlowInEasing), targetScale = 0.92f)
        else -> fadeOut(tween(200)) + slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it / 8 }
    }

    /** The full player rising over the page. */
    fun playerIn(m: PlayerMotion, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        else -> slideInVertically(playerRise(m)) { it } + fadeIn()
    }

    /** The full player dropping away. */
    fun playerOut(m: PlayerMotion, still: Boolean): ExitTransition {
        if (still) return fadeOut(tween(140))
        val ms = when (m) {
            PlayerMotion.SMOOTH -> 260
            PlayerMotion.SNAPPY -> 170
            else -> 220
        }
        return slideOutVertically(tween(ms, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(ms))
    }

    private fun playerRise(m: PlayerMotion) = when (m) {
        PlayerMotion.SPRING -> spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
        // Apple Music's sheet: no bounce, short and responsive.
        PlayerMotion.SMOOTH -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 550f, visibilityThreshold = IntOffset.VisibilityThreshold)
        PlayerMotion.BOUNCY -> spring(dampingRatio = 0.62f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)
        PlayerMotion.SNAPPY -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1100f, visibilityThreshold = IntOffset.VisibilityThreshold)
    }
}
