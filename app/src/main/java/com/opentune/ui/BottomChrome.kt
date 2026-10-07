package com.opentune.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.opentune.data.settings.DockLens
import com.opentune.data.settings.DockMotion
import com.opentune.ui.components.glass

/** Room the chrome takes when shown, for content padding. */
val CHROME_TAB_HEIGHT = 72.dp
val CHROME_MINI_HEIGHT = 72.dp
private val BUBBLE = 62.dp

/** How long the dock takes to fold away or come back, and its easing (Material's emphasized curve). */
private const val FOLD_MS = 420
private const val FOLD_FADE_IN_MS = 240
private const val FOLD_FADE_DELAY_MS = 60
private const val FOLD_FADE_OUT_MS = 160
private val FOLD_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val DOCK_SHAPE = RoundedCornerShape(30.dp)
private val LENS_SHAPE = RoundedCornerShape(23.dp)
private val DOCK_INSET = 6.dp

/** Timings for the other ways the dock can fold; see [DockMotion]. */
private const val RETRACT_MS = 520
private const val CASCADE_STEP_MS = 40
private const val CASCADE_DROP_MS = 220
private const val CASCADE_RISE_MS = 420
private const val CASCADE_LEAD_MS = 40
private const val DISSOLVE_MS = 420
private const val SQUASH_OUT_MS = 200
private const val POP_OUT_MS = 180
private val DISSOLVE_BLUR = 22.dp

/** Ends a touch past the target and eases back, for tabs landing in [DockMotion.CASCADE]. */
private val BACK_OUT = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)

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

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Follow intentional vertical scrolling, not a fling, an animation,
        // or a sideways swipe across a shelf. At the top, a pull reveals navigation.
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (consumed.y == 0f && available.y > 0f) {
            expand()
            return Offset.Zero
        }
        val dy = consumed.y
        if (!dy.isFinite() || dy == 0f) return Offset.Zero
        if ((accumulated > 0 && dy < 0) || (accumulated < 0 && dy > 0)) accumulated = 0f
        accumulated += dy
        if (accumulated <= -thresholdPx && !inline) {
            inline = true
            accumulated = 0f
        } else if (accumulated >= thresholdPx * 0.5f && inline) {
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
 * are one shared element, so the cover travels between them. [motion] picks
 * how the dock itself goes and comes back; [reduceMotion] swaps all of it
 * for a short crossfade.
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
    motion: DockMotion = DockMotion.FOLD,
    lens: DockLens = DockLens.STRETCH,
    reduceMotion: Boolean = false,
) {
    val style = if (reduceMotion) null else motion
    SharedTransitionLayout(modifier) {
        AnimatedContent(
            inline,
            transitionSpec = { foldTransform(style, tabs.size + 1) },
            contentAlignment = Alignment.BottomEnd,
            label = "chrome",
        ) { folded ->
            val shared = SharedMini(this@SharedTransitionLayout, this, boundsSpec(style))
            val mist = if (style == DockMotion.DISSOLVE) {
                transition.animateFloat({ tween(DISSOLVE_MS, easing = FOLD_EASING) }, label = "mist") { if (it == EnterExitState.Visible) 0f else 1f }
            } else {
                null
            }
            val dissolve = if (mist == null) Modifier else Modifier.graphicsLayer {
                val blur = mist.value * DISSOLVE_BLUR.toPx()
                renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
            }
            // Squash: flattens into a line on the way out and springs back past full height on the way in.
            val squash = if (style == DockMotion.SQUASH) {
                transition.animateFloat(
                    {
                        if (targetState == EnterExitState.Visible) spring(dampingRatio = 0.45f, stiffness = 420f)
                        else tween(SQUASH_OUT_MS, easing = FastOutLinearInEasing)
                    },
                    label = "squash",
                ) { if (it == EnterExitState.Visible) 1f else 0f }
            } else {
                null
            }
            val flatten = if (squash == null) Modifier else Modifier.graphicsLayer {
                val v = squash.value
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleY = (0.06f + 0.94f * v).coerceAtLeast(0.02f)
                // Wider as it flattens, the way a pressed drop spreads.
                scaleX = 1f + 0.08f * (1f - v.coerceIn(0f, 1f))
                alpha = (v * 2.5f).coerceIn(0f, 1f)
            }
            if (folded) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(dissolve).then(flatten), contentAlignment = Alignment.BottomEnd) {
                    if (mini != null) {
                        mini(true, (if (reduceMotion) Modifier else with(shared) { Modifier.sharedMini() }).size(BUBBLE))
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(dissolve).then(flatten), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mini != null) mini(false, (if (reduceMotion) Modifier else with(shared) { Modifier.sharedMini() }).fillMaxWidth().height(CHROME_MINI_HEIGHT))
                    Dock(tabs, selected, onSelect, searchSelected, onSearch, style, lens, this@AnimatedContent)
                }
            }
        }
    }
}

/**
 * How the chrome swaps between the full dock and the corner bubble, for
 * each [DockMotion]. Null [motion] is the reduced-motion version: a quick
 * crossfade. [steps] is how many pieces the dock has (tabs plus Search),
 * which sets how long a cascade takes to finish.
 */
private fun AnimatedContentTransitionScope<Boolean>.foldTransform(motion: DockMotion?, steps: Int): ContentTransform {
    val transform = when (motion) {
        null -> fadeIn(tween(160)) togetherWith fadeOut(tween(120))
        DockMotion.FOLD -> {
            // One unhurried curve for every part: the incoming chrome rises a
            // little and fades in once the outgoing one has mostly faded,
            // while the size change and the cover glide on the same timing.
            val enter = fadeIn(tween(FOLD_FADE_IN_MS, delayMillis = FOLD_FADE_DELAY_MS, easing = LinearOutSlowInEasing)) +
                slideInVertically(tween(FOLD_MS, easing = FOLD_EASING)) { it / 10 } +
                scaleIn(tween(FOLD_MS, easing = FOLD_EASING), initialScale = 0.98f, transformOrigin = TransformOrigin(1f, 1f))
            val exit = fadeOut(tween(FOLD_FADE_OUT_MS, easing = FastOutLinearInEasing)) +
                slideOutVertically(tween(FOLD_MS, easing = FOLD_EASING)) { it / 10 } +
                scaleOut(tween(FOLD_MS, easing = FOLD_EASING), targetScale = 0.98f, transformOrigin = TransformOrigin(1f, 1f))
            enter togetherWith exit
        }
        // Down past the edge without a bounce; back up on a spring that
        // carries a little past its place and settles.
        DockMotion.GLIDE ->
            (slideInVertically(spring(dampingRatio = 0.72f, stiffness = 320f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it } + fadeIn(tween(160))) togetherWith
                (slideOutVertically(spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)) { it * 3 / 2 } + fadeOut(tween(220, delayMillis = 140)))
        // The dock clips itself (see Dock); the swap only has to wait for it.
        DockMotion.RETRACT -> fadeIn(tween(140)) togetherWith fadeOut(tween(180, delayMillis = RETRACT_MS - 180))
        // The tabs move themselves (see Dock); the glass sinks after them,
        // starting once half have dropped so it's never left standing empty.
        DockMotion.CASCADE -> {
            val glassDelay = steps * CASCADE_STEP_MS / 2 + 40
            fadeIn(tween(220)) togetherWith
                (fadeOut(tween(CASCADE_DROP_MS + 60, glassDelay, FastOutLinearInEasing)) + slideOutVertically(tween(CASCADE_DROP_MS + 60, glassDelay, FastOutLinearInEasing)) { it / 6 })
        }
        // Blurred in BottomChrome; here it fades and grows a touch as it goes.
        DockMotion.DISSOLVE ->
            (fadeIn(tween(DISSOLVE_MS, easing = LinearOutSlowInEasing)) + scaleIn(tween(DISSOLVE_MS, easing = FOLD_EASING), initialScale = 1.05f)) togetherWith
                (fadeOut(tween(DISSOLVE_MS * 3 / 4, easing = FastOutLinearInEasing)) + scaleOut(tween(DISSOLVE_MS, easing = FOLD_EASING), targetScale = 1.06f))
        // Flattened in BottomChrome; the swap waits until the line has all but gone.
        DockMotion.SQUASH -> fadeIn(tween(1)) togetherWith fadeOut(tween(90, delayMillis = SQUASH_OUT_MS - 40))
        // Into its middle and back out past full size, on a spring.
        DockMotion.POP ->
            (scaleIn(spring(dampingRatio = 0.5f, stiffness = 380f), initialScale = 0.55f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn(tween(140))) togetherWith
                (scaleOut(tween(POP_OUT_MS, easing = FastOutLinearInEasing), targetScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut(tween(POP_OUT_MS)))
    }
    val size: FiniteAnimationSpec<IntSize> = when (motion) {
        null -> tween(200, easing = FOLD_EASING)
        DockMotion.GLIDE -> spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntSize.VisibilityThreshold)
        DockMotion.RETRACT -> tween(RETRACT_MS, easing = FastOutSlowInEasing)
        DockMotion.POP, DockMotion.SQUASH -> spring(dampingRatio = 0.8f, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold)
        else -> tween(FOLD_MS, easing = FOLD_EASING)
    }
    return transform.using(if (motion == null) null else SizeTransform(clip = false) { _, _ -> size })
}

/** How the now-playing card travels between its full size and the bubble. */
private fun boundsSpec(motion: DockMotion?): FiniteAnimationSpec<Rect> = when (motion) {
    null -> tween(200, easing = FOLD_EASING)
    DockMotion.GLIDE -> spring(dampingRatio = 0.82f, stiffness = 300f, visibilityThreshold = Rect.VisibilityThreshold)
    DockMotion.RETRACT -> tween(RETRACT_MS, easing = FastOutSlowInEasing)
    DockMotion.DISSOLVE -> tween(DISSOLVE_MS + 80, easing = FOLD_EASING)
    DockMotion.POP, DockMotion.SQUASH -> spring(dampingRatio = 0.72f, stiffness = 360f, visibilityThreshold = Rect.VisibilityThreshold)
    else -> tween(FOLD_MS, easing = FOLD_EASING)
}

@OptIn(ExperimentalSharedTransitionApi::class)
private class SharedMini(
    private val layout: SharedTransitionScope,
    private val scope: AnimatedVisibilityScope,
    private val spec: FiniteAnimationSpec<Rect>,
) {
    @Composable
    fun Modifier.sharedMini(): Modifier = with(layout) {
        this@sharedMini.sharedElement(
            rememberSharedContentState("mini"),
            scope,
            boundsTransform = { _, _ -> spec },
        )
    }
}

/**
 * For [DockMotion.CASCADE]: piece [i] of the dock drops into the glass a
 * beat after the one before it, and on the way back rises a beat later,
 * landing just past its place and easing back.
 */
private fun cascade(i: Int): Pair<EnterTransition, ExitTransition> {
    val inDelay = CASCADE_LEAD_MS + i * CASCADE_STEP_MS
    val outDelay = i * CASCADE_STEP_MS
    val enter = slideInVertically(tween(CASCADE_RISE_MS, inDelay, BACK_OUT)) { it } + fadeIn(tween(180, inDelay))
    val exit = slideOutVertically(tween(CASCADE_DROP_MS, outDelay, FastOutLinearInEasing)) { it } + fadeOut(tween(CASCADE_DROP_MS, outDelay))
    return enter to exit
}

/**
 * For [DockMotion.RETRACT]: a pill that keeps its end on the Search side
 * (the right, or the left in right-to-left languages) and its round ends
 * while the far edge slides over, down to a circle at [progress] 0, so the
 * dock pulls into the Search button.
 */
private class RetractShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val width = size.height + (size.width - size.height) * progress.coerceIn(0f, 1f)
        val radius = CornerRadius(size.height / 2f)
        val left = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width - width
        return Outline.Rounded(RoundRect(left, 0f, left + width, size.height, radius))
    }
}

/**
 * The dock: a floating glass pill holding the destinations, each an icon
 * over its name, with a soft lens of the accent colour that slides to the
 * open one. Search shares the same rail and the same travelling highlight.
 */
@Composable
private fun Dock(
    tabs: List<ChromeTab>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    motion: DockMotion?,
    lens: DockLens,
    scope: AnimatedVisibilityScope,
) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    val reveal: State<Float>? = if (motion == DockMotion.RETRACT) {
        // Even in and out, so the edge is seen travelling rather than snapping most of the way at once.
        scope.transition.animateFloat({ tween(RETRACT_MS, easing = FastOutSlowInEasing) }, label = "retract") { if (it == EnterExitState.Visible) 1f else 0f }
    } else {
        null
    }
    // Piece i of the dock in a cascade; nothing for the other motions.
    val wave: (Int) -> Modifier = { i ->
        if (motion != DockMotion.CASCADE) Modifier else with(scope) {
            val (enter, exit) = cascade(i)
            Modifier.animateEnterExit(enter, exit, label = "wave$i")
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(CHROME_TAB_HEIGHT)
            .then(
                if (reveal == null) Modifier else Modifier.graphicsLayer {
                    val r = reveal.value
                    clip = r < 1f
                    if (r < 1f) shape = RetractShape(r)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().dockShadow(DOCK_SHAPE).glass(DOCK_SHAPE).dockHighlight(DOCK_SHAPE).padding(DOCK_INSET),
        ) {
            val destinations = tabs + ChromeTab("Search", Icons.Rounded.Search)
            val slot = maxWidth / destinations.size
            val shown = if (searchSelected) tabs.size else selected?.takeIf { it in tabs.indices }
            val resting = shown ?: 0
            val (left, right) = rememberStretchingLens(resting, slot, lens, reduceMotion = motion == null)
            val trackWidth = maxWidth.value
            fun bounds() = dockLensBounds(left().value, right().value, slot.value, trackWidth)
            val lensAlpha = animateFloatAsState(if (shown != null) 1f else 0f, tween(if (motion == null) 0 else 180), label = "lensAlpha")
            val accent = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .then(wave(resting))
                    .offset { IntOffset(bounds().start.dp.roundToPx(), 0) }
                    .layout { measurable, constraints ->
                        val w = bounds().width.dp.roundToPx().coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .fillMaxHeight()
                    .testTag("dockIndicator")
                    .graphicsLayer {
                        alpha = lensAlpha.value
                        // A touch thinner while it's stretched, like a drop pulled sideways.
                        val stretch = (bounds().width / slot.value - 1f).coerceIn(0f, 1f)
                        scaleY = 1f - 0.06f * stretch
                    }
                    .clip(LENS_SHAPE)
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.12f))))
                    .border(0.75.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.08f))), LENS_SHAPE),
            )
            // Clipped to the glass, so tabs in a cascade sink into it rather than below it.
            Row(Modifier.fillMaxSize().clip(LENS_SHAPE).selectableGroup()) {
                destinations.forEachIndexed { i, tab ->
                    DockItem(tab, i == shown, wave(i).width(slot).fillMaxHeight(), reduceMotion = motion == null) {
                        if (i != shown) haptics.tick()
                        if (i == tabs.size) onSearch() else onSelect(i)
                    }
                }
            }
        }
    }
}

/** One destination: the icon, filled and lifted a little when open, over its name. */
@Composable
private fun DockItem(tab: ChromeTab, selected: Boolean, modifier: Modifier, reduceMotion: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        when {
            reduceMotion -> 1f
            pressed -> 0.88f
            selected -> 1.08f
            else -> 1f
        },
        if (reduceMotion) snap() else spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "dockScale",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(if (reduceMotion) 0 else 220, easing = FastOutSlowInEasing),
        label = "dockFg",
    )
    val lift = animateDpAsState(if (selected && !reduceMotion) (-2).dp else 0.dp, if (reduceMotion) snap() else spring(dampingRatio = 0.85f, stiffness = 500f), label = "dockLift")
    Column(
        modifier
            .clip(LENS_SHAPE)
            .testTag("dock:${tab.label}")
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Crossfade(selected, animationSpec = tween(if (reduceMotion) 0 else 160), label = "dockIcon") { on ->
            Icon(
                if (on) tab.selectedIcon else tab.icon,
                null,
                tint = fg,
                modifier = Modifier.size(23.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; translationY = lift.value.toPx() },
            )
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            // One weight either way, so the name doesn't change width as it's picked.
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
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

/**
 * The lens's two edges, which travel separately: the edge in the direction
 * of travel leads and the far edge follows, so the lens stretches toward the
 * new tab and settles into it rather than sliding over as one block. Both
 * are critically damped, so it never overshoots.
 */
@Composable
private fun rememberStretchingLens(index: Int, slot: Dp, style: DockLens = DockLens.STRETCH, reduceMotion: Boolean = false): Pair<() -> Dp, () -> Dp> {
    if (reduceMotion) return ({ slot * index } to { slot * (index + 1) })
    val left = remember { Animatable(slot.value * index) }
    val right = remember { Animatable(slot.value * (index + 1)) }
    var previousSlot by remember { mutableStateOf(slot) }
    LaunchedEffect(index, slot, style) {
        val toLeft = slot.value * index
        val toRight = slot.value * (index + 1)
        if (previousSlot != slot) {
            left.snapTo(toLeft)
            right.snapTo(toRight)
            previousSlot = slot
            return@LaunchedEffect
        }
        val movingRight = toLeft > left.value
        val (lead, follow) = lensSprings(style)
        launch { left.animateTo(toLeft, if (movingRight) follow else lead) }
        launch { right.animateTo(toRight, if (movingRight) lead else follow) }
    }
    return ({ left.value.dp } to { right.value.dp })
}

internal data class DockLensBounds(val start: Float, val width: Float)

/** Keep rapid reversals and spring overshoot inside the rail, without collapsing the lens. */
internal fun dockLensBounds(left: Float, right: Float, slot: Float, track: Float): DockLensBounds {
    if (slot <= 0f || track <= 0f) return DockLensBounds(0f, 0f)
    val width = (right - left).coerceIn((slot * 0.82f).coerceAtMost(track), (slot * 1.8f).coerceAtMost(track))
    return DockLensBounds(((left + right - width) / 2f).coerceIn(0f, track - width), width)
}

/** The springs for the lens's leading and trailing edges, for each [DockLens]. */
internal fun lensSprings(style: DockLens): Pair<SpringSpec<Float>, SpringSpec<Float>> = when (style) {
    DockLens.STRETCH -> spring<Float>(dampingRatio = 0.95f, stiffness = 650f) to spring(dampingRatio = 0.95f, stiffness = 280f)
    // Underdamped edges: the front overshoots, the back catches up late and both wobble home.
    DockLens.JELLY -> spring<Float>(dampingRatio = 0.5f, stiffness = 480f) to spring(dampingRatio = 0.55f, stiffness = 190f)
    // Both edges on the same spring, so the lens keeps its width.
    DockLens.GLIDE -> spring<Float>(dampingRatio = 0.86f, stiffness = 360f).let { it to it }
}
