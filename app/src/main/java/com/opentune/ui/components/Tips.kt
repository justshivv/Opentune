package com.opentune.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.opentune.data.tips.Tip
import com.opentune.data.tips.Tips
import com.opentune.ui.theme.Text
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * One place on screen a tip can point at. Several rows can each offer the
 * same tip; the first one fully on screen takes it, and the bubble follows
 * it as the page scrolls.
 */
@Stable
class TipTarget internal constructor(val tip: Tip) {
    internal var text by mutableStateOf("")
    /** The bubble lies over the bottom of the target rather than beside it, for something as big as the player's cover. */
    internal var inside by mutableStateOf(false)
}

/** Where the tips' targets are, in window pixels, and whether the full player is over the pages. */
object TipAnchors {
    /**
     * Which target each tip points at. Kept apart from where it is ([rects]): the rows read
     * this, and it changes only when the tip moves to another target, so a row being laid out
     * again never makes the rows compose again (which laid them out again, and so on).
     */
    internal val owners = mutableStateMapOf<Tip, TipTarget>()
    /** Where each tip's target is, in window pixels; read only by [TipHost], to place the bubble. */
    internal val rects = mutableStateMapOf<Tip, Rect>()

    /**
     * Every target on screen with where it was last laid out. A target asks for its tip
     * when it's laid out, but a screen that stays still isn't laid out again: refused then
     * (another tip up, the pause after one), it would never ask again. [TipHost] asks
     * again for all of them now and then.
     */
    private val live = mutableMapOf<TipTarget, LayoutCoordinates>()

    /** Set by the app shell: the full player is open over the pages, covering every page tip's target. */
    var pagesCovered by mutableStateOf(false)
    /** Set by the app shell: the player is on screen, open over the pages or docked beside them. */
    var playerShown by mutableStateOf(false)

    internal fun covered(tip: Tip) = if (tip in inPlayer) !playerShown else pagesCovered

    /** The tips whose targets are in the player rather than on the pages under it. */
    internal val inPlayer = setOf(Tip.PLAYER)

    internal fun offer(target: TipTarget, c: LayoutCoordinates) {
        live[target] = c
        val tip = target.tip
        val held = owners[tip]
        val visible = !covered(tip) && c.isAttached && run {
            val full = Rect(c.positionInWindow(), c.size.toSize())
            val shown = c.boundsInWindow()
            full.width > 0f && shown.width >= full.width * 0.9f && shown.height >= full.height * 0.9f
        }
        if (visible) {
            if (held != null && held !== target) return
            if (held !== target) owners[tip] = target
            val at = c.boundsInWindow()
            if (rects[tip] != at) rects[tip] = at
            Tips.request(tip)
        } else if (held === target) {
            owners.remove(tip)
            rects.remove(tip)
            Tips.withdraw(tip)
        }
    }

    /** Asks again for every target's tip, from where it now is (a layer moving it doesn't lay it out again). */
    internal fun retry() {
        for ((target, c) in live.entries.toList()) {
            if (!Tips.wanted(target.tip)) continue
            if (c.isAttached) offer(target, c) else release(target)
        }
    }

    internal fun release(target: TipTarget) {
        live.remove(target)
        val tip = target.tip
        if (owners[tip] === target) {
            owners.remove(tip)
            rects.remove(tip)
            Tips.withdraw(tip)
        }
    }
}

/**
 * A target for [tip] with its [text], or null when the tip can't show any
 * more (seen, or tips are off), so places that have none to give pay nothing.
 */
@Composable
fun rememberTipTarget(tip: Tip, text: String, enabled: Boolean = true, inside: Boolean = false): TipTarget? {
    val on by Tips.enabled.collectAsState()
    val seen by Tips.seen.collectAsState()
    if (!enabled || !on || tip in seen) return null
    val target = remember(tip) { TipTarget(tip) }
    target.text = text
    target.inside = inside
    DisposableEffect(target) { onDispose { TipAnchors.release(target) } }
    return target
}

/** Marks this element as where [target]'s tip points. */
fun Modifier.tipAnchor(target: TipTarget?): Modifier =
    if (target == null) this else onGloballyPositioned { TipAnchors.offer(target, it) }

/** Whether [target]'s tip is up and pointing at it (and not at another row offering the same tip). */
@Composable
fun tipShowing(target: TipTarget?): Boolean {
    val active by Tips.active.collectAsState()
    return target != null && active == target.tip && TipAnchors.owners[target.tip] === target
}

/**
 * Closes the tip that's up at the first touch anywhere, without taking the
 * touch: what the finger meant to do still happens. A tip up only a moment
 * comes back later rather than counting as read (see [Tips.withdraw]).
 */
fun Modifier.tipsCloseOnTouch(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        Tips.active.value?.let(Tips::withdraw)
    }
}

/** Draws the tip that's up next to its target, over everything else. Placed once, in the app's root box. */
@Composable
fun BoxScope.TipHost(reduceMotion: Boolean) {
    val active by Tips.active.collectAsState()
    val covered = active?.let(TipAnchors::covered) == true
    // The player opening over a page tip's target (or closing over the player's own): it goes.
    LaunchedEffect(active, covered) {
        val tip = active ?: return@LaunchedEffect
        if (covered) Tips.withdraw(tip)
    }
    // Asked again now and then: see TipAnchors.live. Only while some tip could still show.
    val on by Tips.enabled.collectAsState()
    val seen by Tips.seen.collectAsState()
    if (on && seen.size < Tip.entries.size) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(RETRY_MS)
                TipAnchors.retry()
            }
        }
    }
    val spot = active?.let { tip ->
        val target = TipAnchors.owners[tip]
        val at = TipAnchors.rects[tip]
        if (target != null && at != null) target to at else null
    }
    // A beat before showing, so a tip doesn't flash up under a page still sliding in.
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        ready = false
        if (active != null) { delay(SETTLE_MS); ready = true }
    }
    // Kept through the fade out, after the tip itself has gone.
    var last by remember { mutableStateOf<Pair<TipTarget, Rect>?>(null) }
    if (spot != null) last = spot
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val shown = last ?: return@Box
        AnimatedVisibility(
            visible = spot != null && ready,
            enter = if (reduceMotion) fadeIn(tween(150)) else fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = fadeOut(tween(150)) + if (reduceMotion) scaleOut(targetScale = 1f) else scaleOut(tween(150), targetScale = 0.95f),
        ) {
            TipBubbleAt(shown.first, shown.second.translate(-origin), onClose = { Tips.dismiss(shown.first.tip) })
        }
    }
}

/** The bubble placed under its target, or over it where there's no room below, its arrow toward it. */
@Composable
private fun TipBubbleAt(target: TipTarget, at: Rect, onClose: () -> Unit) {
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    val maxWidth = with(androidx.compose.ui.platform.LocalDensity.current) { 340.dp.roundToPx() }
    val bottomRoom = with(androidx.compose.ui.platform.LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toFloat()
    }
    var below by remember { mutableStateOf(true) }
    var arrowX by remember { mutableStateOf(0f) }
    Layout(
        content = { TipBubble(target.text, arrow = if (target.inside) null else below, arrowX = { arrowX }, onClose = onClose) },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = minOf(maxWidth, constraints.maxWidth - (margin * 2).roundToInt()).coerceAtLeast(0)
        val bubble = measurables.first().measure(constraints.copy(minWidth = 0, maxWidth = width, minHeight = 0))
        val x = (at.center.x - bubble.width / 2f).coerceIn(margin, (constraints.maxWidth - margin - bubble.width).coerceAtLeast(margin))
        val y = when {
            target.inside -> at.bottom - bubble.height - margin
            at.bottom + gap + bubble.height <= constraints.maxHeight - bottomRoom - margin -> { below = true; at.bottom + gap }
            else -> { below = false; at.top - gap - bubble.height }
        }
        arrowX = (at.center.x - x).coerceIn(margin * 1.5f, bubble.width - margin * 1.5f)
        layout(constraints.maxWidth, constraints.maxHeight) { bubble.place(IntOffset(x.roundToInt(), y.roundToInt())) }
    }
}

/** The tip's words in a dark card (light in dark theme), with "Got it", and an arrow up ([arrow] true) or down. */
@Composable
private fun TipBubble(text: String, arrow: Boolean?, arrowX: () -> Float, onClose: () -> Unit) {
    val fill = MaterialTheme.colorScheme.inverseSurface
    val ink = MaterialTheme.colorScheme.inverseOnSurface
    // As wide as the card, so the arrows' canvases span it and no more.
    Column(Modifier.width(IntrinsicSize.Max).semantics { liveRegion = LiveRegionMode.Polite }) {
        if (arrow == true) Arrow(up = true, fill, arrowX)
        Row(
            Modifier.background(fill, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f, fill = false).padding(vertical = 8.dp), color = ink, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onClose) {
                Text("Got it", color = MaterialTheme.colorScheme.inversePrimary, fontWeight = FontWeight.SemiBold)
            }
        }
        if (arrow == false) Arrow(up = false, fill, arrowX)
    }
}

@Composable
private fun Arrow(up: Boolean, fill: androidx.compose.ui.graphics.Color, arrowX: () -> Float) {
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val x = arrowX()
        val half = 8.dp.toPx()
        val path = Path().apply {
            if (up) { moveTo(x - half, size.height); lineTo(x, 0f); lineTo(x + half, size.height) }
            else { moveTo(x - half, 0f); lineTo(x, size.height); lineTo(x + half, 0f) }
            close()
        }
        drawPath(path, fill)
    }
}

private const val SETTLE_MS = 450L
private const val RETRY_MS = 800L
