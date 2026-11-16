package com.opentune.ui.components

import kotlinx.coroutines.flow.first
import android.os.Build
import android.view.WindowManager
import kotlinx.coroutines.Job
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import com.opentune.ui.theme.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.SheetStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lets what's inside a [FloatingCard] close it with its exit motion. */
class FloatingCardScope internal constructor(private val closeWith: (after: (() -> Unit)?) -> Unit) {
    /** Plays the card out, then calls [after], or the card's own dismiss without one. */
    fun close(after: (() -> Unit)? = null) = closeWith(after)
}

val LocalFloatingCard = compositionLocalOf<FloatingCardScope?> { null }

/**
 * The app's one sheet, and every popup in the app is built on it: full width,
 * flush to the bottom of the screen, rounded only at the top, rising over
 * the page. Its fill is the reader's choice (see [SheetStyle]): a frosted
 * blur of the screen behind it, or the app's own theme, solid, wearing
 * whatever theme the page under it wears. It has a grab handle, an optional
 * icon and title, and a round close button. Dragging the top of the sheet
 * down, tapping outside or pressing back plays it out the way it came.
 *
 * [content] is laid out in a column that keeps to the screen, so a list
 * inside can scroll. [actions] sit in a row along the bottom.
 */
@Composable
fun FloatingCard(
    onDismiss: () -> Unit,
    title: String? = null,
    icon: ImageVector? = null,
    subtitle: String? = null,
    dismissible: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp),
    actions: (@Composable RowScope.() -> Unit)? = null,
    /** Stays put above [content] and, like the handle, pulls the card up and down. */
    header: (@Composable ColumnScope.() -> Unit)? = null,
    /** Opens at half height; dragging the top, or scrolling the content, takes it up to nearly full. */
    expandable: Boolean = false,
    /** Opens already at full content height (up to 90% of the screen), so nothing needs scrolling to reach. */
    startExpanded: Boolean = false,
    /** How much of the screen an [expandable] card takes pulled all the way up: 1 takes over the page. */
    fullFraction: Float = 0.9f,
    /** How much of the screen an [expandable] card opens at, unless [startExpanded]. */
    openFraction: Float = 0.5f,
    /** False for asynchronously loaded lists: loading and empty states must not cap expansion. */
    fitContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui by AppSettings.ui.collectAsState()
    val still = ui.reduceAnimation
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var open by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    // Written straight from the finger, so a release spring can't be cancelled
    // by a drag step that arrives after it starts.
    var drag by remember { mutableFloatStateOf(0f) }
    // How far an expandable card has been pulled up past its half height, in px.
    var extra by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val card = remember {
        FloatingCardScope { after ->
            if (!closing) {
                closing = true
                scope.launch {
                    open = false
                    if (!still) delay(EXIT_MS)
                    (after ?: dismiss)()
                    // Still here a moment later: whoever owns the popup kept it
                    // open (a download running, say), so it comes back.
                    delay(KEPT_MS)
                    drag = 0f
                    closing = false
                    open = true
                }
            }
        }
    }

    // Where the card was opened from: in a sheet, the frost behind keeps the sheet in sight.
    val opener = LocalView.current
    Dialog(
        onDismissRequest = { if (dismissible) card.close() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
    ) {
        // The screen behind frosted by the app itself; the window's blur only where that can't be done.
        // The theme look never frosts: the sheet is the app's own surface, solid,
        // so it starts where a failed frost ends up and skips the wait for one.
        val themed = ui.sheetStyle == SheetStyle.THEME
        var frost by remember { mutableStateOf(if (ui.reduceBlur || themed) Frost.FAILED else Frost.PENDING) }
        val windowFrost = FrostBehind(enabled = !ui.reduceBlur && !themed, standIn = frost != Frost.FAILED)
        val frosted = windowFrost || frost == Frost.READY
        val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
        val shown by animateFloatAsState(
            if (open) 1f else 0f,
            // No bounce: a card that overshoots its place and comes back reads as a shudder.
            if (still) snap() else if (open) spring(dampingRatio = 1f, stiffness = 340f) else tween(EXIT_MS.toInt()),
            label = "card",
        )
        if (frost != Frost.FAILED) {
            val got = ScreenFrost(shown = { shown }, over = opener)
            LaunchedEffect(got) { frost = got }
        }
        // The card rises once the blur behind it is ready (or has failed), and the first frame
        // is laid out: rising while the blur was still being made, its glass changed from nearly
        // solid to clear partway up, and the first frames, busy building the menu, were dropped.
        // An expandable card also waits to know its own height: measured on its first
        // frames, it went from tall to its real size while rising, a jump halfway up.
        var measured by remember { mutableStateOf(!expandable) }
        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            kotlinx.coroutines.withTimeoutOrNull(FROST_WAIT_MS) {
                androidx.compose.runtime.snapshotFlow { frost != Frost.PENDING && measured }.first { it }
            }
            open = true
        }
        // A dialog's content sits outside the app's Surface, so it needs its own text colour.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, LocalFloatingCard provides card) {
            BoxWithConstraints(
                Modifier.fillMaxSize().pointerInput(dismissible) { detectTapGestures { if (dismissible) card.close() } },
                contentAlignment = Alignment.BottomCenter,
            ) {
                val maxCard = maxHeight
                val density = LocalDensity.current
                // The card's own height with everything shown, measured as it lays out;
                // an expandable card never grows past it, so a short menu has no empty foot.
                var naturalPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
                val fullPx = minOf(with(density) { (maxCard * fullFraction).toPx() }, if (fitContent) naturalPx else Float.MAX_VALUE)
                // Start at full height when asked, so the whole menu shows at once; otherwise half.
                val halfPx = if (startExpanded) fullPx else minOf(with(density) { (maxCard * openFraction).toPx() }, fullPx)
                val range = (fullPx - halfPx).coerceAtLeast(0f)
                // Full-screen treatment only for a card that can actually reach the full
                // height: one capped by its content keeps its card shape at its top.
                val fullscreen = expandable && fullFraction >= 1f && fullPx >= with(density) { maxCard.toPx() } - 1f
                // How far the card's square-off has got: 1 at rest, 0 pulled up to the whole
                // page. Read only from layout and draw code (the insets below, the layer and
                // the rim), never while composing: a pull writes [extra] on every move, and a
                // read here would rebuild the whole card — handle, title, content and all —
                // on every one of them, which is what made dragging a sheet crawl.
                val edgeNow = {
                    if (!fullscreen) 1f
                    else 1f - if (range > 0f) (extra / range).coerceIn(0f, 1f) else 1f
                }
                // The same square-off, coarsened for the layout insets: the insets set the
                // card's width, and a width that creeps a pixel at a time makes the whole
                // content measure itself again on every step of the pull — the dearest
                // thing a content can be asked to do, and a sheet full of chip rows pays
                // it worst. A dozen steps across the pull is more than the eye can catch;
                // the corners and the rim are drawn, not laid out, and keep the smooth one.
                val edgeLayout = {
                    val e = edgeNow()
                    (e * 12f).roundToInt() / 12f
                }
                // The solid card's rim and shadow colours, taken here for the draw code below.
                val rim = MaterialTheme.colorScheme.onSurface
                val scrim = MaterialTheme.colorScheme.scrim
                fun settle(velocity: Float) {
                    settling?.cancel()
                    settling = scope.launch {
                        val target = when {
                            !expandable -> 0f
                            velocity < -FLING -> range
                            velocity > FLING -> 0f
                            else -> if (extra > range / 2) range else 0f
                        }
                        launch { animate(drag, 0f, velocity, spring(dampingRatio = 0.75f, stiffness = 500f)) { v, _ -> drag = v } }
                        if (expandable) animate(extra, target, -velocity, spring(dampingRatio = 0.85f, stiffness = 420f)) { v, _ -> extra = v.coerceIn(0f, range) }
                    }
                }
                /** Moves the card by [d] px (down positive): an expandable one grows or shrinks first, then the rest pulls it down. */
                fun pull(d: Float) {
                    var left = d
                    if (expandable) {
                        if (left < 0 && drag <= 0f) {
                            val take = minOf(-left, range - extra)
                            extra += take
                            left += take
                        } else if (left > 0 && extra > 0f && drag <= 0f) {
                            val take = minOf(left, extra)
                            extra -= take
                            left -= take
                        }
                    }
                    if (left != 0f) drag = (drag + if (drag + left < 0) left * 0.2f else left).coerceAtLeast(-24f)
                }
                // Scrolling the content up grows the card first; pulling it down
                // at the top of the list shrinks it back to half.
                val grow = remember(expandable, range) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (!expandable || source != NestedScrollSource.UserInput || available.y >= 0 || extra >= range) return Offset.Zero
                            settling?.cancel()
                            val take = minOf(-available.y, range - extra)
                            extra += take
                            return Offset(0f, -take)
                        }

                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (!expandable || source != NestedScrollSource.UserInput || available.y <= 0) return Offset.Zero
                            settling?.cancel()
                            if (extra <= 0f) {
                                // Back at half with the list at its top: the pull carries the card down, to close it.
                                if (!dismissible) return Offset.Zero
                                drag += available.y
                                return Offset(0f, available.y)
                            }
                            val take = minOf(available.y, extra)
                            extra -= take
                            return Offset(0f, take)
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (!expandable) return Velocity.Zero
                            // Carried down from the list: far enough or fast enough closes it, else back up.
                            if (drag > 0f) {
                                if (drag > with(density) { 110.dp.toPx() } || available.y > 1400f) card.close() else settle(available.y)
                                return available
                            }
                            if (extra <= 0f || extra >= range) return Velocity.Zero
                            settle(available.y)
                            return available
                        }

                        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                            // A slow pull let go of: settles the card back or closes it.
                            if (expandable && drag > 0f) {
                                if (drag > with(density) { 110.dp.toPx() }) card.close() else settle(0f)
                            }
                            return Velocity.Zero
                        }
                    }
                }
                // Pulled up to the whole page, the card runs under the status and navigation bars, edge
                // to edge: the room kept for them outside it goes as it grows, and comes back inside
                // it instead, so nothing of it sits under the clock. Kept outside, it left a hard band
                // of the page above and below a card that was meant to be the page.
                val barsTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val barsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Column(
                    Modifier
                        .insetBy {
                            val e = edgeLayout()
                            val bars = if (fullscreen) e else 1f
                            // Full width, flush to the bottom edge, always: the only room
                            // kept is above the sheet, and only while it isn't the whole
                            // page. The navigation bar's room moves inside the sheet
                            // (the inner insets below), so its fill runs to the very
                            // bottom of the screen while its content never sits under
                            // the bar.
                            InsetsPx(0, (barsTop * bars).roundToPx() + (12.dp * e).roundToPx(), 0, 0)
                        }
                        .imePadding()
                        .fillMaxWidth()
                        .then(
                            if (expandable) {
                                Modifier.layout { measurable, constraints ->
                                    // The content's own height, learned two ways, each covering
                                    // the other's blind spot. The intrinsic size is the honest
                                    // sum of the content, but on some contents the query itself
                                    // throws (it tries to build a Constraints with an
                                    // unrepresentable height) — and a sheet whose body is laid
                                    // out to fill would defeat it the other way. So when the
                                    // query fails, the content is laid out for real inside the
                                    // space actually on offer and its height read off that: a
                                    // short content reports its own height, one that fills the
                                    // whole space reports the space, which is a full sheet
                                    // anyway. Both run at rest and whenever a pull reaches the
                                    // cap they set, never on every pull, so dragging a sheet
                                    // costs exactly what it cost before. A content that can't
                                    // be measured whole (a lazy list) fails both, is caught,
                                    // and just takes the height asked for, as before.
                                    val want = (halfPx + extra).roundToInt()
                                    val atRest = extra <= 0f || extra >= range
                                    val pressing = fitContent && want.toFloat() >= naturalPx
                                    val natural = if (fitContent && (atRest || pressing)) {
                                        runCatching { measurable.maxIntrinsicHeight(constraints.maxWidth) }.getOrNull()
                                            ?: runCatching {
                                                val probe = measurable.measure(constraints.copy(minHeight = 0))
                                                probe.height.takeIf { it > 0 && it < constraints.maxHeight }
                                            }.getOrNull()
                                    } else null
                                    if (natural != null && natural.toFloat() != naturalPx) naturalPx = natural.toFloat()
                                    if (atRest || pressing || !fitContent) measured = true
                                    val h = minOf(want, natural ?: want).coerceIn(constraints.minHeight, constraints.maxHeight)
                                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                }
                            } else {
                                Modifier.heightIn(max = maxCard * 0.9f)
                            },
                        )
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            translationY = (1f - shown) * size.height * 0.5f + drag
                            alpha = shown.coerceIn(0f, 1f)
                            // The sheet's top corners, squared off as it takes the whole
                            // page, are the layer's own shape: set here in the draw phase,
                            // a pull changes them without recomposing the sheet to do it.
                            // The bottom corners are the screen's own: the sheet runs to
                            // its edges, so they are never rounded.
                            shape = RoundedCornerShape(topStart = 32.dp * edgeNow(), topEnd = 32.dp * edgeNow())
                            clip = true
                            // No drop shadow under glass, whichever blur is behind it: Android
                            // draws the shadow under the whole card, a little lower and inset,
                            // and through the glass its edges read as a box inside the card.
                            // Only the solid card, with no blur at all, gets one — cast by the
                            // layer, with the same shape.
                            if (frost == Frost.FAILED && !frosted) {
                                shadowElevation = 32.dp.toPx()
                                ambientShadowColor = scrim
                                spotShadowColor = scrim
                            }
                        }
                        .then(
                            if (frosted) {
                                // Glass: the blurred screen behind the window shows through
                                // a film of the surface, lit a little along the top.
                                Modifier
                                    // Over the app's own frost the blur is certain, so the film can be
                                    // thin glass. Over the window's it stays near solid: some phones say
                                    // they blur behind a window and then don't, and the page would read through.
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                            alpha = if (frost == Frost.READY) (if (dark) 0.62f else 0.72f) else if (dark) 0.88f else 0.9f,
                                        ),
                                    )
                                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.07f else 0.18f), 0.35f to Color.White.copy(alpha = 0f)))
                            } else {
                                Modifier
                                    // Solid, so its shadow never shows through.
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            },
                        )
                        // The card's rim, drawn over the film: like the corners, its fade as
                        // the card squares off reads the pull in the draw phase.
                        .drawWithContent {
                            drawContent()
                            val e = edgeNow()
                            if (e > 0f) {
                                val stroke = 1.dp.toPx()
                                val outline = RoundedCornerShape(topStart = 32.dp * e, topEnd = 32.dp * e).createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
                                drawContext.canvas.save()
                                drawContext.canvas.translate(stroke / 2f, stroke / 2f)
                                if (frosted) {
                                    drawOutline(outline, Brush.verticalGradient(listOf(Color.White.copy(alpha = (if (dark) 0.22f else 0.6f) * e), Color.White.copy(alpha = 0.04f * e))), style = Stroke(stroke))
                                } else {
                                    drawOutline(outline, rim.copy(alpha = 0.08f * e), style = Stroke(stroke))
                                }
                                drawContext.canvas.restore()
                            }
                        }
                        // Taps on the card stay on the card.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .insetBy {
                            val insideF = if (fullscreen) 1f - edgeLayout() else 0f
                            // The bottom room is the navigation bar's at every height:
                            // the sheet's fill runs under it, its content never does.
                            InsetsPx(10.dp.roundToPx(), 10.dp.roundToPx() + (barsTop * insideF).roundToPx(), 10.dp.roundToPx(), 14.dp.roundToPx() + barsBottom.roundToPx())
                        },
                ) {
                    // The handle and title pull the card down; content below keeps its own gestures.
                    Column(
                        Modifier.fillMaxWidth().draggable(
                            orientation = Orientation.Vertical,
                            enabled = dismissible,
                            state = rememberDraggableState { d -> pull(d) },
                            onDragStarted = { settling?.cancel() },
                            onDragStopped = { v ->
                                // Down hard from half height, or pulled well past it, closes; an
                                // expanded card flung down only drops back to half.
                                if (drag > with(density) { 110.dp.toPx() } || (v > 1400f && extra <= 1f)) card.close()
                                else settle(v)
                            },
                        ),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(24.dp)
                                // A tap on the bar expands or collapses the sheet, with no ripple across the strip.
                                .then(if (expandable) Modifier.clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Expand or collapse sheet",
                                    role = Role.Button,
                                ) { settle(if (extra < range / 2) -FLING - 1 else FLING + 1) } else Modifier)
                                .semantics { contentDescription = "Sheet drag handle" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)))
                        }
                        if (title != null) {
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (icon != null) {
                                    Box(
                                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
                                    Spacer(Modifier.width(14.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (subtitle != null) {
                                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (dismissible) {
                                    Box(
                                        Modifier.size(40.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                            .clickable(onClickLabel = "Close", role = Role.Button) { card.close() },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Rounded.Close, "Close", Modifier.size(20.dp)) }
                                }
                            }
                        } else {
                            Spacer(Modifier.size(4.dp))
                        }
                        if (header != null) header()
                    }
                    // The body rises in just behind the card, on the card's own clock, so the
                    // two never pull against each other. A content-capped card's body is not
                    // force-filled: its height never passes the content's own anyway, and
                    // leaving the fill off is what lets the cap probe in the height block
                    // above lay the body out unbounded and hear its true height — a filled
                    // body answers "as tall as you'll give me" and the cap never engages.
                    Column(
                        Modifier.weight(1f, fill = expandable && !fitContent).fillMaxWidth().nestedScroll(grow).padding(contentPadding).graphicsLayer {
                            val rise = ((shown - 0.2f) / 0.8f).coerceIn(0f, 1f)
                            alpha = rise
                            translationY = (1f - rise) * 16.dp.toPx()
                        },
                        content = content,
                    )
                    if (actions != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    }
                }
            }
        }
    }
}

/**
 * [FloatingCard] with AlertDialog's slots, so a dialog moves over by
 * changing its name. The text slot reads as body text; the buttons take
 * equal shares of a row, the confirm one filled.
 */
@Composable
fun FloatingDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    FloatingCard(
        onDismiss = onDismissRequest,
        dismissible = properties.dismissOnClickOutside || properties.dismissOnBackPress,
        actions = {
            if (dismissButton != null) {
                Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalSheetTone provides SheetTone.Neutral) { dismissButton() } }
            }
            Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalSheetTone provides SheetTone.Primary) { confirmButton() } }
        },
    ) {
        Column(modifier.padding(horizontal = 6.dp)) {
            if (icon != null || title != null) {
                Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { icon() } }
                        Spacer(Modifier.width(14.dp))
                    }
                    if (title != null) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)) { title() }
                    }
                }
            }
            if (text != null) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodyLarge) { text() }
                }
            }
        }
    }
}

enum class SheetTone { Primary, Neutral, Danger }

/** Which look a [SheetButton] takes when it isn't told: set by the slot it sits in. */
val LocalSheetTone = compositionLocalOf { SheetTone.Neutral }

/**
 * A pill button for popups. Filled in the accent for the main action,
 * a soft fill for the rest, red for [SheetTone.Danger]. With [closes] it
 * plays the popup out first and then runs [onClick]; with [loading] it
 * shows a spinner by its label.
 */
@Composable
fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: SheetTone = LocalSheetTone.current,
    closes: Boolean = false,
    icon: ImageVector? = null,
    loading: Boolean = false,
) {
    val haptics = rememberHaptics()
    val card = LocalFloatingCard.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    val scheme = MaterialTheme.colorScheme
    val (fill, ink) = when (tone) {
        SheetTone.Primary -> scheme.primary to scheme.onPrimary
        SheetTone.Danger -> scheme.error to scheme.onError
        SheetTone.Neutral -> scheme.onSurface.copy(alpha = 0.08f) to scheme.onSurface
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer {
                scaleX = press
                scaleY = press
                alpha = if (enabled || loading) 1f else 0.4f
            }
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = FocusRing(CircleShape), enabled = enabled, role = Role.Button) {
                haptics.tick()
                if (closes && card != null) card.close(onClick) else onClick()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Frosts what's behind the dialog's window where the phone allows it; dims it
 * either way. Returns whether the blur is on right now: the system can turn
 * cross-window blur off (battery saver, some animations off) and back on.
 */
@Composable
internal fun FrostBehind(
    enabled: Boolean,
    /** True while [ScreenFrost] frosts the screen itself: the window is left alone. */
    standIn: Boolean = false,
): Boolean {
    val view = LocalView.current
    val density = LocalDensity.current
    var blurring by remember { mutableStateOf(false) }
    DisposableEffect(view, enabled, standIn) {
        val window = (view.parent as? DialogWindowProvider)?.window?.takeIf { !standIn }
        var listener: java.util.function.Consumer<Boolean>? = null
        if (standIn) blurring = false
        if (window != null) {
            window.setDimAmount(if (enabled) 0.35f else 0.55f)
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val radius = with(density) { 36.dp.roundToPx() }
                val apply = java.util.function.Consumer<Boolean> { on ->
                    blurring = on
                    // Lighter dim over a blur, so the colours behind still come through the card.
                    window.setDimAmount(if (on) 0.22f else 0.35f)
                    if (on) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        window.attributes = window.attributes.apply { blurBehindRadius = radius }
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    }
                }
                apply.accept(window.windowManager.isCrossWindowBlurEnabled)
                window.windowManager.addCrossWindowBlurEnabledListener(apply)
                listener = apply
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listener?.let { window?.windowManager?.removeCrossWindowBlurEnabledListener(it) }
        }
    }
    return blurring
}

/** Padding measured in the layout phase: what it returns may read the card's pull, so a drag re-lays the card out without recomposing it. */
private class InsetsPx(val left: Int, val top: Int, val right: Int, val bottom: Int)

private fun Modifier.insetBy(insets: Density.() -> InsetsPx): Modifier = layout { measurable, constraints ->
    val ins = insets()
    val maxW = (constraints.maxWidth - ins.left - ins.right).coerceAtLeast(0)
    val maxH = (constraints.maxHeight - ins.top - ins.bottom).coerceAtLeast(0)
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth.coerceAtMost(maxW),
            maxWidth = maxW,
            minHeight = constraints.minHeight.coerceAtMost(maxH),
            maxHeight = maxH,
        ),
    )
    layout(placeable.width + ins.left + ins.right, placeable.height + ins.top + ins.bottom) {
        placeable.place(ins.left, ins.top)
    }
}

private const val EXIT_MS = 200L
private const val KEPT_MS = 120L
/** The longest a card waits for the blur behind it before rising without it. */
private const val FROST_WAIT_MS = 180L
/** A release faster than this, in px/s, decides an expandable card's height on its own. */
private const val FLING = 800f
