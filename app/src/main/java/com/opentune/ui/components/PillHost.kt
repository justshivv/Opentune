package com.opentune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.opentune.data.Pills
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select

/**
 * Where [Pills] show up: one at a time, floating in under the status bar.
 * Each starts as a dot in the middle (a tick draws itself in it for
 * something done), then stretches sideways into a pill with the words. It
 * stays a couple of seconds, longer with a button on it, and goes back the
 * way it came; a tap sends it off early. A newer note cuts the wait short
 * instead of queueing behind it.
 *
 * Only moves, clips and fades a layer as it animates: the pill is laid out
 * once per note.
 */
@Composable
fun PillHost(reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val queue = remember { Channel<Pills.Pill>(Channel.UNLIMITED) }
    val dismissals = remember { Channel<Unit>(Channel.CONFLATED) }
    // Collected only while this screen is in front; otherwise notes become toasts.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { Pills.posted.collect { queue.trySend(it) } }
    }
    var current by remember { mutableStateOf<Pills.Pill?>(null) }
    // 0 gone, 1 the dot is in place.
    val dot = remember { Animatable(0f) }
    // 0 a dot, 1 the whole pill.
    val stretch = remember { Animatable(0f) }
    // How far the tick has drawn.
    val mark = remember { Animatable(0f) }
    val reduce by rememberUpdatedState(reduceMotion)
    LaunchedEffect(Unit) {
        var next: Pills.Pill? = null
        while (true) {
            val pill = next ?: queue.receive()
            next = null
            dismissals.tryReceive()
            dot.snapTo(0f); stretch.snapTo(0f); mark.snapTo(0f)
            current = pill
            val shownAt = System.currentTimeMillis()
            if (reduce) {
                stretch.snapTo(1f); mark.snapTo(1f)
                dot.animateTo(1f, tween(140))
            } else coroutineScope {
                launch { dot.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 520f)) }
                launch { delay(90); mark.animateTo(1f, tween(380, easing = TICK_EASING)) }
                delay(150)
                stretch.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 360f, visibilityThreshold = 0.001f))
            }
            // Long enough to read: longer notes stay longer.
            val hold = (HOLD_MS + pill.text.length * HOLD_PER_CHAR_MS).coerceAtMost(HOLD_MAX_MS) + if (pill.action != null) HOLD_ACTION_MS else 0L
            next = withTimeoutOrNull(hold) {
                select<Pills.Pill?> {
                    queue.onReceive { it }
                    dismissals.onReceive { null }
                }
            }
            // A newer note still lets this one be read for a moment.
            if (next != null) delay((MIN_SHOWN_MS - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0L))
            if (reduce) {
                dot.animateTo(0f, tween(120))
            } else coroutineScope {
                launch { stretch.animateTo(0f, tween(220, easing = OUT_EASING)) }
                delay(120)
                dot.animateTo(0f, tween(160, easing = OUT_EASING))
            }
            current = null
        }
    }

    val pill = current ?: return
    val density = LocalDensity.current
    val lift = with(density) { 18.dp.toPx() }
    val shadow = with(density) { 10.dp.toPx() }
    // Use the same surface and content pairs as the surrounding interface.
    // Tint only the status mark; tinting the whole face fights custom/AMOLED themes.
    val colors = MaterialTheme.colorScheme
    val face = colors.surfaceContainerHigh
    val ink = colors.onSurface
    val badge = when (pill.kind) {
        Pills.Kind.DONE -> colors.primaryContainer
        Pills.Kind.ERROR -> colors.errorContainer
        Pills.Kind.INFO -> colors.secondaryContainer
    }
    val markInk = when (pill.kind) {
        Pills.Kind.DONE -> colors.onPrimaryContainer
        Pills.Kind.ERROR -> colors.onErrorContainer
        Pills.Kind.INFO -> colors.onSecondaryContainer
    }
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .wrapContentWidth()
                .widthIn(max = 420.dp)
                .heightIn(min = PILL_HEIGHT)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .graphicsLayer {
                    val w = size.width
                    val h = size.height
                    val d = dot.value
                    val k = stretch.value.coerceIn(0f, 1f)
                    // The dot sits where the middle of the pill will be, then the
                    // pill grows out to its right while sliding back to centre.
                    translationX = (w - h) / 2f * (1f - k)
                    translationY = -lift * (1f - d)
                    scaleX = 0.5f + 0.5f * d
                    scaleY = 0.5f + 0.5f * d
                    alpha = d.coerceIn(0f, 1f)
                    shape = RevealShape(h + (w - h) * k)
                    clip = true
                    shadowElevation = shadow * d.coerceIn(0f, 1f)
                }
                .background(face)
                .clickable(remember { MutableInteractionSource() }, indication = null) { dismissals.trySend(Unit) }
                .padding(start = 7.dp, end = if (pill.action != null) 10.dp else 18.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(pill.kind, badge, markInk) { mark.value }
            Spacer(Modifier.width(10.dp))
            Text(
                pill.text,
                color = ink,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .graphicsLayer { alpha = ((stretch.value - 0.35f) / 0.65f).coerceIn(0f, 1f) },
            )
            val action = pill.action
            if (action != null) {
                Spacer(Modifier.width(14.dp))
                Text(
                    action,
                    color = colors.primary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .graphicsLayer { alpha = ((stretch.value - 0.5f) / 0.5f).coerceIn(0f, 1f) }
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { pill.onAction?.invoke(); dismissals.trySend(Unit) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** The round mark at the start of a pill: a tick, an exclamation or a dot, drawn in as [drawn] goes 0..1. */
@Composable
private fun Badge(kind: Pills.Kind, fill: Color, ink: Color, drawn: () -> Float) {
    Canvas(Modifier.size(BADGE_SIZE)) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val t = drawn().coerceIn(0f, 1f)
        drawCircle(fill, r, c)
        val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            Pills.Kind.DONE -> {
                val tick = Path().apply {
                    moveTo(c.x - r * 0.42f, c.y + r * 0.02f)
                    lineTo(c.x - r * 0.12f, c.y + r * 0.32f)
                    lineTo(c.x + r * 0.44f, c.y - r * 0.30f)
                }
                val measure = PathMeasure().apply { setPath(tick, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * t, part, true)
                drawPath(part, ink, style = stroke)
            }
            Pills.Kind.ERROR -> {
                val top = c.y - r * 0.42f
                val bottom = c.y + r * 0.12f
                drawLine(ink, Offset(c.x, top), Offset(c.x, top + (bottom - top) * t), stroke.width, StrokeCap.Round)
                if (t > 0.8f) drawCircle(ink, stroke.width * 0.62f * ((t - 0.8f) / 0.2f), Offset(c.x, c.y + r * 0.42f))
            }
            Pills.Kind.INFO -> drawCircle(ink, r * 0.22f * t, c)
        }
    }
}

/** A pill [width] wide from the left, as tall as the layer, with fully round ends. */
private class RevealShape(private val width: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = size.height / 2f
        // No narrower than round ends need, unless the layer itself is narrower than it is tall
        // (its first frame): a range whose low end passed its high end would throw.
        return Outline.Rounded(RoundRect(0f, 0f, width.coerceIn(minOf(size.height, size.width), size.width), size.height, CornerRadius(r)))
    }
}

private val PILL_HEIGHT = 44.dp
private val BADGE_SIZE = 30.dp
private val TICK_EASING = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)
private val OUT_EASING = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private const val HOLD_MS = 1600L
private const val HOLD_PER_CHAR_MS = 35L
private const val HOLD_MAX_MS = 5000L
private const val HOLD_ACTION_MS = 2000L
private const val MIN_SHOWN_MS = 900L
