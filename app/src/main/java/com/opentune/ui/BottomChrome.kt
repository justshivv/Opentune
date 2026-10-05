package com.opentune.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.glass

/** Room the chrome takes when shown, for content padding. */
val CHROME_TAB_HEIGHT = 64.dp
val CHROME_MINI_HEIGHT = 64.dp
private val BUBBLE = 62.dp
private val DOCK_SHAPE = RoundedCornerShape(24.dp)

/**
 * Tucks the dock away while content scrolls down and brings it back when it
 * scrolls up. Direction has to hold for [thresholdPx] before anything
 * changes, so a small wobble mid-scroll doesn't flicker it. Nothing is
 * consumed: the list keeps every pixel of its scroll.
 */
class ChromeScrollConnection(private val thresholdPx: Float) : NestedScrollConnection {
    var inline by mutableStateOf(false)
        private set
    private var accumulated = 0f

    fun expand() {
        inline = false
        accumulated = 0f
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        if ((accumulated > 0 && dy < 0) || (accumulated < 0 && dy > 0)) accumulated = 0f
        accumulated += dy
        if (accumulated <= -thresholdPx && !inline) {
            inline = true
            accumulated = 0f
        } else if (accumulated >= thresholdPx && inline) {
            inline = false
            accumulated = 0f
        }
        return Offset.Zero
    }
}

@Composable
fun rememberChromeScroll(threshold: Dp = 48.dp): ChromeScrollConnection {
    val px = with(LocalDensity.current) { threshold.toPx() }
    return remember(px) { ChromeScrollConnection(px) }
}

class ChromeTab(val label: String, val icon: ImageVector)

/**
 * OpenTune's bottom chrome: the now-playing card over one dock that holds
 * every destination, Search included. The current destination is a filled
 * capsule in the accent colour with its name; the others are icons.
 *
 * While a page scrolls down, the dock tucks away and the now-playing card
 * shrinks into a round bubble of the cover, ringed by the song's progress,
 * in the corner. Scrolling back up brings both back. The card and the bubble
 * are one shared element, so the cover travels between them.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BottomChrome(
    inline: Boolean,
    tabs: List<ChromeTab>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onExpand: () -> Unit,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    mini: (@Composable (inline: Boolean, modifier: Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout(modifier) {
        AnimatedContent(
            inline,
            transitionSpec = {
                (fadeIn(spring(stiffness = 380f)) togetherWith fadeOut(spring(stiffness = 380f)))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.BottomEnd,
            label = "chrome",
        ) { folded ->
            val shared = SharedMini(this@SharedTransitionLayout, this)
            if (folded) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.BottomEnd) {
                    if (mini != null) {
                        mini(true, with(shared) { Modifier.sharedMini() }.size(BUBBLE))
                    } else {
                        // Nothing playing: a small button brings the dock back.
                        val current = selected?.let(tabs::getOrNull) ?: tabs.first()
                        Box(
                            Modifier.size(52.dp).glass(RoundedCornerShape(18.dp)).clickable(onClick = onExpand),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(current.icon, "Show navigation", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mini != null) mini(false, with(shared) { Modifier.sharedMini() }.fillMaxWidth().height(CHROME_MINI_HEIGHT))
                    Dock(tabs, selected, onSelect, searchSelected, onSearch)
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private class SharedMini(private val layout: SharedTransitionScope, private val scope: AnimatedVisibilityScope) {
    @Composable
    fun Modifier.sharedMini(): Modifier = with(layout) {
        this@sharedMini.sharedElement(
            rememberSharedContentState("mini"),
            scope,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.8f, stiffness = 360f) },
        )
    }
}

/** Home, Search, then the other destinations, in one bar. */
@Composable
private fun Dock(tabs: List<ChromeTab>, selected: Int?, onSelect: (Int) -> Unit, searchSelected: Boolean, onSearch: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val items = buildList {
        tabs.forEachIndexed { i, t -> add(Triple(t, i == selected) { onSelect(i) }) }
        add(1.coerceAtMost(size), Triple(ChromeTab("Search", Icons.Rounded.Search), searchSelected, onSearch))
    }
    Row(
        Modifier.fillMaxWidth().height(CHROME_TAB_HEIGHT).glass(DOCK_SHAPE).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { (tab, isSelected, onClick) ->
            DockItem(tab, isSelected) {
                if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        }
    }
}

@Composable
private fun DockItem(tab: ChromeTab, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        spring(stiffness = 500f),
        label = "dockBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "dockFg",
    )
    Row(
        Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(24.dp))
        AnimatedVisibility(
            selected,
            enter = fadeIn() + expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 500f)),
            exit = fadeOut() + shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 600f)),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(tab.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg)
            }
        }
    }
}
