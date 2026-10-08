package com.opentune.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.opentune.playback.AudioLevels
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** How many pools of light stand along the bottom edge. */
private const val LIGHTS = 6

/**
 * Light rising from the bottom of the screen, in time with the music:
 * pools of colour that slowly cycle through the hues (leaning toward the
 * cover's colour), swell with the bass, flash on the beat and now and then
 * flicker out for a moment like a failing stage light, over a thin glowing
 * strip along the edge. With less motion it's one steady, quiet glow.
 */
@Composable
fun StageLights(seed: Color?, playing: Boolean, still: Boolean, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        AudioLevels.listening = true
        onDispose { AudioLevels.listening = false }
    }
    val lights = remember { Lights() }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, still) {
        var last = 0L
        while (true) {
            val keepGoing = androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                lights.step(AudioLevels.at(System.nanoTime()), dt, playing, still)
                frame.longValue = now
                playing || lights.fade > 0.005f
            }
            if (!keepGoing) break
        }
    }
    val tint = seed ?: Color(0xFFFF4D9D)
    Canvas(modifier.fillMaxSize()) {
        frame.longValue // redraw every frame the clock moves
        val fade = lights.fade
        if (fade <= 0.005f) return@Canvas
        val w = size.width
        val h = size.height
        val energy = lights.energy
        val beat = lights.beat
        for (i in 0 until LIGHTS) {
            val hue = (lights.hue + i * 48f) % 360f
            val color = lerp(Color.hsv(hue, 0.88f, 1f), tint, 0.14f)
            val sway = sin(lights.time * (0.35f + i * 0.07f) + i * 1.7f) * w * 0.04f
            val x = w * (i + 0.5f) / LIGHTS + sway
            val r = w * (0.34f + 0.16f * energy + 0.05f * beat) * (0.9f + 0.2f * ((i * 37) % 10) / 10f)
            val alpha = ((if (still) 0.38f else 0.22f + 0.58f * energy + 0.3f * beat) * lights.flicker[i] * fade).coerceIn(0f, 0.9f)
            if (alpha < 0.01f) continue
            val center = Offset(x, h + r * 0.3f)
            drawCircle(
                Brush.radialGradient(0f to color.copy(alpha = alpha), 0.45f to color.copy(alpha = alpha * 0.5f), 1f to Color.Transparent, center = center, radius = r),
                r, center, blendMode = BlendMode.Screen,
            )
        }
        // The tube itself along the bottom: a thin bright line in the lights' colours.
        val strip = 3.dp.toPx()
        val stops = (0..LIGHTS).map { i -> lerp(Color.hsv((lights.hue + i * 48f) % 360f, 0.8f, 1f), tint, 0.12f) }
        val stripAlpha = ((0.35f + 0.5f * energy + 0.3f * beat) * lights.tube * fade).coerceIn(0f, 1f)
        drawRect(
            Brush.horizontalGradient(stops.map { it.copy(alpha = stripAlpha) }),
            topLeft = Offset(0f, h - strip),
            size = Size(w, strip),
            blendMode = BlendMode.Screen,
        )
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, stops[LIGHTS / 2].copy(alpha = stripAlpha * 0.5f)), startY = h - strip * 8, endY = h),
            topLeft = Offset(0f, h - strip * 8),
            size = Size(w, strip * 8),
            blendMode = BlendMode.Screen,
        )
    }
}

/** The lights' state from frame to frame: how bright, which colours, and which are flickering. */
private class Lights {
    var energy = 0f
    var beat = 0f
    var hue = 300f
    var time = 0f
    var fade = 0f
    val flicker = FloatArray(LIGHTS) { 1f }
    var tube = 1f

    private var peak = 0.05f
    private var average = 0f
    private var sinceBeat = 1f
    private val dropUntil = FloatArray(LIGHTS)
    private val dropDepth = FloatArray(LIGHTS) { 1f }
    private var tubeUntil = 0f
    private val random = Random(7)

    fun step(level: AudioLevels.Level?, dt: Float, playing: Boolean, still: Boolean) {
        time += dt
        fade += ((if (playing) 1f else 0f) - fade) * (dt * 2.5f).coerceAtMost(1f)
        // Bass, measured against the song's own recent loudest, so quiet and loud songs both move.
        val raw = level?.let { it.bass * 0.75f + it.level * 0.25f } ?: 0f
        peak = max(raw, peak * (1f - dt * 0.35f)).coerceAtLeast(0.02f)
        val norm = (raw / peak).coerceIn(0f, 1f)
        val follow = if (norm > energy) 0.55f else 0.12f
        energy += (norm - energy) * follow
        average += (norm - average) * (dt * 1.5f).coerceAtMost(1f)
        sinceBeat += dt
        if (!still && norm > 0.55f && norm > average * 1.3f && sinceBeat > 0.22f) {
            beat = 1f
            sinceBeat = 0f
        }
        beat *= (1f - dt * 5f).coerceIn(0f, 1f)
        // The colours drift on their own and hurry a little when the music's loud.
        hue = (hue + dt * (8f + 40f * energy)) % 360f
        if (still) {
            flicker.fill(1f)
            tube = 1f
            return
        }
        // Now and then a light cuts out for a moment, more often on a beat; sometimes twice.
        for (i in 0 until LIGHTS) {
            if (time < dropUntil[i]) {
                flicker[i] += (dropDepth[i] - flicker[i]) * 0.6f
            } else {
                flicker[i] += (1f - flicker[i]) * 0.35f
                val chance = dt * (0.25f + 2.2f * beat)
                if (random.nextFloat() < chance) {
                    dropUntil[i] = time + 0.04f + random.nextFloat() * 0.09f
                    dropDepth[i] = 0.1f + random.nextFloat() * 0.4f
                    if (random.nextFloat() < 0.3f) dropUntil[i] += 0.12f
                }
            }
        }
        if (time < tubeUntil) {
            tube += (0.15f - tube) * 0.7f
        } else {
            tube += (1f - tube) * 0.4f
            if (random.nextFloat() < dt * (0.15f + 1.2f * beat)) tubeUntil = time + 0.05f + random.nextFloat() * 0.06f
        }
    }
}
