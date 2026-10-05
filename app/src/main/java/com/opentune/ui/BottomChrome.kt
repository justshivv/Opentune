package com.opentune.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.opentune.ui.components.liquidGlassOn

/** Room the bar's two rows take when unfolded, for content padding. */
val CHROME_TAB_HEIGHT = 72.dp
val CHROME_MINI_HEIGHT = 66.dp
private val INLINE_HEIGHT = 58.dp
private val GAP = 8.dp

/**
 * Folds the bottom bar while content scrolls down and unfolds it when it
 * scrolls back up. Direction has to hold for [thresholdPx] before the bar
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
fun rememberChromeScroll(threshold: Dp = 50.dp): ChromeScrollConnection {
    val px = with(LocalDensity.current) { threshold.toPx() }
    return remember(px) { ChromeScrollConnection(px) }
}

class ChromeTab(val label: String, val icon: ImageVector)

/**
 * The floating bottom bar in its two shapes.
 *
 * Unfolded: the mini player across the full width, and under it the tab pill
 * and the round search button. Folded: one row of a round button showing the
 * current tab, the mini player squeezed between, and search.
 *
 * The pill, the current tab's icon, the mini player and search are shared
 * elements, so each one slides and resizes into its new place on a spring
 * while the rest cross-fades.
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
                (fadeIn(spring(stiffness = 420f)) togetherWith fadeOut(spring(stiffness = 420f)))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.BottomCenter,
            label = "chrome",
        ) { folded ->
            val scope = this
            val shared = SharedKeys(this@SharedTransitionLayout, scope)
            if (folded) {
                Row(
                    Modifier.fillMaxWidth().height(INLINE_HEIGHT).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val current = selected?.let(tabs::getOrNull) ?: tabs.first()
                    Box(
                        with(shared) { Modifier.sharedTabs() }.size(INLINE_HEIGHT).glass(CircleShape).clickable(onClick = onExpand),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(current.icon, current.label, with(shared) { Modifier.sharedIcon() }.size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    if (mini != null) mini(true, with(shared) { Modifier.sharedMini() }.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                    SearchButton(searchSelected, onSearch, with(shared) { Modifier.sharedSearch() }.size(INLINE_HEIGHT))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(GAP)) {
                    if (mini != null) mini(false, with(shared) { Modifier.sharedMini() }.fillMaxWidth().height(CHROME_MINI_HEIGHT))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TabPill(tabs, selected, onSelect, shared, Modifier.weight(1f))
                        SearchButton(searchSelected, onSearch, with(shared) { Modifier.sharedSearch() }.size(CHROME_TAB_HEIGHT))
                    }
                }
            }
        }
    }
}

/** The shared-element keys, with one spring for every bounds change. */
@OptIn(ExperimentalSharedTransitionApi::class)
private class SharedKeys(private val layout: SharedTransitionScope, private val scope: AnimatedVisibilityScope) {
    @Composable
    private fun Modifier.key(name: String): Modifier = with(layout) {
        this@key.sharedElement(
            rememberSharedContentState(name),
            scope,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.82f, stiffness = 380f) },
        )
    }

    @Composable fun Modifier.sharedTabs() = key("tabs")
    @Composable fun Modifier.sharedIcon() = key("tab-icon")
    @Composable fun Modifier.sharedMini() = key("mini")
    @Composable fun Modifier.sharedSearch() = key("search")
}

/** The glass pill holding the main tabs; the current one sits in a lighter inset pill. */
@Composable
private fun TabPill(tabs: List<ChromeTab>, selected: Int?, onSelect: (Int) -> Unit, shared: SharedKeys, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val liquid = liquidGlassOn()
    Row(
        with(shared) { modifier.sharedTabs() }.height(CHROME_TAB_HEIGHT).glass(RoundedCornerShape(36.dp)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, t ->
            val isSelected = i == selected
            // On Liquid Glass the current tab sits in a dark scrim, as on iOS;
            // on frosted or solid glass, a lighter inset reads better.
            val selectedBg = if (liquid) Color.Black.copy(alpha = 0.34f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            val bg by animateColorAsState(
                if (isSelected) selectedBg else Color.Transparent,
                spring(dampingRatio = 0.72f, stiffness = 320f),
                label = "tabBg",
            )
            val fg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tabFg",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(30.dp))
                    .background(bg)
                    .clickable {
                        if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(i)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val iconModifier = if (isSelected) with(shared) { Modifier.sharedIcon() } else Modifier
                Icon(t.icon, null, tint = fg, modifier = iconModifier.size(26.dp))
                Text(t.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg)
            }
        }
    }
}

@Composable
private fun SearchButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .glass(CircleShape, if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Search, "Search", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}
