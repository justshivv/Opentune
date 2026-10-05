package com.opentune.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
private val DOCK_SHAPE = RoundedCornerShape(32.dp)
private val LENS_SHAPE = RoundedCornerShape(26.dp)
private val DOCK_INSET = 6.dp

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

/** A destination in the dock: its name, its outline icon, and the filled one shown while it's open. */
class ChromeTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

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

/**
 * The dock: a floating glass pill holding the destinations, each an icon
 * over its name, with a soft lens of the accent colour that slides to the
 * open one; and Search in its own round button beside it.
 */
@Composable
private fun Dock(tabs: List<ChromeTab>, selected: Int?, onSelect: (Int) -> Unit, searchSelected: Boolean, onSearch: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().height(CHROME_TAB_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().dockShadow(DOCK_SHAPE).glass(DOCK_SHAPE).dockHighlight(DOCK_SHAPE).padding(DOCK_INSET),
        ) {
            val slot = maxWidth / tabs.size
            val shown = selected?.takeIf { it in tabs.indices }
            // The lens rests where it last was while Search is open, faded out.
            var resting by remember { mutableIntStateOf(shown ?: 0) }
            if (shown != null) resting = shown
            val x by animateDpAsState(slot * resting, spring(dampingRatio = 0.72f, stiffness = 380f), label = "lensX")
            val lensAlpha by animateFloatAsState(if (shown != null) 1f else 0f, spring(stiffness = 500f), label = "lensAlpha")
            val accent = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }
                    .width(slot)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = lensAlpha }
                    .clip(LENS_SHAPE)
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.14f))))
                    .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.08f))), LENS_SHAPE),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, tab ->
                    DockItem(tab, i == shown, Modifier.width(slot).fillMaxHeight()) {
                        if (i != shown) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(i)
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        SearchOrb(searchSelected) {
            if (!searchSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSearch()
        }
    }
}

/** One destination: the icon, filled and lifted a little when open, over its name. */
@Composable
private fun DockItem(tab: ChromeTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.86f
            selected -> 1.08f
            else -> 1f
        },
        spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "dockScale",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        spring(stiffness = 500f),
        label = "dockFg",
    )
    Column(
        modifier
            .clip(LENS_SHAPE)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Crossfade(selected, animationSpec = spring(stiffness = 600f), label = "dockIcon") { on ->
            Icon(
                if (on) tab.selectedIcon else tab.icon,
                null,
                tint = fg,
                modifier = Modifier.size(25.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            )
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Search on its own: a glass circle that fills with the accent while search is open. */
@Composable
private fun SearchOrb(selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "orbScale")
    val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, spring(stiffness = 500f), label = "orbFill")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, label = "orbFg")
    Box(
        Modifier
            .size(CHROME_TAB_HEIGHT)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .dockShadow(CircleShape)
            .glass(CircleShape)
            .dockHighlight(CircleShape)
            .padding(DOCK_INSET)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Search, "Search", tint = fg, modifier = Modifier.size(26.dp))
    }
}

/** A hairline of light along the top edge, fading down, so the glass reads as lit from above. */
@Composable
private fun Modifier.dockHighlight(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val light = Color.White.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.22f else 0.6f)
    return this.border(1.dp, Brush.verticalGradient(listOf(light, Color.Transparent, Color.Transparent)), shape)
}

/** A soft drop shadow so the dock floats over the page. */
private fun Modifier.dockShadow(shape: androidx.compose.ui.graphics.Shape): Modifier =
    shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
