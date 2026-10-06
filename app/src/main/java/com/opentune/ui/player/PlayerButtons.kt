package com.opentune.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.ControlStyle
import com.opentune.ui.components.glass
import kotlin.math.PI
import kotlin.math.sin

/**
 * Back, play/pause and forward in one of the designs after Classic (see
 * [ControlStyle]). All of them share two drawn glyphs: play and pause are
 * one shape that folds from the triangle into the two bars, and the skip
 * arrows roll a step forward (or back) each time they're tapped.
 */
@Composable
internal fun StyledControls(
    style: ControlStyle,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasNext: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    var nextRolls by remember { mutableIntStateOf(0) }
    var previousRolls by remember { mutableIntStateOf(0) }
    Row(
        modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(style, forward = false, rolls = previousRolls, enabled = true, animate = animate) {
            previousRolls++
            onPrevious()
        }
        PlayButton(style, isPlaying, isBuffering, animate, onTogglePlay)
        SkipButton(style, forward = true, rolls = nextRolls, enabled = hasNext, animate = animate) {
            nextRolls++
            onNext()
        }
    }
}

@Composable
private fun PlayButton(style: ControlStyle, isPlaying: Boolean, isBuffering: Boolean, animate: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        if (animate) spring(dampingRatio = 0.45f, stiffness = 520f) else snap(),
        label = "playPress",
    )
    // A small pop each time it changes, on top of the press.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(isPlaying) {
        if (!animate || style == ControlStyle.MORPH) return@LaunchedEffect
        pop.animateTo(1f, keyframes { durationMillis = 360; 1.07f at 120; 0.98f at 240 })
    }
    // Squircle: square-ish while paused, a circle while it plays.
    val corner by animateDpAsState(
        if (isPlaying) 42.dp else 26.dp,
        if (animate) spring(dampingRatio = 0.6f, stiffness = 300f) else snap(),
        label = "playCorner",
    )
    val (container, glyph, glyphSize) = when (style) {
        ControlStyle.DISC -> Triple(scheme.primary, scheme.onPrimary, 44.dp)
        ControlStyle.SQUIRCLE -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, 42.dp)
        ControlStyle.GLASS -> Triple(Color.Transparent, scheme.onSurface, 42.dp)
        else -> Triple(Color.Transparent, scheme.onSurface, 66.dp)
    }
    Box(contentAlignment = Alignment.Center) {
        if (isBuffering) {
            CircularProgressIndicator(Modifier.size(if (style == ControlStyle.MORPH) 84.dp else 94.dp), strokeWidth = 2.dp, color = scheme.onSurface.copy(alpha = 0.5f))
        }
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer { scaleX = press * pop.value; scaleY = press * pop.value }
                .then(
                    when (style) {
                        ControlStyle.DISC -> Modifier.clip(CircleShape).background(container)
                        ControlStyle.SQUIRCLE -> Modifier.clip(RoundedCornerShape(corner)).background(container)
                        ControlStyle.GLASS -> Modifier.glass(CircleShape)
                        else -> Modifier
                    },
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .semantics { role = Role.Button; contentDescription = if (isPlaying) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) {
            PlayPauseGlyph(isPlaying, glyph, animate, Modifier.size(glyphSize))
        }
    }
}

@Composable
private fun SkipButton(style: ControlStyle, forward: Boolean, rolls: Int, enabled: Boolean, animate: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 0.84f else 1f,
        if (animate) spring(dampingRatio = 0.5f, stiffness = 600f) else snap(),
        label = "skipPress",
    )
    // Squircle buttons round off under the finger.
    val corner by animateDpAsState(if (pressed) 30.dp else 18.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "skipCorner")
    val color = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f)
    val (box, glyph) = when (style) {
        ControlStyle.SQUIRCLE -> 60.dp to 34.dp
        ControlStyle.GLASS -> 64.dp to 34.dp
        else -> 68.dp to 54.dp
    }
    Box(
        Modifier
            .size(box)
            .graphicsLayer { scaleX = press; scaleY = press }
            .then(
                when (style) {
                    ControlStyle.SQUIRCLE -> Modifier.clip(RoundedCornerShape(corner)).background(scheme.onSurface.copy(alpha = 0.10f))
                    ControlStyle.GLASS -> Modifier.glass(CircleShape)
                    else -> Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = if (forward) "Next" else "Previous" },
        contentAlignment = Alignment.Center,
    ) {
        SkipGlyph(forward, rolls, color, animate, Modifier.size(glyph))
    }
}

/**
 * Play and pause as one shape. Each half of the triangle is a four-point
 * piece that travels to one of the pause bars, so a tap folds one into the
 * other on a spring that carries a touch past and settles.
 */
@Composable
fun PlayPauseGlyph(playing: Boolean, color: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(
        if (playing) 1f else 0f,
        if (animate) spring(dampingRatio = 0.62f, stiffness = 420f) else snap(),
        label = "playPause",
    )
    Canvas(modifier) { drawPlayPause(p.coerceIn(-0.1f, 1.1f), color) }
}

// The two pieces, as points in a unit square: top-left, top-right, bottom-right, bottom-left.
// Play is a triangle cut down the middle; pause is two bars.
private val PLAY_LEFT = floatArrayOf(0.20f, 0.13f, 0.53f, 0.315f, 0.53f, 0.685f, 0.20f, 0.87f)
private val PLAY_RIGHT = floatArrayOf(0.53f, 0.315f, 0.86f, 0.5f, 0.86f, 0.5f, 0.53f, 0.685f)
private val PAUSE_LEFT = floatArrayOf(0.21f, 0.14f, 0.39f, 0.14f, 0.39f, 0.86f, 0.21f, 0.86f)
private val PAUSE_RIGHT = floatArrayOf(0.61f, 0.14f, 0.79f, 0.14f, 0.79f, 0.86f, 0.61f, 0.86f)

private fun DrawScope.drawPlayPause(p: Float, color: Color) {
    val w = size.minDimension
    // A slight squeeze through the middle of the fold.
    val squeeze = 1f - 0.07f * sin(PI.toFloat() * p.coerceIn(0f, 1f))
    val stroke = Stroke(width = w * 0.09f, join = StrokeJoin.Round)
    scale(squeeze, squeeze) {
        for ((from, to) in listOf(PLAY_LEFT to PAUSE_LEFT, PLAY_RIGHT to PAUSE_RIGHT)) {
            val path = Path()
            for (i in 0 until 4) {
                val x = (from[2 * i] + (to[2 * i] - from[2 * i]) * p) * w
                val y = (from[2 * i + 1] + (to[2 * i + 1] - from[2 * i + 1]) * p) * w
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            // Filled, then stroked with round joins in the same colour, so the corners are soft.
            drawPath(path, color, style = Fill)
            drawPath(path, color, style = stroke)
        }
    }
}

/**
 * Two arrowheads, like ⏩. Each tap rolls them a step on: the front one is
 * pushed on and shrinks away, the back one moves up to take its place, and
 * a new one grows in behind, while the whole glyph nudges in the direction
 * of travel.
 * [rolls] counts taps; every change plays one roll.
 */
@Composable
fun SkipGlyph(forward: Boolean, rolls: Int, color: Color, animate: Boolean, modifier: Modifier = Modifier) {
    // At rest a full roll and none look the same, so resting at 1 lets every tap start from 0.
    val t = remember { Animatable(1f) }
    LaunchedEffect(rolls) {
        if (rolls == 0 || !animate) return@LaunchedEffect
        t.snapTo(0f)
        t.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val w = size.minDimension
        val nudge = sin(PI.toFloat() * t.value) * w * 0.06f
        scale(if (forward) 1f else -1f, 1f) {
            translate(left = nudge) { drawArrows(t.value, color) }
        }
    }
}

private fun DrawScope.drawArrows(t: Float, color: Color) {
    val w = size.minDimension
    val head = 0.42f * w
    val height = 0.6f * w
    val cy = size.height / 2f
    val start = (size.width - 2 * head) / 2f
    val shift = t * head
    val stroke = Stroke(width = w * 0.06f, join = StrokeJoin.Round)
    fun arrow(left: Float, scale: Float, alpha: Float) {
        if (scale <= 0.01f || alpha <= 0.01f) return
        val hw = head * scale
        val hh = height * scale
        val path = Path().apply {
            moveTo(left, cy - hh / 2f)
            lineTo(left + hw, cy)
            lineTo(left, cy + hh / 2f)
            close()
        }
        // Fill and the soft-cornered stroke go down opaque in a layer that's
        // faded as one, so a half-faded arrow doesn't show its outline.
        val bounds = androidx.compose.ui.geometry.Rect(left - w * 0.1f, cy - hh, left + hw + w * 0.1f, cy + hh)
        drawContext.canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
        drawPath(path, color, style = Fill)
        drawPath(path, color, style = stroke)
        drawContext.canvas.restore()
    }
    // The new one grows in at the back.
    arrow(start, t, t)
    // The back one moves up to the front.
    arrow(start + shift, 1f, 1f)
    // The front one is pushed on ahead of it, shrinking as it goes.
    arrow(start + head + shift, 1f - t, 1f - t)
}
