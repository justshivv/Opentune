package com.opentune.ui

import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.collectAsState
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.GlassStyle
import com.opentune.ui.components.LocalBackdrop
import com.opentune.ui.components.liquidGlassOn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.SpringSpec
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import com.opentune.data.settings.DockLens
import com.opentune.data.settings.DockMotion
import com.opentune.ui.components.glass

/** Room the chrome takes when shown, for content padding. */
val CHROME_TAB_HEIGHT = 64.dp
val CHROME_MINI_HEIGHT = 64.dp
/** Height of the one-row minimized dock, and of the capsule. */
private val MINI_ROW = 56.dp
/** The capsule: the open tab at one end, the song's cover (or Search) at the other. */
private val CAPSULE_WIDTH = 124.dp
private val CAPSULE_INSET = 4.dp

/** At this many tabs or fewer (Explore moved into Search), the tabs' icons and names are a size up. */
private const val ROOMY_TABS = 3
/** Between the now-playing card and the dock, and between the dock and Search. */
private val CARD_GAP = 8.dp
private val SEARCH_GAP = 10.dp
/** Room around the sinking dock for its shadow, which the blur's layer would otherwise cut off. */
private val SINK_ROOM = 32.dp
/** How far into a sink the dock has gone from sight. */
private const val SINK_GONE = 0.7f

internal val DOCK_SHAPE = RoundedCornerShape(32.dp)
internal val LENS_SHAPE = RoundedCornerShape(26.dp)
internal val DOCK_INSET = 6.dp

/**
 * Tucks the dock away while content scrolls down and brings it back when it
 * scrolls up. Direction has to hold for [thresholdPx] before anything
 * changes, so a small wobble mid-scroll doesn't flicker it. Nothing is
 * consumed: the list keeps every pixel of its scroll. Only what the content
 * really scrolled counts, so a swipe on a page with nothing to scroll (or one
 * already at its end) leaves the dock as it is.
 */
class ChromeScrollConnection(private val thresholdPx: Float) : NestedScrollConnection {
    var inline by mutableStateOf(false)
        private set
    private var accumulated = 0f

    /** Pages holding the dock out while they're shown; see [KeepChromeOut]. */
    internal var holds = 0

    fun expand() {
        inline = false
        accumulated = 0f
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (holds > 0) return Offset.Zero
        val dy = consumed.y
        if (dy == 0f) return Offset.Zero
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

/** The app's [ChromeScrollConnection], for pages that need the dock to stay put. */
val LocalChromeScroll = androidx.compose.runtime.staticCompositionLocalOf<ChromeScrollConnection?> { null }

/**
 * Keeps the dock and the mini player out while the caller is shown, for a
 * page with its own controls stacked on them: scrolling it doesn't tuck
 * them away and leave those controls hanging over empty space.
 */
@Composable
fun KeepChromeOut() {
    val chrome = LocalChromeScroll.current ?: return
    androidx.compose.runtime.DisposableEffect(chrome) {
        chrome.expand()
        chrome.holds++
        onDispose { chrome.holds-- }
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
 * While a page scrolls down the chrome gets out of the way, and scrolling
 * back up brings it back; [motion] picks how. Each motion is one spring from
 * open (0) to folded (1) that every piece reads its size and place from, so
 * a scroll that changes its mind mid-way just turns it around. [reduceMotion]
 * swaps the travel for a short crossfade between the two ends.
 */
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
    motion: DockMotion = DockMotion.MINIMIZE,
    lens: DockLens = DockLens.STRETCH,
    reduceMotion: Boolean = false,
) {
    val c = ChromeContent(tabs, selected, onSelect, onExpand, searchSelected, onSearch, mini, if (reduceMotion) DockLens.GLIDE else lens)
    val chrome: @Composable (Boolean, () -> Float, Modifier) -> Unit = { folded, progress, m ->
        when (motion) {
            DockMotion.MINIMIZE -> MinimizeChrome(c, folded, progress, m)
            DockMotion.CAPSULE -> CapsuleChrome(c, folded, progress, m)
            DockMotion.SINK -> SinkChrome(c, folded, progress, m)
        }
    }
    if (reduceMotion) {
        AnimatedContent(
            inline,
            modifier,
            transitionSpec = {
                (fadeIn(tween(160)) togetherWith fadeOut(tween(120)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(200, easing = FastOutSlowInEasing) })
            },
            contentAlignment = Alignment.BottomCenter,
            label = "chrome",
        ) { folded -> chrome(folded, { if (folded) 1f else 0f }, Modifier) }
        return
    }
    val progress = animateFloatAsState(if (inline) 1f else 0f, motionSpring(motion), label = "dock")
    chrome(inline, { progress.value }, modifier)
}

/** What every form of the chrome shows and does; see [BottomChrome]. */
private class ChromeContent(
    val tabs: List<ChromeTab>,
    val selected: Int?,
    val onSelect: (Int) -> Unit,
    val onExpand: () -> Unit,
    val searchSelected: Boolean,
    val onSearch: () -> Unit,
    val mini: (@Composable (inline: Boolean, modifier: Modifier) -> Unit)?,
    val lens: DockLens,
)

/** The spring each [DockMotion] runs on, from open to folded and back. */
private fun motionSpring(motion: DockMotion): SpringSpec<Float> = when (motion) {
    // Settles without bouncing past.
    DockMotion.MINIMIZE -> spring(dampingRatio = 0.9f, stiffness = 300f, visibilityThreshold = 0.001f)
    // A little give as the capsule closes and opens out again.
    DockMotion.CAPSULE -> spring(dampingRatio = 0.8f, stiffness = 260f, visibilityThreshold = 0.001f)
    // Slower and even, with no overshoot: a sink, not a drop.
    DockMotion.SINK -> spring(dampingRatio = 1f, stiffness = 220f, visibilityThreshold = 0.001f)
}

private fun lerp(a: Dp, b: Dp, t: Float): Dp = a + (b - a) * t
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** 0 up to [from], 1 from [to], easing in and out between. */
private fun ramp(from: Float, to: Float, t: Float): Float {
    val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** The chrome's height at a given moment. Everything in it is placed from the bottom edge, which stays put. */
private fun Modifier.chromeHeight(progress: () -> Float, open: Dp, folded: Dp): Modifier =
    this.layout { measurable, constraints ->
        val h = lerp(open, folded, progress()).roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    }

/** A piece of the chrome at its size and place at a given moment, [bottom] up from the bottom edge. */
private fun Modifier.placed(progress: () -> Float, x: (Float) -> Dp, bottom: (Float) -> Dp, w: (Float) -> Dp, h: (Float) -> Dp): Modifier =
    this.layout { measurable, constraints ->
        val t = progress()
        val pw = w(t).roundToPx().coerceAtLeast(0)
        val ph = h(t).roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(pw, ph))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(x(t).roundToPx(), constraints.maxHeight - bottom(t).roundToPx() - ph)
        }
    }

/**
 * [DockMotion.MINIMIZE], the way iOS 26's tab bar minimizes: nothing swaps.
 * The dock pill itself contracts toward its left end until it's a circle,
 * the open tab gliding to its middle while the others slip under the closing
 * edge, so the dock becomes the open tab's button. The now-playing card drops
 * into the space beside it and Search shrinks to match. Scrolling back up
 * runs it the other way.
 */
@Composable
private fun MinimizeChrome(c: ChromeContent, inline: Boolean, progress: () -> Float, modifier: Modifier) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val width = maxWidth
        val fullDock = width - CHROME_TAB_HEIGHT - SEARCH_GAP
        val openHeight = if (c.mini != null) CHROME_MINI_HEIGHT + CARD_GAP + CHROME_TAB_HEIGHT else CHROME_TAB_HEIGHT
        Box(Modifier.fillMaxWidth().chromeHeight(progress, openHeight, MINI_ROW)) {
            val row = { t: Float -> lerp(CHROME_TAB_HEIGHT, MINI_ROW, t) }
            c.mini?.invoke(
                false,
                Modifier.placed(
                    progress,
                    x = { t -> lerp(0.dp, MINI_ROW + SEARCH_GAP, t) },
                    bottom = { t -> lerp(CHROME_TAB_HEIGHT + CARD_GAP, 0.dp, t) },
                    w = { t -> lerp(width, width - (MINI_ROW + SEARCH_GAP) * 2, t) },
                    h = { t -> lerp(CHROME_MINI_HEIGHT, MINI_ROW, t) },
                ),
            )
            // The dock, contracting to the left into a circle.
            DockPill(
                c, fullDock, inline, progress, anchor = MINI_ROW / 2,
                Modifier.placed(progress, x = { 0.dp }, bottom = { 0.dp }, w = { t -> lerp(fullDock, MINI_ROW, t) }, h = row),
            )
            SearchOrb(
                c.searchSelected,
                Modifier.placed(progress, x = { t -> width - row(t) }, bottom = { 0.dp }, w = row, h = row),
            ) {
                if (!c.searchSelected) haptics.tick()
                c.onSearch()
            }
        }
    }
}

/**
 * [DockMotion.CAPSULE]: everything gathers into one small capsule in the
 * middle. The dock's pill closes in from both ends around the open tab, the
 * now-playing card drops into it as into a pocket, shrinking on the way, and
 * the song comes out as its cover at the far end; with nothing playing,
 * Search slides in to take that end instead. The open tab brings the dock
 * back and the cover opens the player. Scrolling back up, the capsule opens
 * out and the card rises out of it.
 */
@Composable
private fun CapsuleChrome(c: ChromeContent, inline: Boolean, progress: () -> Float, modifier: Modifier) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    // Each is only there while it can be seen, so nothing invisible takes a tap.
    val cardShown by remember { derivedStateOf { progress() < 0.85f } }
    val coverShown by remember { derivedStateOf { progress() > 0.6f } }
    val searchShown by remember { derivedStateOf { progress() < 0.45f } }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val width = maxWidth
        val fullDock = width - CHROME_TAB_HEIGHT - SEARCH_GAP
        val openHeight = if (c.mini != null) CHROME_MINI_HEIGHT + CARD_GAP + CHROME_TAB_HEIGHT else CHROME_TAB_HEIGHT
        val left = (width - CAPSULE_WIDTH) / 2
        // The capsule's far end, where the cover or Search ends up.
        val end = MINI_ROW - CAPSULE_INSET * 2
        val endX = left + CAPSULE_WIDTH - CAPSULE_INSET - end
        val row = { t: Float -> lerp(CHROME_TAB_HEIGHT, MINI_ROW, t) }
        val mini = c.mini
        Box(Modifier.fillMaxWidth().chromeHeight(progress, openHeight, MINI_ROW)) {
            val onSearch = {
                if (!c.searchSelected) haptics.tick()
                c.onSearch()
            }
            if (mini != null) {
                // Cut off at the pill's top edge, so the card is hidden as it goes
                // in rather than showing through the glass.
                if (cardShown) {
                    Box(
                        Modifier
                            .placed(progress, x = { 0.dp }, bottom = row, w = { width }, h = { CHROME_MINI_HEIGHT + CARD_GAP })
                            .clipToBounds(),
                    ) {
                        mini(
                            false,
                            Modifier
                                .fillMaxWidth()
                                .height(CHROME_MINI_HEIGHT)
                                .graphicsLayer {
                                    // Straight down toward the capsule's middle, narrowing to about its width.
                                    val t = progress()
                                    translationY = (CHROME_MINI_HEIGHT / 2 + CARD_GAP + MINI_ROW / 2).toPx() * t
                                    val s = lerp(1f, CAPSULE_WIDTH / width, t.coerceIn(0f, 1f))
                                    scaleX = s
                                    scaleY = s
                                    alpha = 1f - ramp(0.35f, 0.85f, t)
                                },
                        )
                    }
                }
                // Search keeps to the closing edge, shrinking and fading.
                if (searchShown) {
                    val orb = { t: Float -> lerp(CHROME_TAB_HEIGHT, CHROME_TAB_HEIGHT * 0.6f, t) }
                    SearchOrb(
                        c.searchSelected,
                        Modifier
                            .placed(
                                progress,
                                x = { t -> lerp(width - CHROME_TAB_HEIGHT, left + CAPSULE_WIDTH + SEARCH_GAP, t) },
                                bottom = { t -> (row(t) - orb(t)) / 2 },
                                w = orb,
                                h = orb,
                            )
                            .graphicsLayer { alpha = 1f - ramp(0f, 0.45f, progress()) },
                        onSearch,
                    )
                }
            }
            DockPill(
                c, fullDock, inline, progress, anchor = MINI_ROW / 2,
                Modifier.placed(
                    progress,
                    x = { t -> lerp(0.dp, left, t) },
                    bottom = { 0.dp },
                    w = { t -> lerp(fullDock, CAPSULE_WIDTH, t) },
                    h = { t -> lerp(CHROME_TAB_HEIGHT, MINI_ROW, t) },
                ),
            )
            if (mini == null) {
                // Nothing playing: Search itself settles into the capsule's end.
                val orb = { t: Float -> lerp(CHROME_TAB_HEIGHT, end, t) }
                SearchOrb(
                    c.searchSelected,
                    Modifier.placed(progress, x = { t -> lerp(width - CHROME_TAB_HEIGHT, endX, t) }, bottom = { t -> lerp(0.dp, CAPSULE_INSET, t) }, w = orb, h = orb),
                    onSearch,
                )
            } else if (coverShown) {
                mini(
                    true,
                    Modifier
                        .placed(progress, x = { endX }, bottom = { CAPSULE_INSET }, w = { end }, h = { end })
                        .graphicsLayer {
                            val a = ramp(0.6f, 1f, progress())
                            alpha = a
                            val s = lerp(0.6f, 1f, a)
                            scaleX = s
                            scaleY = s
                        },
                )
            }
        }
    }
}

/**
 * [DockMotion.SINK]: the dock sinks back into the page, shrinking a little
 * and blurring as it fades, and the now-playing card settles into the place
 * it leaves, at full width. Coming back, the dock surfaces out of the blur as
 * the card lifts back above it. With nothing playing, a small button with
 * the open tab rises where the dock was, to bring it back.
 */
@Composable
private fun SinkChrome(c: ChromeContent, inline: Boolean, progress: () -> Float, modifier: Modifier) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    val blur = !AppSettings.ui.collectAsState().value.reduceBlur
    // Gone from the layout once it's out of sight, so it can't take a tap.
    val dockShown by remember { derivedStateOf { progress() < SINK_GONE } }
    val buttonShown by remember { derivedStateOf { progress() > 0.5f } }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val width = maxWidth
        val openHeight = if (c.mini != null) CHROME_MINI_HEIGHT + CARD_GAP + CHROME_TAB_HEIGHT else CHROME_TAB_HEIGHT
        val foldedHeight = if (c.mini != null) CHROME_MINI_HEIGHT else MINI_ROW
        Box(Modifier.fillMaxWidth().chromeHeight(progress, openHeight, foldedHeight)) {
            if (dockShown) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .layout { measurable, constraints ->
                            val room = SINK_ROOM.roundToPx()
                            val h = CHROME_TAB_HEIGHT.roundToPx()
                            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + room * 2, h + room * 2))
                            layout(constraints.maxWidth, h) { placeable.place(-room, -room) }
                        }
                        .graphicsLayer {
                            val s = (progress() / SINK_GONE).coerceIn(0f, 1f)
                            alpha = 1f - ramp(0f, 1f, s)
                            val scale = 1f - 0.08f * s
                            scaleX = scale
                            scaleY = scale
                            translationY = 14.dp.toPx() * s
                            val r = 18.dp.toPx() * s
                            renderEffect = if (blur && r >= 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                        }
                        .padding(SINK_ROOM),
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        DockPill(c, width - CHROME_TAB_HEIGHT - SEARCH_GAP, inline, { 0f }, anchor = MINI_ROW / 2, Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(SEARCH_GAP))
                        SearchOrb(c.searchSelected) {
                            if (!c.searchSelected) haptics.tick()
                            c.onSearch()
                        }
                    }
                }
            }
            val mini = c.mini
            if (mini != null) {
                mini(
                    false,
                    Modifier.placed(progress, x = { 0.dp }, bottom = { t -> lerp(CHROME_TAB_HEIGHT + CARD_GAP, 0.dp, t) }, w = { width }, h = { CHROME_MINI_HEIGHT }),
                )
            } else if (buttonShown) {
                val current = c.selected?.let(c.tabs::getOrNull) ?: c.tabs.first()
                TabOrb(
                    current,
                    c.searchSelected,
                    Modifier.align(Alignment.BottomCenter).graphicsLayer {
                        val a = ramp(0.5f, 1f, progress())
                        alpha = a
                        val s = lerp(0.85f, 1f, a)
                        scaleX = s
                        scaleY = s
                    },
                    c.onExpand,
                )
            }
        }
    }
}

/**
 * The dock's glass pill with the tabs in it, at whatever size [modifier]
 * gives it. The tabs stay laid out at the open dock's width [full]; as
 * [fold] goes to 1 the pill's edges pass over them, the names fold away, the
 * other tabs fade, and the row slides so the open tab's middle lands
 * [anchor] in from the pill's start. While [folded], the pill as a whole
 * brings the dock back.
 */
@Composable
private fun DockPill(c: ChromeContent, full: Dp, folded: Boolean, fold: () -> Float, anchor: Dp, modifier: Modifier) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    val ink = dockInk()
    val pill = RoundedCornerShape(percent = 50)
    val slot = (full - DOCK_INSET * 2) / c.tabs.size
    val shown = c.selected?.takeIf { it in c.tabs.indices }
    // The lens rests where it last was while Search is open, faded out.
    var resting by remember { mutableIntStateOf(shown ?: 0) }
    if (shown != null) resting = shown
    Box(
        modifier
            .dockShadow(pill)
            .glass(pill, tint = ink.film ?: MaterialTheme.colorScheme.surfaceContainerHigh)
            .dockHighlight(pill)
            .clip(pill),
    ) {
        val (left, right) = rememberStretchingLens(resting, slot, c.lens)
        val lensAlpha by animateFloatAsState(if (shown != null) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "lensAlpha")
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val inset = DOCK_INSET.roundToPx()
                    val w = (full - DOCK_INSET * 2).roundToPx()
                    val h = (constraints.maxHeight - inset * 2).coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    // From the open tab's own place to [anchor].
                    val slide = (anchor - DOCK_INSET - slot * (resting + 0.5f)).toPx() * fold()
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(inset + slide.roundToInt(), inset) }
                },
        ) {
            Box(
                Modifier
                    .offset { IntOffset(left().roundToPx(), 0) }
                    .layout { measurable, constraints ->
                        val w = (right() - left()).roundToPx().coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .fillMaxHeight()
                    .graphicsLayer { alpha = lensAlpha * (1f - fold()).coerceIn(0f, 1f) }
                    .clip(LENS_SHAPE)
                    .background(Brush.verticalGradient(listOf(ink.lensTop, ink.lensBottom)))
                    .border(1.dp, Brush.verticalGradient(listOf(ink.rimTop, ink.rimBottom)), LENS_SHAPE),
            )
            Row(Modifier.fillMaxSize()) {
                c.tabs.forEachIndexed { i, tab ->
                    DockItem(
                        tab,
                        i == shown,
                        Modifier
                            .width(slot)
                            .fillMaxHeight()
                            // The other tabs fade as the edge reaches them.
                            .graphicsLayer { if (i != resting) alpha = (1f - fold() * 1.6f).coerceIn(0f, 1f) },
                        collapse = fold,
                        roomy = c.tabs.size <= ROOMY_TABS,
                    ) {
                        if (i != shown) haptics.tick()
                        c.onSelect(i)
                    }
                }
            }
        }
        if (folded) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(onClickLabel = "Show navigation", onClick = c.onExpand)
                    .semantics { role = Role.Button },
            )
        }
    }
}

/** One destination: the icon, filled and lifted a little when open, over its name. */
@Composable
internal fun DockItem(tab: ChromeTab, selected: Boolean, modifier: Modifier, collapse: () -> Float = { 0f }, roomy: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.9f
            selected -> 1.06f
            else -> 1f
        },
        spring(dampingRatio = 0.85f, stiffness = 320f),
        label = "dockScale",
    )
    val ink = dockInk()
    val fg by animateColorAsState(
        if (selected) ink.on else ink.off,
        tween(320, easing = FastOutSlowInEasing),
        label = "dockFg",
    )
    Column(
        modifier
            .clip(LENS_SHAPE)
            .clickable(interactionSource = interaction, indication = com.opentune.ui.components.FocusRing(LENS_SHAPE), onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Crossfade(selected, animationSpec = tween(280, easing = FastOutSlowInEasing), label = "dockIcon") { on ->
            Icon(
                if (on) tab.selectedIcon else tab.icon,
                null,
                tint = fg,
                // With fewer tabs each has more room, and uses it.
                modifier = Modifier.size(if (roomy) 29.dp else 25.dp).graphicsLayer {
                    // Folding into a button, the icon drops to the middle as the name goes.
                    val c = collapse()
                    val s = scale * (1f + 0.04f * c)
                    scaleX = s
                    scaleY = s
                    translationY = 9.dp.toPx() * c
                },
            )
        }
        Text(
            com.opentune.ui.theme.tr(tab.label),
            style = (if (roomy) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall).let { if (ink.shade != null) it.copy(shadow = ink.shade) else it },
            // One weight either way, so the name doesn't change width as it's picked.
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp).graphicsLayer {
                val c = collapse()
                alpha = (1f - c * 2.5f).coerceIn(0f, 1f)
                translationY = 6.dp.toPx() * c
            },
        )
    }
}

/** Search on its own: a glass circle that fills with the accent while search is open. */
@Composable
internal fun SearchOrb(selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "orbScale")
    val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, spring(stiffness = 500f), label = "orbFill")
    val ink = dockInk()
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else ink.search, label = "orbFg")
    Box(
        modifier
            .sizeIn(maxWidth = CHROME_TAB_HEIGHT, maxHeight = CHROME_TAB_HEIGHT)
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .dockShadow(CircleShape)
            .glass(CircleShape, tint = ink.film ?: MaterialTheme.colorScheme.surfaceContainerHigh)
            .dockHighlight(CircleShape)
            .padding(DOCK_INSET)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = com.opentune.ui.components.FocusRing(CircleShape), onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Search, "Search", tint = fg, modifier = Modifier.size(26.dp))
    }
}

/**
 * The open tab on its own, for [DockMotion.SINK] with nothing playing: its
 * filled icon in the accent colour inside a glass circle. Tapping it brings
 * the whole dock back.
 */
@Composable
private fun TabOrb(tab: ChromeTab, searchSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "tabOrbScale")
    val ink = dockInk()
    Box(
        modifier
            .size(MINI_ROW)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .dockShadow(CircleShape)
            .glass(CircleShape, tint = ink.film ?: MaterialTheme.colorScheme.surfaceContainerHigh)
            .dockHighlight(CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = com.opentune.ui.components.FocusRing(CircleShape), onClickLabel = "Show navigation", onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (searchSelected) tab.icon else tab.selectedIcon,
            com.opentune.ui.theme.tr(tab.label),
            tint = if (searchSelected) ink.off else ink.on,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** A hairline of light along the top edge, fading down, so the glass reads as lit from above. */
@Composable
internal fun Modifier.dockHighlight(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val light = Color.White.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.22f else 0.6f)
    return this.border(1.dp, Brush.verticalGradient(listOf(light, light.copy(alpha = 0f), light.copy(alpha = 0f))), shape)
}

/**
 * What the dock draws in. Over Liquid Glass, as BitChord does it: white
 * icons and names on glass darkened a little, so they read over any colour
 * coming through, and the open tab on a dark see-through pill. Otherwise the
 * open tab is in the accent, on a soft lens of it.
 */
internal class DockInk(
    val on: Color, val off: Color, val search: Color,
    val lensTop: Color, val lensBottom: Color, val rimTop: Color, val rimBottom: Color,
    val film: Color?, val shade: androidx.compose.ui.graphics.Shadow?,
)

@Composable
internal fun dockInk(): DockInk {
    val scheme = MaterialTheme.colorScheme
    val ui by AppSettings.ui.collectAsState()
    val liquid = LocalBackdrop.current != null && liquidGlassOn() && ui.glassStyle == GlassStyle.LIQUID
    if (!liquid) {
        val a = scheme.primary
        // Over a glass dock with content showing through, the muted inactive tint
        // washes out and the icons vanish. Lift it toward white on a dark app, or
        // a strong ink on a light one, and give every glyph a faint shadow so it
        // reads over whatever colour is behind. A solid dock keeps the scheme tint.
        val overGlass = LocalBackdrop.current != null && liquidGlassOn()
        val dark = scheme.surface.luminance() <= 0.5f
        val off = when {
            !overGlass -> scheme.onSurfaceVariant
            dark -> Color.White.copy(alpha = 0.9f)
            else -> scheme.onSurface.copy(alpha = 0.82f)
        }
        val shade = if (overGlass) androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.3f), blurRadius = 5f) else null
        return DockInk(a, off, scheme.onSurface, a.copy(alpha = 0.24f), a.copy(alpha = 0.14f), a.copy(alpha = 0.45f), a.copy(alpha = 0.08f), null, shade)
    }
    val dark = scheme.surface.luminance() <= 0.5f
    return DockInk(
        on = Color.White, off = Color.White.copy(alpha = 0.92f), search = Color.White,
        lensTop = Color.Black.copy(alpha = 0.36f), lensBottom = Color.Black.copy(alpha = 0.28f),
        rimTop = Color.White.copy(alpha = 0.24f), rimBottom = Color.White.copy(alpha = 0.04f),
        film = Color.Black.copy(alpha = if (dark) 0.14f else 0.28f),
        shade = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 6f),
    )
}

/** A soft drop shadow so the dock floats over the page. */
internal fun Modifier.dockShadow(shape: androidx.compose.ui.graphics.Shape): Modifier =
    shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))

/**
 * The lens's two edges, which travel separately: the edge in the direction
 * of travel leads and the far edge follows, so the lens stretches toward the
 * new tab and settles into it rather than sliding over as one block. Both
 * are critically damped, so it never overshoots.
 */
@Composable
internal fun rememberStretchingLens(index: Int, slot: Dp, style: DockLens = DockLens.STRETCH): Pair<() -> Dp, () -> Dp> {
    val left = remember { Animatable(slot.value * index) }
    val right = remember { Animatable(slot.value * (index + 1)) }
    LaunchedEffect(index, slot) {
        val toLeft = slot.value * index
        val toRight = slot.value * (index + 1)
        val movingRight = toLeft > left.value
        val (lead, follow) = lensSprings(style)
        launch { left.animateTo(toLeft, if (movingRight) follow else lead) }
        launch { right.animateTo(toRight, if (movingRight) lead else follow) }
    }
    return ({ left.value.dp } to { right.value.dp })
}

/** The springs for the lens's leading and trailing edges, for each [DockLens]. */
internal fun lensSprings(style: DockLens): Pair<SpringSpec<Float>, SpringSpec<Float>> = when (style) {
    DockLens.STRETCH -> spring<Float>(dampingRatio = 1f, stiffness = 520f) to spring(dampingRatio = 1f, stiffness = 170f)
    // Underdamped edges: the front overshoots, the back catches up late and both wobble home.
    DockLens.JELLY -> spring<Float>(dampingRatio = 0.5f, stiffness = 480f) to spring(dampingRatio = 0.55f, stiffness = 190f)
    // Both edges on the same spring, so the lens keeps its width.
    DockLens.GLIDE -> spring<Float>(dampingRatio = 0.86f, stiffness = 360f).let { it to it }
}
