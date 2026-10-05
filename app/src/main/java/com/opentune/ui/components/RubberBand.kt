package com.opentune.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * iOS-style overscroll: pulling past the end of a list moves the content
 * with growing resistance, letting go springs it back, and a fling that hits
 * the end gives a small bounce. Replaces Android's stretch effect app-wide
 * through [LocalOverscrollFactory].
 */
object RubberBandOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = RubberBandOverscroll()
    override fun hashCode(): Int = 7
    override fun equals(other: Any?): Boolean = other === this
}

/**
 * The offset kept is the one drawn, not how far the finger has travelled.
 * Pulling further adds to it less and less (Apple's curve, applied a step at
 * a time), while pushing back takes it away one to one, so the content
 * follows the finger straight back instead of the finger first having to
 * undo a long hidden pull.
 */
private class RubberBandOverscroll : OverscrollEffect {
    private var offsetX by mutableFloatStateOf(0f)
    private var offsetY by mutableFloatStateOf(0f)
    private var width = 0f
    private var height = 0f
    private var settling: Job? = null

    override val isInProgress: Boolean get() = offsetX != 0f || offsetY != 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        // A finger landing on a bounce takes hold of it where it is.
        if (source == NestedScrollSource.UserInput) settling?.cancel()
        // Scrolling back toward the content first takes up the offset.
        val relaxX = relax(offsetX, delta.x)
        val relaxY = relax(offsetY, delta.y)
        // [relax] points back toward zero, so it is added.
        offsetX += relaxX
        offsetY += relaxY
        val remaining = Offset(delta.x - relaxX, delta.y - relaxY)
        val consumed = performScroll(remaining)
        val left = remaining - consumed
        // Only the finger stretches; a fling that reaches the end bounces in applyToFling.
        if (source == NestedScrollSource.UserInput) {
            offsetX = stretch(offsetX, left.x, width)
            offsetY = stretch(offsetY, left.y, height)
            return delta
        }
        return Offset(relaxX, relaxY) + consumed
    }

    /** The part of [delta] that moves against [offset], up to the offset itself. */
    private fun relax(offset: Float, delta: Float): Float =
        if (offset != 0f && delta != 0f && sign(offset) != sign(delta)) sign(delta) * minOf(abs(delta), abs(offset)) else 0f

    /**
     * Adds [delta] with the slope of Apple's rubber band, c·(1 − x/d)²,
     * so the content never goes past the container's own size.
     */
    private fun stretch(offset: Float, delta: Float, dimension: Float): Float {
        if (delta == 0f) return offset
        if (dimension <= 0f) return offset + delta * RESISTANCE
        val room = (1f - abs(offset) / dimension).coerceAtLeast(0f)
        return (offset + delta * RESISTANCE * room * room).coerceIn(-dimension, dimension)
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        settling?.cancel()
        // Flicking further out keeps the stretch and springs back from there;
        // flicking back in runs the fling, which takes up the offset first.
        val outward = (offsetY != 0f && sign(velocity.y) == sign(offsetY)) ||
            (offsetX != 0f && sign(velocity.x) == sign(offsetX))
        val left = if (outward) velocity else performFling(velocity)
        // The spring runs on the effect's own node, so a new touch elsewhere
        // that cancels this fling can't leave the content parked off its place.
        val scope = node.takeIf { it.isAttached }?.coroutineScope
        if (scope == null) {
            settle(left)
        } else {
            val job = scope.launch { settle(left) }
            settling = job
            job.join()
        }
    }

    private suspend fun settle(velocity: Velocity) {
        if (offsetY != 0f || abs(velocity.y) > MIN_BOUNCE) {
            val v = velocity.y.coerceIn(-MAX_VELOCITY, MAX_VELOCITY) * if (offsetY == 0f) BOUNCE else 1f
            AnimationState(offsetY, v).animateTo(0f, settleSpring(v)) { offsetY = value }
        }
        if (offsetX != 0f || abs(velocity.x) > MIN_BOUNCE) {
            val v = velocity.x.coerceIn(-MAX_VELOCITY, MAX_VELOCITY) * if (offsetX == 0f) BOUNCE else 1f
            AnimationState(offsetX, v).animateTo(0f, settleSpring(v)) { offsetX = value }
        }
    }

    private fun settleSpring(v: Float) =
        spring<Float>(Spring.DampingRatioNoBouncy, if (abs(v) > FAST_VELOCITY) 160f else 260f)

    override val node: Modifier.Node = object : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            width = placeable.width.toFloat()
            height = placeable.height.toFloat()
            return layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(0, 0) {
                    translationX = offsetX
                    translationY = offsetY
                }
            }
        }

        override fun onDetach() {
            // Coming back into view, a list starts where it belongs.
            offsetX = 0f
            offsetY = 0f
        }
    }

    private companion object {
        const val RESISTANCE = 0.55f
        const val FAST_VELOCITY = 5_000f
        const val MAX_VELOCITY = 8_000f
        /** Below this, a fling reaching the end just stops. */
        const val MIN_BOUNCE = 600f
        /** A fling reaching the end bounces with a share of what it had left. */
        const val BOUNCE = 0.35f
    }
}
