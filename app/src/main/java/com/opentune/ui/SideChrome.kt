package com.opentune.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import com.opentune.data.settings.DockLens
import com.opentune.ui.components.Haptics
import com.opentune.ui.components.glass
import com.opentune.ui.components.rememberHaptics

/** How wide the rail's glass is. */
private val RAIL_WIDTH = 72.dp
/** Each tab's height in the rail, and the least it shrinks to on a short window. */
private val RAIL_SLOT = 64.dp
private val RAIL_SLOT_MIN = 44.dp
/** Below this the tabs drop their names and keep the icons. */
private val RAIL_SLOT_NAMED = 56.dp
private val RAIL_GAP = 12.dp
/** Space between the side chrome and the screen's edge, and between it and the page. */
private val SIDE_MARGIN = 12.dp

/** How wide the docked player is, and its strip when folded away. */
val PANE_WIDTH = 380.dp
private val STRIP_WIDTH = 72.dp

/** Room the rail takes from the page's start, insets aside. */
val RAIL_GUTTER = SIDE_MARGIN + RAIL_WIDTH + SIDE_MARGIN
/** Room the docked player or its strip takes from the page's end, insets aside. */
val PANE_GUTTER = SIDE_MARGIN + PANE_WIDTH + SIDE_MARGIN
val STRIP_GUTTER = SIDE_MARGIN + STRIP_WIDTH + SIDE_MARGIN

/**
 * The dock stood on its end, for wide windows: Search in its own glass
 * circle over a glass pill of the tabs, each an icon over its name, with the
 * same lens sliding to the open one and the same ink as the dock (white over
 * Liquid Glass). It sits in the middle of the window's start edge, where a
 * thumb holding a tablet reaches it.
 */
@Composable
fun NavRail(
    tabs: List<ChromeTab>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    lens: DockLens = DockLens.STRETCH,
    reduceMotion: Boolean = false,
) {
    val haptics = rememberHaptics()
    val ink = dockInk()
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(horizontal = SIDE_MARGIN, vertical = 8.dp)
            .width(RAIL_WIDTH),
        contentAlignment = Alignment.Center,
    ) {
        // A phone on its side is short: the tabs squeeze before anything spills.
        val room = maxHeight - CHROME_TAB_HEIGHT - RAIL_GAP - DOCK_INSET * 2
        val slot = (room / tabs.size).coerceIn(RAIL_SLOT_MIN, RAIL_SLOT)
        val named = slot >= RAIL_SLOT_NAMED
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RAIL_GAP)) {
            SearchOrb(searchSelected) {
                if (!searchSelected) haptics.tick()
                onSearch()
            }
            Box(
                Modifier
                    .width(RAIL_WIDTH)
                    .height(slot * tabs.size + DOCK_INSET * 2)
                    .dockShadow(DOCK_SHAPE)
                    .glass(DOCK_SHAPE, tint = ink.film ?: MaterialTheme.colorScheme.surfaceContainerHigh)
                    .dockHighlight(DOCK_SHAPE)
                    .padding(DOCK_INSET),
            ) {
                val shown = selected?.takeIf { it in tabs.indices }
                // The lens rests where it last was while Search is open, faded out.
                var resting by remember { mutableIntStateOf(shown ?: 0) }
                if (shown != null) resting = shown
                val (top, bottom) = rememberStretchingLens(resting, slot, if (reduceMotion) DockLens.GLIDE else lens)
                val lensAlpha by animateFloatAsState(if (shown != null) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "railLensAlpha")
                Box(
                    Modifier
                        .offset { IntOffset(0, top().roundToPx()) }
                        .layout { measurable, constraints ->
                            val h = (bottom() - top()).roundToPx().coerceAtLeast(0)
                            val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = lensAlpha
                            // A touch narrower while it's stretched, like a drop pulled lengthways.
                            val stretch = ((bottom() - top()) / slot - 1f).coerceIn(0f, 1f)
                            scaleX = 1f - 0.08f * stretch
                        }
                        .clip(LENS_SHAPE)
                        .background(Brush.verticalGradient(listOf(ink.lensTop, ink.lensBottom)))
                        .border(1.dp, Brush.verticalGradient(listOf(ink.rimTop, ink.rimBottom)), LENS_SHAPE),
                )
                Column(Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { i, tab ->
                        // Unnamed, the icon drops to the middle the way it does as the dock minimizes.
                        DockItem(tab, i == shown, Modifier.fillMaxWidth().height(slot), collapse = { if (named) 0f else 1f }) {
                            if (i != shown) haptics.tick()
                            onSelect(i)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The docked player's frame: a rounded card down the window's end edge,
 * clear of the status and navigation bars, which the player inside then
 * doesn't pad for again.
 */
@Composable
fun PlayerPaneFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
            .padding(vertical = 8.dp, horizontal = SIDE_MARGIN)
            .width(PANE_WIDTH)
            .shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

/**
 * The docked player folded away: a slim glass strip on the end edge with
 * the cover ringed by the song's progress, play or pause, and next. The
 * cover or the arrow opens the player again. [cover] draws the round
 * now-playing bubble.
 */
@Composable
internal fun NowPlayingStrip(
    isPlaying: Boolean,
    hasNext: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable (Modifier) -> Unit,
) {
    val haptics = rememberHaptics()
    val ink = dockInk()
    val shape = RoundedCornerShape(36.dp)
    Box(
        modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
            .padding(horizontal = SIDE_MARGIN, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(STRIP_WIDTH)
                .dockShadow(shape)
                .glass(shape, tint = ink.film ?: MaterialTheme.colorScheme.surfaceContainerHigh)
                .dockHighlight(shape)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StripButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Show player", ink.search, onClick = onExpand)
            cover(Modifier.size(56.dp))
            StripButton(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause" else "Play", ink.search) {
                haptics.pattern(if (isPlaying) Haptics.Pattern.PAUSE else Haptics.Pattern.PLAY)
                onTogglePlay()
            }
            StripButton(Icons.Rounded.SkipNext, "Next", ink.search.copy(alpha = if (hasNext) 1f else 0.4f), enabled = hasNext) {
                haptics.pattern(Haptics.Pattern.NEXT)
                onNext()
            }
        }
    }
}

@Composable
private fun StripButton(icon: ImageVector, label: String, tint: Color, size: Dp = 48.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(26.dp), tint = tint)
    }
}
