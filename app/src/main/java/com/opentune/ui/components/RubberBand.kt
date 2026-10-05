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
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.sign

/**
 * iOS-style overscroll: pulling past the end of a list moves the content
 * with growing resistance, and letting go springs it back. Replaces
 * Android's stretch effect app-wide through [LocalOverscrollFactory].
 */
object RubberBandOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = RubberBandOverscroll()
    override fun hashCode(): Int = 7
    override fun equals(other: Any?): Boolean = other === this
}

private class RubberBandOverscroll : OverscrollEffect {
    /** How far the finger has pulled past the edge, in raw pixels per axis. */
    private var pullX by mutableFloatStateOf(0f)
    private var pullY by mutableFloatStateOf(0f)
    private var width = 0f
    private var height = 0f

    override val isInProgress: Boolean get() = pullX != 0f || pullY != 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        // Scrolling back toward the content first relaxes any stretch.
        val relaxX = relax(pullX, delta.x)
        val relaxY = relax(pullY, delta.y)
        pullX -= relaxX
        pullY -= relaxY
        val remaining = Offset(delta.x - relaxX, delta.y - relaxY)
        val consumed = performScroll(remaining)
        val left = remaining - consumed
        // Only the finger stretches; a fling hitting the end just stops there.
        if (source == NestedScrollSource.UserInput) {
            pullX += left.x
            pullY += left.y
            return delta
        }
        return Offset(relaxX, relaxY) + consumed
    }

    /** The part of [delta] that moves against [pull], up to the pull itself. */
    private fun relax(pull: Float, delta: Float): Float =
        if (pull != 0f && delta != 0f && sign(pull) != sign(delta)) sign(delta) * minOf(abs(delta), abs(pull)) else 0f

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        val left = if (isInProgress) velocity else performFling(velocity)
        settle(left)
    }

    private suspend fun settle(velocity: Velocity) {
        val settleSpring = { v: Float ->
            spring<Float>(Spring.DampingRatioNoBouncy, if (abs(v) > FAST_VELOCITY) 130f else 247f)
        }
        if (pullY != 0f) {
            val v = velocity.y.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
            AnimationState(pullY, v).animateTo(0f, settleSpring(v)) { pullY = value }
        }
        if (pullX != 0f) {
            val v = velocity.x.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
            AnimationState(pullX, v).animateTo(0f, settleSpring(v)) { pullX = value }
        }
    }

    /** Apple's curve: moves freely at first, then ever harder, never past the container. */
    private fun displayed(pull: Float, dimension: Float): Float {
        if (pull == 0f || dimension <= 0f) return 0f
        val d = abs(pull)
        return sign(pull) * (1f - 1f / (d * RESISTANCE / dimension + 1f)) * dimension / RESISTANCE
    }

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            width = placeable.width.toFloat()
            height = placeable.height.toFloat()
            return layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(0, 0) {
                    translationX = displayed(pullX, width)
                    translationY = displayed(pullY, height)
                }
            }
        }
    }

    private companion object {
        const val RESISTANCE = 0.55f
        const val FAST_VELOCITY = 5_000f
        const val MAX_VELOCITY = 10_000f
    }
}
