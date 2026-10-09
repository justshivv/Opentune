package com.opentune.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.Job
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
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
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lets what's inside a [FloatingCard] close it with its exit motion. */
class FloatingCardScope internal constructor(private val closeWith: (after: (() -> Unit)?) -> Unit) {
    /** Plays the card out, then calls [after], or the card's own dismiss without one. */
    fun close(after: (() -> Unit)? = null) = closeWith(after)
}

val LocalFloatingCard = compositionLocalOf<FloatingCardScope?> { null }

/**
 * The app's popup: a rounded card that rises from the bottom over a frosted
 * blur of the screen (dimmed where the phone can't blur behind a window),
 * with a grab handle, an optional icon and title, and a round close button.
 * Dragging the top of the card down, tapping outside or pressing back plays
 * it out the way it came.
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
    LaunchedEffect(Unit) { open = true }
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

    Dialog(
        onDismissRequest = { if (dismissible) card.close() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
    ) {
        FrostBehind(enabled = !ui.reduceBlur)
        val shown by animateFloatAsState(
            if (open) 1f else 0f,
            if (still) snap() else if (open) spring(dampingRatio = 0.8f, stiffness = 360f) else tween(EXIT_MS.toInt()),
            label = "card",
        )
        // A dialog's content sits outside the app's Surface, so it needs its own text colour.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, LocalFloatingCard provides card) {
            BoxWithConstraints(
                Modifier.fillMaxSize().pointerInput(dismissible) { detectTapGestures { if (dismissible) card.close() } },
                contentAlignment = Alignment.BottomCenter,
            ) {
                val maxCard = maxHeight
                val shape = RoundedCornerShape(32.dp)
                val density = LocalDensity.current
                // The card's own height with everything shown, measured as it lays out;
                // an expandable card never grows past it, so a short menu has no empty foot.
                var naturalPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
                val fullPx = minOf(with(density) { (maxCard * 0.9f).toPx() }, naturalPx)
                val halfPx = minOf(with(density) { (maxCard * 0.5f).toPx() }, fullPx)
                val range = (fullPx - halfPx).coerceAtLeast(0f)
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
                            if (!expandable || source != NestedScrollSource.UserInput || available.y <= 0 || extra <= 0f) return Offset.Zero
                            settling?.cancel()
                            val take = minOf(available.y, extra)
                            extra -= take
                            return Offset(0f, take)
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (!expandable || extra <= 0f || extra >= range) return Velocity.Zero
                            settle(available.y)
                            return available
                        }
                    }
                }
                Column(
                    Modifier
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(12.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .then(
                            if (expandable) {
                                Modifier.layout { measurable, constraints ->
                                    // Lists that can't be measured whole (lazy ones) just take the height asked for.
                                    val natural = runCatching { measurable.maxIntrinsicHeight(constraints.maxWidth) }.getOrNull()
                                    if (natural != null && natural.toFloat() != naturalPx) naturalPx = natural.toFloat()
                                    val want = (halfPx + extra).roundToInt()
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
                            val s = 0.92f + 0.08f * shown
                            scaleX = s
                            scaleY = s
                            alpha = shown.coerceIn(0f, 1f)
                        }
                        .shadow(32.dp, shape, ambientColor = MaterialTheme.colorScheme.scrim, spotColor = MaterialTheme.colorScheme.scrim)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
                        // Taps on the card stay on the card.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp),
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
                            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp)
                                .size(width = 36.dp, height = 4.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                        )
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
                    // The body rises in just behind the card.
                    val rise = remember { Animatable(if (still) 1f else 0f) }
                    LaunchedEffect(Unit) { if (!still) { delay(50); rise.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 300f)) } }
                    Column(
                        Modifier.weight(1f, fill = expandable).fillMaxWidth().nestedScroll(grow).padding(contentPadding).graphicsLayer {
                            alpha = rise.value.coerceIn(0f, 1f)
                            translationY = (1f - rise.value) * 16.dp.toPx()
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
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
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

/** Frosts what's behind the dialog's window where the phone allows it; dims it either way. */
@Composable
internal fun FrostBehind(enabled: Boolean) {
    val view = LocalView.current
    val density = LocalDensity.current
    DisposableEffect(view, enabled) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.setDimAmount(if (enabled) 0.35f else 0.55f)
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window.windowManager.isCrossWindowBlurEnabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = with(density) { 36.dp.roundToPx() } }
            }
        }
        onDispose { }
    }
}

private const val EXIT_MS = 200L
private const val KEPT_MS = 120L
/** A release faster than this, in px/s, decides an expandable card's height on its own. */
private const val FLING = 800f
