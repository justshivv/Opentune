package com.opentune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The full player growing out of the mini player as one card, and shrinking
 * back into it: the card's bounds run between the mini player's
 * ([PlayerMorph.miniBar]) and the screen, its corners round as it gets
 * smaller, and near the mini player's size it darkens to the mini player's
 * face and fades onto it. A pull up on the mini player moves it with the
 * finger ([PlayerMorph.pulling]); a drag down on the player hands over where
 * the card was ([PlayerMorph.handOff]) and the shrink carries on from there.
 *
 * [content] is laid out once at full size. Each frame only moves, scales and
 * clips its layer, on the render thread: nothing is measured or composed
 * again while the card moves.
 */
@Composable
fun PlayerSheet(open: Boolean, onSettled: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val t = remember { Animatable(if (open) 1f else 0f) }
    // Where the mini player's top was when last drawn, to turn a drag in pixels into the card's progress.
    val miniTop = remember { floatArrayOf(0f) }
    LaunchedEffect(open) {
        if (open) {
            if (PlayerMorph.sheetAt > t.value) t.snapTo(PlayerMorph.sheetAt)
            PlayerMorph.sheetAt = 0f
            PlayerMorph.cardFrom = null
            // The first frames build the player (out of sight, or under the
            // finger): the card starts moving once they're done, not through them.
            withFrameNanos { }
            withFrameNanos { }
            t.animateTo(1f, tween(sheetMs(1f - t.value, SHEET_OPEN_MS), easing = SHEET_EASING))
        } else {
            // Dragged down part of the way already: the shrink goes on from where the finger let go, at its speed.
            val dragged = PlayerMorph.sheetDrag
            val fling = PlayerMorph.sheetFling
            if (dragged > 0f) {
                t.snapTo(dragProgress(t.value, dragged, miniTop[0]))
                PlayerMorph.sheetDrag = 0f
                PlayerMorph.sheetFling = 0f
            }
            // The page and dock come back under it on this frame.
            withFrameNanos { }
            // An even glide down to the mini player; after a quick flick, no slower than the finger was going.
            val left = t.value
            val byFling = if (fling < -0.01f) (left / -fling * 1_000f * FLING_STRETCH).toInt() else Int.MAX_VALUE
            t.animateTo(0f, tween(minOf(sheetMs(left, SHEET_CLOSE_MS), byFling).coerceAtLeast(SHEET_MIN_MS), easing = SHEET_EASING))
            PlayerMorph.cardFrom = null
        }
    }
    val settled by remember { derivedStateOf { t.value >= 1f && !t.isRunning } }
    val report by rememberUpdatedState(onSettled)
    LaunchedEffect(settled, open) { report(settled && open) }

    val shown by remember { derivedStateOf { t.value > 0f || PlayerMorph.pulling > 0f || PlayerMorph.sheetAt > 0f } }
    if (!open && !shown) return

    val density = LocalDensity.current
    val barCorner = with(density) { 22.dp.toPx() }
    val shadow = with(density) { 18.dp.toPx() }
    val fallbackLift = with(density) { 120.dp.toPx() }
    val fallbackHeight = with(density) { 64.dp.toPx() }
    val face = MaterialTheme.colorScheme.surfaceContainerHigh
    // Where the card is, 0 on the mini player and 1 filling the screen; a drag down
    // takes it toward 0 with the card's top under the finger.
    val p: () -> Float = { dragProgress(maxOf(t.value, PlayerMorph.pulling, PlayerMorph.sheetAt), PlayerMorph.sheetDrag, miniTop[0]) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@graphicsLayer
                val mini = PlayerMorph.miniBar
                    ?: Rect(w * 0.04f, h - fallbackLift - fallbackHeight, w * 0.96f, h - fallbackLift)
                miniTop[0] = mini.top
                val k = p().coerceIn(0f, 1f)
                val card = PlayerMorph.cardFrom
                val from = card ?: Rect(0f, 0f, w, h)
                fun lerp(a: Float, b: Float) = a + (b - a) * k
                val left = lerp(mini.left, from.left)
                val top = lerp(mini.top, from.top)
                val width = lerp(mini.width, from.width).coerceAtLeast(1f)
                val height = lerp(mini.height, from.height)
                // Scaled by width alone, so the player inside keeps its shape;
                // the clip below takes it down to the card's height.
                val s = width / w
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = left
                translationY = top
                scaleX = s
                scaleY = s
                val miniCorner = if (PlayerMorph.miniBarRound) mini.height / 2f else barCorner
                val corner = lerp(miniCorner, if (card != null) PlayerMorph.cardCornerPx else 0f) / s
                val clipHeight = (height / s).coerceAtMost(h)
                if (k < 1f || card != null) {
                    shape = CardShape(clipHeight, corner)
                    clip = true
                    shadowElevation = shadow * minOf(1f, (1f - k) * 4f, k * 4f)
                } else {
                    clip = false
                    shadowElevation = 0f
                }
                // On the mini player's size, it fades onto the real one beneath.
                alpha = (k / 0.12f).coerceIn(0f, 1f)
            },
    ) {
        content()
        // The mini player's face, coming over the player as the card gets small.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ((0.45f - p()) / 0.45f).coerceIn(0f, 1f) }
                .background(face),
        )
    }
}

/**
 * How the card moves between the mini player and the screen: a soft start,
 * an even pace through the middle and a gentle landing, so the whole move
 * reads instead of most of it happening in the first few frames, as the
 * springs it replaced did.
 */
internal val SHEET_EASING = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.1f, 0.2f, 1f)
internal const val SHEET_OPEN_MS = 500
internal const val SHEET_CLOSE_MS = 460
private const val SHEET_MIN_MS = 180
/** The eased start is slower than a straight line; this keeps a flick's speed through it. */
private const val FLING_STRETCH = 1.6f

/** The time for [left] of the whole way (0..1), out of [full] for all of it. */
private fun sheetMs(left: Float, full: Int): Int = (full * left.coerceIn(0f, 1f)).toInt().coerceAtLeast(SHEET_MIN_MS)

/**
 * The card's progress [base] taken down by a drag of [dragPx]: the card's top
 * runs from the screen's top (1) to the mini player's [miniTop] (0), so
 * this keeps it under the finger.
 */
private fun dragProgress(base: Float, dragPx: Float, miniTop: Float): Float =
    if (dragPx <= 0f || miniTop <= 0f) base else minOf(base, 1f - (dragPx / miniTop).coerceIn(0f, 1f))

/** The card's outline: [height] tall from the top, with [corner] corners, in the layer's own pixels. */
private class CardShape(private val height: Float, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, size.width, minOf(height, size.height), CornerRadius(corner)))
}
