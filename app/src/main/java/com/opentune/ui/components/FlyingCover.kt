package com.opentune.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A tapped song's cover flying into the now-playing card. The root
 * watches where taps land ([watchTaps]), the card says where its cover is
 * ([landing], [card]); when a song starts just after a tap outside the
 * card, the cover lifts from the tap and arcs down into the card's.
 */
object FlyingCover {
    internal var target by mutableStateOf<Rect?>(null)
    internal var cardBounds: Rect? = null
    private var tapAt = Offset.Unspecified
    private var tapTime = 0L

    /** Notes each tap on the screen without taking it. */
    fun Modifier.watchTaps(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            tapAt = down.position
            tapTime = SystemClock.uptimeMillis()
        }
    }

    /** On the now-playing card's cover: where flights land. */
    fun Modifier.landing(): Modifier = onGloballyPositioned { target = it.boundsInRoot() }

    /** On the whole card, so taps on it (skip, play) don't send a cover flying. */
    fun Modifier.card(): Modifier = onGloballyPositioned { cardBounds = it.boundsInRoot() }

    /** Where a flight for a song starting now begins, or null if no tap just led to it. */
    internal fun takeStart(): Offset? {
        val recent = SystemClock.uptimeMillis() - tapTime < TAP_WINDOW_MS
        val at = tapAt
        tapTime = 0L
        if (!recent || at == Offset.Unspecified) return null
        if (cardBounds?.inflate(12f)?.contains(at) == true) return null
        return at
    }

    private const val TAP_WINDOW_MS = 1_500L
}

/**
 * The flight itself, over everything: started when [songKey] changes with
 * [url] as its cover. Draws nothing between flights.
 */
@Composable
fun CoverFlight(songKey: String?, url: String?, enabled: Boolean) {
    var flight by remember { mutableStateOf<Triple<String?, Offset, Rect>?>(null) }
    val t = remember { Animatable(0f) }
    LaunchedEffect(songKey) {
        if (!enabled || songKey == null) return@LaunchedEffect
        val start = FlyingCover.takeStart() ?: return@LaunchedEffect
        // The card may still be arriving for a first song; wait a moment for it.
        var end = FlyingCover.target
        var tries = 0
        while (end == null && tries++ < 20) { kotlinx.coroutines.delay(16); end = FlyingCover.target }
        end ?: return@LaunchedEffect
        flight = Triple(url, start, end)
        t.snapTo(0f)
        t.animateTo(1f, tween(720, easing = CubicBezierEasing(0.3f, 0f, 0.15f, 1f)))
        flight = null
    }
    val f = flight ?: return
    val density = LocalDensity.current
    val startSize = with(density) { 72.dp.toPx() }
    val p = t.value
    val (_, from, to) = f
    val size = startSize + (to.width - startSize) * p
    // Along a curve that rises a little before it drops into the card.
    val x = from.x + (to.center.x - from.x) * p
    val y = from.y + (to.center.y - from.y) * p - with(density) { 90.dp.toPx() } * sin(Math.PI.toFloat() * p) * 0.6f
    val lift = 1f + 0.12f * sin(Math.PI.toFloat() * p)
    Box(
        Modifier
            .offset { IntOffset((x - size / 2f).roundToInt(), (y - size / 2f).roundToInt()) }
            .size(with(density) { size.toDp() })
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
                rotationZ = -8f * sin(Math.PI.toFloat() * p)
                alpha = if (p > 0.92f) (1f - p) / 0.08f else 1f
            }
            .shadow(with(density) { (16f * (1f - p)).toDp() }, RoundedCornerShape(14.dp)),
    ) {
        Artwork(f.first, Modifier.matchParentSize(), RoundedCornerShape((14 - 2 * p).dp))
    }
}
