package com.opentune.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.glass
import kotlinx.coroutines.launch

/** How far every page keeps its content from the edges of the page area. */
internal val GUTTER = 32.dp

/**
 * A row that scrolls sideways, with a round frosted arrow at each end under
 * the pointer that pages it along, since a mouse wheel scrolls down the page
 * rather than along a row.
 */
@Composable
internal fun HRow(spacing: Dp = 16.dp, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val hover = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxWidth().hoverable(hover)) {
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(spacing), content = content)
        PageArrows(state, hover) { state.layoutInfo.viewportSize.width.toFloat() }
    }
}

/** A grid of [rows] rows that scrolls sideways, [height] tall, with the same arrows as [HRow]. */
@Composable
internal fun HGrid(rows: Int, height: Dp, spacing: Dp = 12.dp, rowSpacing: Dp = 4.dp, content: LazyGridScope.() -> Unit) {
    val state = rememberLazyGridState()
    val hover = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxWidth().hoverable(hover)) {
        LazyHorizontalGrid(
            GridCells.Fixed(rows), Modifier.fillMaxWidth().height(height), state = state,
            contentPadding = PaddingValues(horizontal = GUTTER),
            horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(rowSpacing),
            content = content,
        )
        PageArrows(state, hover) { state.layoutInfo.viewportSize.width.toFloat() }
    }
}

@Composable
private fun BoxScope.PageArrows(state: ScrollableState, hover: MutableInteractionSource, width: () -> Float) {
    val hovered by hover.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    fun page(direction: Int) = scope.launch { state.animateScrollBy(direction * width() * 0.8f, tween(560, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
    Arrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", hovered && state.canScrollBackward, Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) { page(-1) }
    Arrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "More", hovered && state.canScrollForward, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) { page(1) }
}

@Composable
private fun Arrow(icon: ImageVector, label: String, shown: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(shown, modifier, enter = fadeIn(tween(160)) + scaleIn(tween(200), 0.8f), exit = fadeOut(tween(120)) + scaleOut(tween(140), 0.8f)) {
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier.size(40.dp).hoverLift(press, 1.08f).shadow(10.dp, CircleShape)
                .glass(CircleShape, MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f))
                .clickable(interactionSource = press, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
    }
}
