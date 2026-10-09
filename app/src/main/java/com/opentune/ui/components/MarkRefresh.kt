package com.opentune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.ui.theme.songAccents
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Pull to refresh with the OpenTune mark. Pulling brings the mark in and
 * fills it with the accent from left to right, the page coming down after
 * the finger with some give; passing the line gives a tick and a pop. While
 * it loads, the mark sways like a sound wave with light running across it;
 * when it's done, it shrinks away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val haptics = rememberHaptics()
    val loading by androidx.compose.runtime.rememberUpdatedState(isRefreshing)
    // A tick as the pull passes the line (not as the indicator settles while loading).
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }.distinctUntilChanged().collect { past ->
            if (past && !loading) haptics.tick()
        }
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { haptics.press(); onRefresh() },
        state = state,
        modifier = modifier,
        indicator = { MarkIndicator(state, isRefreshing, still, Modifier.align(Alignment.TopCenter)) },
    ) {
        // The page follows the pull, slowing as it goes, and holds a little down while loading.
        Box(
            Modifier.graphicsLayer {
                val f = state.distanceFraction
                translationY = PULL_ROOM.toPx() * (if (f <= 1f) f else 1f + (1f - 1f / f) * 0.6f)
            },
        ) { content() }
    }
}

/** How far the page comes down at the line. */
private val PULL_ROOM = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkIndicator(state: PullToRefreshState, refreshing: Boolean, still: Boolean, modifier: Modifier) {
    val mark = remember { markPath() }
    // The colours of the song that's playing, so the mark matches the player.
    val (ink, inkEnd) = songAccents()
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    // A pop as the line is passed, and as the load finishes.
    val pop = remember { Animatable(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }.distinctUntilChanged().collect { past ->
            if (past && !still) {
                pop.snapTo(1f)
                pop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
            }
        }
    }
    val t = if (refreshing && !still) {
        val v by rememberInfiniteTransition(label = "refresh").animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart), label = "sway")
        v
    } else 0f
    Canvas(
        modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 18.dp)
            .size(width = 56.dp, height = 40.dp)
            .graphicsLayer {
                val f = state.distanceFraction.coerceIn(0f, 1.6f)
                alpha = (f * 1.6f).coerceIn(0f, 1f)
                // Comes in small and grows with the pull, a little past full when stretched.
                val grow = 0.55f + 0.45f * f.coerceAtMost(1f) + 0.1f * (f - 1f).coerceAtLeast(0f) + 0.18f * pop.value
                scaleX = grow
                scaleY = grow
                translationY = -20.dp.toPx() * (1f - f.coerceAtMost(1f))
            },
    ) {
        // The mark's own extent in the icon's 108 units, so it fills this box.
        val mw = MARK_RIGHT - MARK_LEFT
        val mh = MARK_BOTTOM - MARK_TOP
        val s = minOf(size.width / mw, size.height / mh)
        val ox = (size.width - mw * s) / 2f - MARK_LEFT * s
        val oy = (size.height - mh * s) / 2f - MARK_TOP * s
        val fill = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)
        // While loading, it sways as a wave runs through it.
        val sway = if (refreshing && !still) 1f + 0.12f * sin(2f * PI.toFloat() * t) else 1f
        translate(ox, oy) {
            // Scaled from the corner to fit, then the sway stretches it about its own middle.
            scale(s, s, pivot = Offset.Zero) {
            scale(1f, sway, pivot = Offset((MARK_LEFT + MARK_RIGHT) / 2f, (MARK_TOP + MARK_BOTTOM) / 2f)) {
                drawPath(mark, faint)
                // Filled with the accent from the left as the pull goes on.
                clipRect(right = MARK_LEFT + mw * fill) {
                    drawPath(mark, Brush.horizontalGradient(listOf(ink, inkEnd), startX = MARK_LEFT, endX = MARK_RIGHT))
                }
                if (refreshing && !still) {
                    val x = MARK_LEFT - 20f + (mw + 40f) * t
                    drawPath(
                        mark,
                        Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent), start = Offset(x - 12f, 40f), end = Offset(x + 12f, 68f)),
                    )
                }
            }
            }
        }
    }
}

// Where the mark sits in the icon's 108-unit square.
private const val MARK_LEFT = 27.8f
private const val MARK_RIGHT = 80.3f
private const val MARK_TOP = 34.9f
private const val MARK_BOTTOM = 73.1f
