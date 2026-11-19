package com.opentune.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PlayerMotion
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.launch

/**
 * How far the full player has come up, 0 (below the window) to 1 (open), for
 * what inside it should arrive a beat behind the sheet itself.
 */
internal val LocalPlayerReveal = compositionLocalOf<() -> Float> { { 1f } }

/**
 * The full player's sheet: it slides up from the foot of the window over
 * 420ms on the emphasized decelerate curve when [open] turns true, and back
 * down over 300ms, gathering speed, when it turns false. A grab handle at
 * the top centre can be dragged: the sheet follows the pointer, and on
 * release it closes (through [onClose]) if it was pulled far or flung down,
 * or springs back up. Behind it the window dims as it rises.
 *
 * The sheet waits one frame before it moves, so the player is laid out off
 * screen first, and the small cover the backdrop is made from is asked for
 * while the sheet is still closed, so the backdrop is ready when it rises.
 */
@Composable
internal fun PlayerOverlay(open: Boolean, cover: String?, onClose: () -> Unit, content: @Composable () -> Unit) {
    val ui by AppSettings.ui.collectAsState()
    // 0 open, 1 fully below the window.
    val gone = remember { Animatable(if (open) 0f else 1f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PlayerPreload(cover)
    LaunchedEffect(open) {
        if (open) {
            if (gone.value >= 1f) withFrameNanos { }
            gone.animateTo(0f, playerOpenSpec(ui.playerMotion, ui.reduceAnimation))
        } else {
            gone.animateTo(1f, playerCloseSpec(ui.playerMotion, ui.reduceAnimation, gone.value))
        }
    }
    if (!open && gone.value >= 1f) return
    val still = ui.reduceAnimation
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(still) {
            // The handle's drag, read on a layer that doesn't move, so the sheet can follow the
            // pointer exactly; a press anywhere else goes on to the player as usual.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val top = if (still) 0f else gone.value.coerceAtLeast(0f) * size.height
                val half = PLAYER_HANDLE_ZONE_W.toPx() / 2
                val zone = Rect(size.width / 2f - half, top, size.width / 2f + half, top + PLAYER_HANDLE_ZONE_H.toPx())
                if (!zone.contains(down.position)) return@awaitEachGesture
                down.consume()
                dragging = true
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var moved = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dy = change.positionChange().y
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    moved += dy
                    val to = (gone.value + dy / size.height).coerceIn(0f, 1f)
                    scope.launch { gone.snapTo(to) }
                }
                dragging = false
                val flung = tracker.calculateVelocity().y > 1_200f
                if (moved > 4f && (gone.value > 0.22f || flung)) onClose()
                else scope.launch { gone.animateTo(0f, tween(260, easing = Motion.Emphasized)) }
            }
        },
    ) {
        val height = constraints.maxHeight.toFloat()
        // The window behind dims as the sheet comes up.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - gone.value).coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.45f)))
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                if (still) alpha = (1f - gone.value).coerceIn(0f, 1f)
                else translationY = gone.value.coerceAtLeast(0f) * height
            },
        ) {
            CompositionLocalProvider(LocalPlayerReveal provides { if (still) 1f else (1f - gone.value).coerceIn(0f, 1f) }) { content() }
            PlayerGrabHandle(dragging, Modifier.align(Alignment.TopCenter))
        }
    }
}

/** The handle's hit area: wider and taller than the bar itself, so it's easy to catch. */
private val PLAYER_HANDLE_ZONE_W = 120.dp
private val PLAYER_HANDLE_ZONE_H = 30.dp

/** A short rounded bar at the top centre, white at half strength, brighter under the pointer or held. */
@Composable
private fun PlayerGrabHandle(dragging: Boolean, modifier: Modifier) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        modifier.padding(top = 4.dp).size(PLAYER_HANDLE_ZONE_W, PLAYER_HANDLE_ZONE_H - 4.dp).hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = "Drag down to close the player" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(48.dp, 5.dp).clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = if (hovered || dragging) 0.8f else 0.5f)),
        )
    }
}

/**
 * The sheet rising, by the "Player opens" setting: the reference's 420ms
 * emphasized decelerate for Spring (the default) and Smooth, a short one for
 * Snappy, and the two springs for Bouncy and Echo.
 */
private fun playerOpenSpec(m: PlayerMotion, still: Boolean): AnimationSpec<Float> = when {
    still -> tween(160)
    m == PlayerMotion.SNAPPY -> tween(240, easing = Motion.Emphasized)
    m == PlayerMotion.BOUNCY -> spring(dampingRatio = 0.7f, stiffness = 320f)
    m == PlayerMotion.ECHO -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    else -> tween(420, easing = Motion.Emphasized)
}

/** The sheet dropping away: 300ms gathering speed, less for what's left of a drag. */
private fun playerCloseSpec(m: PlayerMotion, still: Boolean, from: Float): AnimationSpec<Float> {
    if (still) return tween(140)
    val full = if (m == PlayerMotion.SNAPPY) 200 else 300
    return tween((full * (1f - from.coerceIn(0f, 1f))).toInt().coerceAtLeast(120), easing = Motion.Exit)
}

/** Asks for the backdrop's small cover ahead, so it's in memory when the sheet opens or the song changes. */
@Composable
private fun PlayerPreload(cover: String?) {
    val context = LocalPlatformContext.current
    LaunchedEffect(cover) {
        if (cover == null) return@LaunchedEffect
        runCatching {
            SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(Subsonic.resolveCover(cover)).size(PLAYER_SOFT_REQUEST).build())
        }
    }
}
