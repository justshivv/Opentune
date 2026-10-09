package com.opentune.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.ControlStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Back, play/pause and forward, in one of three designs that each have their
 * own shape and their own motion (see [ControlStyle]):
 *
 * - Bloom: big bare glyphs; a soft disc of light blooms behind a press.
 * - Capsule: play in an accent pill that stretches wide while playing;
 *   the skips sit in tinted circles that tilt toward where they go.
 * - Orbit: play inside the song's progress ring, a comet of light circling
 *   it while music plays; a skip sends an arc spinning round its button.
 * - Morph: a solid disc while paused that ripples into a slowly turning
 *   wavy shape while it plays, its outlined play triangle folding into two
 *   outlined bars; a skip sends a ripple round its own disc.
 *
 * Bloom, Capsule and Orbit share the drawn glyphs below: play folds into pause, and the
 * skip arrows roll a step on with each tap.
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasNext: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlStyle = ControlStyle.BLOOM,
    animate: Boolean = true,
    progress: () -> Float = { 0f },
) {
    var nextRolls by remember { mutableIntStateOf(0) }
    var previousRolls by remember { mutableIntStateOf(0) }
    // Deep, strong feels for the transport, each its own (see Haptics.Pattern).
    val haptics = com.opentune.ui.components.rememberHaptics()
    val back = { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.PREVIOUS); previousRolls++; onPrevious() }
    val ahead = { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.NEXT); nextRolls++; onNext() }
    val playPause = {
        haptics.pattern(if (isPlaying) com.opentune.ui.components.Haptics.Pattern.PAUSE else com.opentune.ui.components.Haptics.Pattern.PLAY)
        onTogglePlay()
    }
    Row(
        modifier.fillMaxWidth().height(104.dp),
        // Capsule is a tight cluster; the bare designs spread across the row.
        horizontalArrangement = if (style == ControlStyle.CAPSULE || style == ControlStyle.MORPH) Arrangement.Center else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (style) {
            ControlStyle.ECHO -> {
                BloomSkip(forward = false, previousRolls, enabled = true, animate, back)
                EchoPlay(isPlaying, animate, playPause)
                BloomSkip(forward = true, nextRolls, hasNext, animate, ahead)
            }
            ControlStyle.BLOOM -> {
                BloomSkip(forward = false, previousRolls, enabled = true, animate, back)
                BloomPlay(isPlaying, isBuffering, animate, playPause)
                BloomSkip(forward = true, nextRolls, hasNext, animate, ahead)
            }
            ControlStyle.CAPSULE -> {
                CapsuleSkip(forward = false, previousRolls, enabled = true, animate, back)
                Spacer(Modifier.width(26.dp))
                CapsulePlay(isPlaying, isBuffering, animate, playPause)
                Spacer(Modifier.width(26.dp))
                CapsuleSkip(forward = true, nextRolls, hasNext, animate, ahead)
            }
            ControlStyle.ORBIT -> {
                OrbitSkip(forward = false, previousRolls, enabled = true, animate, back)
                OrbitPlay(isPlaying, isBuffering, animate, progress, playPause)
                OrbitSkip(forward = true, nextRolls, hasNext, animate, ahead)
            }
            ControlStyle.MORPH -> {
                MorphSkip(forward = false, previousRolls, enabled = true, animate, back)
                Spacer(Modifier.width(30.dp))
                MorphPlay(isPlaying, isBuffering, animate, playPause)
                Spacer(Modifier.width(30.dp))
                MorphSkip(forward = true, nextRolls, hasNext, animate, ahead)
            }
        }
    }
}

/** How far a press has gone, 0 to 1, springing in under the finger and easing back out. */
@Composable
private fun pressAmount(pressed: Boolean, animate: Boolean): Float {
    val p by animateFloatAsState(
        if (pressed) 1f else 0f,
        when {
            !animate -> snap()
            pressed -> spring(dampingRatio = 0.8f, stiffness = 900f)
            else -> spring(dampingRatio = 0.55f, stiffness = 380f)
        },
        label = "press",
    )
    return p
}

private fun Modifier.button(interaction: MutableInteractionSource, enabled: Boolean, label: String, onClick: () -> Unit) =
    clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
        .semantics { role = Role.Button; contentDescription = label }

/** Echo Music's nine-lobed, eight-second play-button rotation; GPL-3.0 attribution in THIRD_PARTY_NOTICES.md. */
@Composable
private fun EchoPlay(playing: Boolean, animate: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    val phase = rememberEchoPhase(playing && animate, 8_000)
    val depth by animateFloatAsState(if (playing) 0.08f else 0f, if (animate) tween(300, easing = LinearEasing) else snap(), label = "echoIndent")
    val fill = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onPrimary
    val path = remember { Path() }
    Box(
        Modifier.size(100.dp).graphicsLayer { scaleX = 1f - press * 0.05f; scaleY = scaleX }
            .drawBehind {
                wavyCircle(path, size, size.minDimension / 2f, depth, phase.value * 360f)
                drawPath(path, fill)
            }
            .button(interaction, true, if (playing) "Pause" else "Play", onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(playing, ink, animate, Modifier.size(46.dp))
    }
}

// ---- Bloom -------------------------------------------------------------------

@Composable
private fun BloomPlay(isPlaying: Boolean, isBuffering: Boolean, animate: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    val ink = MaterialTheme.colorScheme.onSurface
    val pulse = loadingPulse(isBuffering && animate)
    Box(
        Modifier.size(100.dp).drawBloom(press, ink, pulse).button(interaction, true, if (isPlaying) "Pause" else "Play", onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(isPlaying, ink, animate, Modifier.size(70.dp).graphicsLayer { val s = 1f - 0.16f * press; scaleX = s; scaleY = s })
    }
}

@Composable
private fun BloomSkip(forward: Boolean, rolls: Int, enabled: Boolean, animate: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f)
    Box(
        Modifier.size(76.dp).drawBloom(press, MaterialTheme.colorScheme.onSurface, 0f).button(interaction, enabled, if (forward) "Next" else "Previous", onClick),
        contentAlignment = Alignment.Center,
    ) {
        SkipGlyph(forward, rolls, ink, animate, Modifier.size(50.dp).graphicsLayer { val s = 1f - 0.16f * press; scaleX = s; scaleY = s })
    }
}

/** A soft disc behind the glyph that grows and brightens with the press, and breathes while loading. */
private fun Modifier.drawBloom(press: Float, ink: Color, pulse: Float) = this.then(
    Modifier.drawBehindCompat { size ->
        val r = size.minDimension / 2f
        val amount = maxOf(press, pulse * 0.6f)
        if (amount > 0.01f) {
            drawCircle(ink.copy(alpha = 0.13f * amount), radius = r * (0.62f + 0.38f * amount))
        }
    },
)

// ---- Capsule -----------------------------------------------------------------

@Composable
private fun CapsulePlay(isPlaying: Boolean, isBuffering: Boolean, animate: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    // 0 is a circle (paused), 1 a wide pill (playing), on a spring that overshoots a touch.
    val stretch by animateFloatAsState(
        if (isPlaying) 1f else 0f,
        if (animate) spring(dampingRatio = 0.55f, stiffness = 260f) else snap(),
        label = "capsule",
    )
    val sheen = looping(isBuffering && animate, -0.4f, 1.4f, 1_100, reverse = false)
    val h = 84.dp
    val w = h + 52.dp * stretch
    Box(
        Modifier
            .size(width = w.coerceAtLeast(60.dp), height = h)
            .graphicsLayer {
                // Squashes a little as it stretches, like something soft.
                val s = 1f - 0.06f * press
                scaleX = s
                scaleY = s * (1f - 0.05f * sin(PI.toFloat() * stretch.coerceIn(0f, 1f)))
            }
            .drawBehindCompat { size ->
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(scheme.primary, cornerRadius = radius)
                // A band of light sweeps across while the song loads.
                if (isBuffering && animate) {
                    val x = sheen * size.width
                    drawRoundRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, scheme.onPrimary.copy(alpha = 0.28f), Color.Transparent),
                            startX = x - size.width * 0.35f,
                            endX = x + size.width * 0.35f,
                        ),
                        cornerRadius = radius,
                    )
                }
            }
            .button(interaction, true, if (isPlaying) "Pause" else "Play", onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(isPlaying, scheme.onPrimary, animate, Modifier.size(40.dp))
    }
}

@Composable
private fun CapsuleSkip(forward: Boolean, rolls: Int, enabled: Boolean, animate: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    // A lean toward where the tap is going, that wobbles back once let go.
    val lean = remember { Animatable(0f) }
    LaunchedEffect(rolls) {
        if (rolls == 0 || !animate) return@LaunchedEffect
        lean.snapTo(1f)
        lean.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 260f))
    }
    val dir = if (forward) 1f else -1f
    val alpha = if (enabled) 1f else 0.35f
    Box(
        Modifier
            .size(64.dp)
            .graphicsLayer {
                val s = 1f - 0.1f * press
                scaleX = s
                scaleY = s
                rotationZ = dir * (14f * lean.value + 8f * press)
                translationX = dir * (6.dp.toPx() * lean.value)
                this.alpha = alpha
            }
            .drawBehindCompat { size -> drawCircle(scheme.primary.copy(alpha = 0.16f + 0.1f * press), radius = size.minDimension / 2f) }
            .button(interaction, enabled, if (forward) "Next" else "Previous", onClick),
        contentAlignment = Alignment.Center,
    ) {
        SkipGlyph(forward, rolls, scheme.primary, animate, Modifier.size(32.dp))
    }
}

// ---- Orbit -------------------------------------------------------------------

@Composable
private fun OrbitPlay(isPlaying: Boolean, isBuffering: Boolean, animate: Boolean, progress: () -> Float, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    val moving = animate && (isPlaying || isBuffering)
    // Each loop runs only while it's seen: the comet while music moves, the breath while paused.
    val comet = looping(moving, 0f, 360f, if (isBuffering) 900 else 2_600, reverse = false)
    val breathe = looping(animate && !moving, 0.55f, 1f, 1_400, reverse = true)
    val cometAlpha by animateFloatAsState(if (moving) 1f else 0f, tween(400), label = "cometAlpha")
    Box(
        Modifier
            .size(104.dp)
            .graphicsLayer { val s = 1f - 0.08f * press; scaleX = s; scaleY = s }
            .drawBehindCompat { size ->
                val stroke = (4.dp.toPx() + 3.dp.toPx() * press)
                val inset = stroke / 2f + 4.dp.toPx()
                val arc = Size(size.width - inset * 2, size.height - inset * 2)
                val tl = Offset(inset, inset)
                // The track breathes while paused, holds still while playing.
                val trackAlpha = if (isPlaying || !animate) 0.14f else 0.1f + 0.08f * breathe
                drawArc(scheme.onSurface.copy(alpha = trackAlpha), 0f, 360f, false, tl, arc, style = Stroke(stroke))
                drawArc(scheme.primary, -90f, 360f * progress().coerceIn(0f, 1f), false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (cometAlpha > 0.01f) {
                    rotate(comet) {
                        drawArc(
                            Brush.sweepGradient(
                                0f to Color.Transparent,
                                0.10f to scheme.onSurface.copy(alpha = 0.85f * cometAlpha),
                                0.11f to Color.Transparent,
                                center = center,
                            ),
                            0f, 40f, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                // A faint fill that brightens under the finger.
                drawCircle(scheme.onSurface.copy(alpha = 0.04f + 0.08f * press), radius = arc.width / 2f - stroke)
            }
            .button(interaction, true, if (isPlaying) "Pause" else "Play", onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(isPlaying, scheme.onSurface, animate, Modifier.size(44.dp))
    }
}

@Composable
private fun OrbitSkip(forward: Boolean, rolls: Int, enabled: Boolean, animate: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    // Each tap sends a short arc once round the button, in the skip's direction.
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(rolls) {
        if (rolls == 0 || !animate) return@LaunchedEffect
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
    }
    val ink = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f)
    Box(
        Modifier
            .size(72.dp)
            .graphicsLayer { val s = 1f - 0.12f * press; scaleX = s; scaleY = s }
            .drawBehindCompat { size ->
                val t = sweep.value
                if (t < 1f) {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2f + 2.dp.toPx()
                    val start = -90f + (if (forward) 1f else -1f) * 360f * t
                    drawArc(
                        scheme.primary.copy(alpha = 1f - t),
                        start, if (forward) 70f else -70f, false,
                        Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                drawCircle(scheme.onSurface.copy(alpha = 0.09f * press), radius = size.minDimension / 2f)
            }
            .button(interaction, enabled, if (forward) "Next" else "Previous", onClick),
        contentAlignment = Alignment.Center,
    ) {
        SkipGlyph(forward, rolls, ink, animate, Modifier.size(40.dp))
    }
}

// ---- Morph -------------------------------------------------------------------

@Composable
private fun MorphPlay(isPlaying: Boolean, isBuffering: Boolean, animate: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    // 0 is the round disc (paused), 1 the wavy shape (playing). The spring
    // carries the ripple a little past and lets it settle, like something soft.
    val wave by animateFloatAsState(
        if (isPlaying) 1f else 0f,
        if (animate) spring(dampingRatio = 0.5f, stiffness = 170f) else snap(),
        label = "wave",
    )
    // The waves turn slowly while music plays, and quicker while it loads.
    val turn = looping(animate && (isPlaying || isBuffering), 0f, 360f, if (isBuffering) 2_400 else 14_000, reverse = false)
    val pulse = loadingPulse(isBuffering && animate)
    val fill = scheme.onSurface
    val path = remember { Path() }
    Box(
        Modifier
            .size(100.dp)
            .graphicsLayer { val s = 1f - 0.08f * press; scaleX = s; scaleY = s }
            .drawBehindCompat { size ->
                val depth = MORPH_DEPTH * (wave.coerceAtLeast(-0.2f) + 0.35f * pulse)
                wavyCircle(path, size, size.minDimension / 2f, depth, turn)
                drawPath(path, fill)
            }
            .button(interaction, true, if (isPlaying) "Pause" else "Play", onClick),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedPlayPauseGlyph(isPlaying, scheme.surface, animate, Modifier.size(46.dp))
    }
}

@Composable
private fun MorphSkip(forward: Boolean, rolls: Int, enabled: Boolean, animate: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val press = pressAmount(interaction.collectIsPressedAsState().value, animate)
    // Each tap ripples the disc into waves that turn the skip's way and smooth out again.
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(rolls) {
        if (rolls == 0 || !animate) return@LaunchedEffect
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
    }
    val ink = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f)
    val path = remember { Path() }
    Box(
        Modifier
            .size(66.dp)
            .graphicsLayer { val s = 1f - 0.1f * press; scaleX = s; scaleY = s }
            .drawBehindCompat { size ->
                val t = ripple.value
                val depth = MORPH_DEPTH * (sin(PI.toFloat() * t) + 0.5f * press)
                wavyCircle(path, size, size.minDimension / 2f, depth, (if (forward) 1f else -1f) * 80f * t)
                drawPath(path, scheme.onSurface.copy(alpha = 0.12f + 0.08f * press))
            }
            .button(interaction, enabled, if (forward) "Next" else "Previous", onClick),
        contentAlignment = Alignment.Center,
    ) {
        SkipGlyph(forward, rolls, ink, animate, Modifier.size(34.dp))
    }
}

/** How deep the Morph button's waves are, as a part of its radius, when fully wavy. */
private const val MORPH_DEPTH = 0.075f
private const val MORPH_WAVES = 9

/**
 * Sets [path] to a circle of [radius] whose edge rises and falls in
 * [MORPH_WAVES] rounded waves, [depth] of the radius deep (0 is a plain
 * circle), turned by [degrees]. The crests touch [radius], so the shape
 * never grows past its box as the waves come in.
 */
internal fun wavyCircle(path: Path, size: Size, radius: Float, depth: Float, degrees: Float) {
    path.reset()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val turn = degrees * (PI.toFloat() / 180f)
    val steps = 180
    for (i in 0..steps) {
        val a = 2f * PI.toFloat() * i / steps
        val r = radius * (1f - depth + depth * cos(MORPH_WAVES * (a - turn)))
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/**
 * Play and pause drawn as outlines: a hollow triangle that folds into two
 * hollow bars. Each shape is a solid piece with a smaller piece cut out of
 * it, and both travel, so the outline stays even all the way through.
 */
@Composable
fun OutlinedPlayPauseGlyph(playing: Boolean, color: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(
        if (playing) 1f else 0f,
        if (animate) spring(dampingRatio = 0.62f, stiffness = 420f) else snap(),
        label = "outlinedPlayPause",
    )
    val path = remember { Path() }
    Canvas(modifier) {
        val t = p.coerceIn(-0.06f, 1.06f)
        val w = size.minDimension
        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
        for ((from, to) in OUTER) {
            quad(path, from, to, t, w)
            drawPath(path, color)
        }
        // The hollow is cut out of what was just drawn.
        for ((from, to) in INNER) {
            quad(path, from, to, t.coerceIn(0f, 1f), w)
            drawPath(path, Color.Black, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        }
        drawContext.canvas.restore()
    }
}

private fun quad(path: Path, from: FloatArray, to: FloatArray, t: Float, w: Float) {
    path.reset()
    for (i in 0 until 4) {
        val x = (from[2 * i] + (to[2 * i] - from[2 * i]) * t) * w
        val y = (from[2 * i + 1] + (to[2 * i + 1] - from[2 * i + 1]) * t) * w
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

// Points in a unit square: top-left, top-right, bottom-right, bottom-left. The
// triangle is cut down the middle into two pieces, each meeting a bar; the
// halves overlap a hair at the cut so no seam shows.
private val OUTER = listOf(
    floatArrayOf(0.28f, 0.16f, 0.524f, 0.3081f, 0.524f, 0.6919f, 0.28f, 0.84f) to
        floatArrayOf(0.22f, 0.17f, 0.44f, 0.17f, 0.44f, 0.83f, 0.22f, 0.83f),
    floatArrayOf(0.516f, 0.3033f, 0.84f, 0.5f, 0.84f, 0.5f, 0.516f, 0.6967f) to
        floatArrayOf(0.56f, 0.17f, 0.78f, 0.17f, 0.78f, 0.83f, 0.56f, 0.83f),
)
private val INNER = listOf(
    floatArrayOf(0.375f, 0.3288f, 0.524f, 0.4193f, 0.524f, 0.5807f, 0.375f, 0.6712f) to
        floatArrayOf(0.295f, 0.245f, 0.365f, 0.245f, 0.365f, 0.755f, 0.295f, 0.755f),
    floatArrayOf(0.516f, 0.4145f, 0.6569f, 0.5f, 0.6569f, 0.5f, 0.516f, 0.5855f) to
        floatArrayOf(0.635f, 0.245f, 0.705f, 0.245f, 0.705f, 0.755f, 0.635f, 0.755f),
)

// ---- Shared ------------------------------------------------------------------

/** 0 to 1 and back while [on], for the "loading" breath of the Bloom disc; 0 when off. */
@Composable
private fun loadingPulse(on: Boolean): Float = looping(on, 0f, 1f, 700, reverse = true)

/**
 * A value running from [from] to [to] over [ms] and over again (back and
 * forth with [reverse]) while [on]; [from] when off. Off, no animation runs
 * at all, so a still button costs nothing.
 */
@Composable
private fun looping(on: Boolean, from: Float, to: Float, ms: Int, reverse: Boolean): Float {
    if (!on) return from
    val v by rememberInfiniteTransition(label = "loop").animateFloat(
        from,
        to,
        infiniteRepeatable(
            tween(ms, easing = if (reverse) FastOutSlowInEasing else LinearEasing),
            if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "loopValue",
    )
    return v
}

/** Draws behind the content, with the drawing's size. */
private fun Modifier.drawBehindCompat(block: DrawScope.(Size) -> Unit) = drawBehind { block(size) }

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
