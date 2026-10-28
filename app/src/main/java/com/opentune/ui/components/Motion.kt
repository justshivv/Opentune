package com.opentune.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * A click that sinks a little under the finger and springs back, the way
 * iOS tiles do. Use on cards and tiles; list rows keep their ripple.
 */
@Composable
fun Modifier.pressable(onClick: () -> Unit, pressedScale: Float = 0.95f): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = 600f),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = FocusRing(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), onClick = onClick)
}

/**
 * Motion artwork made from the cover itself: while [playing], the image
 * drifts and zooms slowly inside its frame on a slow, never-repeating path
 * (two sines at unrelated speeds per axis). Pausing eases it to a stop where
 * it is, and playing picks up from there. Nothing when [enabled] is false.
 * Use inside a clipped frame, e.g. as Artwork's imageModifier.
 */
/** A shared clock lets a flying cover and its destination draw the same crop. */
class ArtworkMotion(val time: () -> Float, val amount: () -> Float)

@Composable
fun rememberArtworkMotion(playing: Boolean): ArtworkMotion {
    var t by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val amount = animateFloatAsState(if (playing) 1f else 0f, androidx.compose.animation.core.tween(1_400), label = "livingArt")
    androidx.compose.runtime.LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { now ->
            t += (now - last) / 1e9f
            last = now
        }
    }
    return remember { ArtworkMotion({ t }, { amount.value }) }
}

@Composable
fun Modifier.livingArt(playing: Boolean, enabled: Boolean, shared: ArtworkMotion? = null, strength: () -> Float = { 1f }): Modifier {
    if (!enabled) return this
    val motion = shared ?: rememberArtworkMotion(playing)
    return this.graphicsLayer {
        val t = motion.time()
        val amount = motion.amount() * strength().coerceIn(0f, 1f)
        val zoom = 0.075f + 0.035f * kotlin.math.sin(t * 0.21f)
        val s = 1f + zoom * amount
        scaleX = s; scaleY = s
        val room = zoom * 0.42f * amount
        translationX = size.width * room * (0.7f * kotlin.math.sin(t * 0.13f) + 0.3f * kotlin.math.sin(t * 0.31f + 1.3f))
        translationY = size.height * room * (0.7f * kotlin.math.cos(t * 0.11f) + 0.3f * kotlin.math.sin(t * 0.27f + 0.4f))
    }
}
