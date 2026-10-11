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
import com.opentune.data.settings.DesignMotion
import com.opentune.data.settings.PageTransition
import com.opentune.data.settings.PlayerMotion

/**
 * How pages come and go for each [PageTransition]: the page opened, the one
 * it covers, and the same two on the way back. [still] (reduced animation)
 * is a short cross-fade whatever the choice.
 */
internal object PageMotion {

    private fun designEnter(profile: DesignMotion, still: Boolean, backwards: Boolean = false): EnterTransition {
        val ms = if (still) 0 else if (profile == DesignMotion.CALM) 160 else 300
        val fade = fadeIn(tween(ms))
        return if (still || profile == DesignMotion.CALM) fade else fade + slideInHorizontally(tween(ms, easing = FastOutSlowInEasing)) { if (backwards) -it / 16 else it / 12 }
    }
    private fun designExit(profile: DesignMotion, still: Boolean): ExitTransition = fadeOut(tween(if (still) 0 else if (profile == DesignMotion.CALM) 120 else 180))
    fun enter(t: PageTransition, still: Boolean, profile: DesignMotion = DesignMotion.INHERIT): EnterTransition =
        if (profile == DesignMotion.INHERIT) legacyEnter(t, still) else designEnter(profile, still)
    fun exit(t: PageTransition, still: Boolean, profile: DesignMotion = DesignMotion.INHERIT): ExitTransition =
        if (profile == DesignMotion.INHERIT) legacyExit(t, still) else designExit(profile, still)
    fun popEnter(t: PageTransition, still: Boolean, profile: DesignMotion = DesignMotion.INHERIT): EnterTransition =
        if (profile == DesignMotion.INHERIT) legacyPopEnter(t, still) else designEnter(profile, still, backwards = true)
    fun popExit(t: PageTransition, still: Boolean, profile: DesignMotion = DesignMotion.INHERIT): ExitTransition =
        if (profile == DesignMotion.INHERIT) legacyPopExit(t, still) else designExit(profile, still)
    fun playerIn(m: PlayerMotion, still: Boolean, profile: DesignMotion = DesignMotion.INHERIT): EnterTransition =
        if (profile == DesignMotion.INHERIT) legacyPlayerIn(m, still) else if (still || profile == DesignMotion.CALM) designEnter(profile, still) else slideInVertically(spring(dampingRatio = if (profile == DesignMotion.EXPRESSIVE) .78f else 1f, stiffness = 480f)) { it } + fadeIn(tween(220))
    fun playerOut(m: PlayerMotion, still: Boolean, profile: DesignMotion = DesignMotion.INHERIT): ExitTransition =
        if (profile == DesignMotion.INHERIT) legacyPlayerOut(m, still) else if (still || profile == DesignMotion.CALM) designExit(profile, still) else slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(180))

    private fun legacyEnter(t: PageTransition, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        t == PageTransition.SLIDE -> fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 }
        t == PageTransition.FADE -> fadeIn(tween(240, delayMillis = 60))
        t == PageTransition.ZOOM -> fadeIn(tween(240)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.92f)
        else -> fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 8 }
    }

    private fun legacyExit(t: PageTransition, still: Boolean): ExitTransition = when {
        still -> fadeOut(tween(120))
        t == PageTransition.ZOOM -> fadeOut(tween(200)) + scaleOut(tween(320, easing = FastOutSlowInEasing), targetScale = 1.04f)
        else -> fadeOut(tween(160))
    }

    private fun legacyPopEnter(t: PageTransition, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        t == PageTransition.ZOOM -> fadeIn(tween(240)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 1.04f)
        t == PageTransition.FADE -> fadeIn(tween(240, delayMillis = 60))
        else -> fadeIn(tween(220))
    }

    private fun legacyPopExit(t: PageTransition, still: Boolean): ExitTransition = when {
        still -> fadeOut(tween(120))
        t == PageTransition.SLIDE -> fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { it / 10 }
        t == PageTransition.FADE -> fadeOut(tween(160))
        t == PageTransition.ZOOM -> fadeOut(tween(200)) + scaleOut(tween(280, easing = FastOutSlowInEasing), targetScale = 0.92f)
        else -> fadeOut(tween(200)) + slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it / 8 }
    }

    /** The full player rising over the page. */
    private fun legacyPlayerIn(m: PlayerMotion, still: Boolean): EnterTransition = when {
        still -> fadeIn(tween(160))
        else -> slideInVertically(playerRise(m)) { it } + fadeIn()
    }

    /** The full player dropping away. */
    private fun legacyPlayerOut(m: PlayerMotion, still: Boolean): ExitTransition {
        if (still) return fadeOut(tween(140))
        if (m == PlayerMotion.ECHO) return slideOutVertically(playerRise(m)) { it } + fadeOut(tween(220))
        val ms = when (m) {
            PlayerMotion.SMOOTH -> 260
            PlayerMotion.SNAPPY -> 170
            else -> 220
        }
        return slideOutVertically(tween(ms, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(ms))
    }

    private fun playerRise(m: PlayerMotion) = when (m) {
        // Echo Music BottomSheet's soft spring; see THIRD_PARTY_NOTICES.md.
        PlayerMotion.ECHO -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
        PlayerMotion.SPRING -> spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
        // Apple Music's sheet: no bounce, short and responsive.
        PlayerMotion.SMOOTH -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 550f, visibilityThreshold = IntOffset.VisibilityThreshold)
        PlayerMotion.BOUNCY -> spring(dampingRatio = 0.62f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)
        PlayerMotion.SNAPPY -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1100f, visibilityThreshold = IntOffset.VisibilityThreshold)
    }
}
