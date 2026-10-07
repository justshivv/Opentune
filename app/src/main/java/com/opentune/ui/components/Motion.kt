package com.opentune.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.opentune.data.settings.AppSettings

/**
 * A card click with a gentle spring and a ripple. Reduced animation removes
 * the scale, while disabled controls keep their disabled accessibility state.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    pressedScale: Float = 0.97f,
    enabled: Boolean = true,
    onClickLabel: String? = null,
): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        if (pressed && enabled && !ui.reduceAnimation) pressedScale else 1f,
        if (ui.reduceAnimation) snap() else spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.Button,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/** A short dissolve with a small rise; reduced motion keeps only the dissolve. */
fun contentSwap(reducedMotion: Boolean): ContentTransform {
    val enter = fadeIn(tween(180, delayMillis = 50))
    val arrival = if (reducedMotion) enter else enter +
        slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 32 }
    return ContentTransform(
        targetContentEnter = arrival,
        initialContentExit = fadeOut(tween(100)),
        sizeTransform = if (reducedMotion) null else SizeTransform(clip = false) { _, _ -> tween(240) },
    )
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
    val ui by AppSettings.ui.collectAsState()
    if (!enabled || ui.reduceAnimation) return this
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
