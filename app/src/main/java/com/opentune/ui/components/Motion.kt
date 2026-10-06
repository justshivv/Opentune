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
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * Motion artwork made from the cover itself: while [playing], the image
 * drifts and zooms slowly inside its frame on a slow, never-repeating path
 * (two sines at unrelated speeds per axis). Pausing eases it to a stop where
 * it is, and playing picks up from there. Nothing when [enabled] is false.
 * Use inside a clipped frame, e.g. as Artwork's imageModifier.
 */
@androidx.compose.runtime.Composable
fun Modifier.livingArt(playing: Boolean, enabled: Boolean): Modifier {
    if (!enabled) return this
    var t by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val amount by animateFloatAsState(if (playing) 1f else 0f, androidx.compose.animation.core.tween(1_400), label = "livingArt")
    androidx.compose.runtime.LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                t += (now - last) / 1e9f
                last = now
            }
        }
    }
    return this.graphicsLayer {
        val zoom = 0.075f + 0.035f * kotlin.math.sin(t * 0.21f)
        val s = 1f + zoom * amount
        scaleX = s
        scaleY = s
        // Keep the drift inside what the zoom adds, so no edge ever shows.
        val room = zoom * 0.42f * amount
        translationX = size.width * room * (0.7f * kotlin.math.sin(t * 0.13f) + 0.3f * kotlin.math.sin(t * 0.31f + 1.3f))
        translationY = size.height * room * (0.7f * kotlin.math.cos(t * 0.11f) + 0.3f * kotlin.math.sin(t * 0.27f + 0.4f))
    }
}

