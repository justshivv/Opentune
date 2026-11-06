package com.opentune.ui.player

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.opentune.playback.AudioLevels
import kotlin.math.cos
import kotlin.math.exp
import kotlin.random.Random
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The music as light, filling the screen with every button gone. The picture is generated,
 * not drawn: see [TripDesign]. Every time Trip mode opens, every half minute or so, and when
 * the music surges, a new design is made up and the screen melts into it; "New look" asks for
 * one at once. The bass breathes through whichever is showing and the kick drum sends a pulse
 * through it, both heard from the song as it plays (see [TripSignal]). Back, or a tap and
 * Exit, leaves.
 */
@Composable
internal fun TripMode(title: String, artist: String, artworkUrl: String?, onExit: () -> Unit, forceSimple: Boolean = false, seed: Long? = null) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The whole screen: no system bars, and it stays on while it plays.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let { w ->
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose { }
        }
        DisposableEffect(Unit) {
            AudioLevels.hold()
            onDispose { AudioLevels.release() }
        }
        // Some designs are built round the cover's main colour, so the picture often suits the song.
        val palette = com.opentune.ui.theme.rememberArtworkPalette(artworkUrl)
        val anchor = remember(palette) { palette.firstOrNull()?.let(::hueOf) }
        val anchorNow = rememberUpdatedState(anchor)
        val signal = remember { TripSignal() }
        val stage = remember { TripStage(if (seed != null) Random(seed) else Random(System.nanoTime())) { anchorNow.value } }
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                signal.step(AudioLevels.at(System.nanoTime()), dt)
                // Time runs a little faster when the music is loud, so it flows with the energy.
                stage.step(dt, 0.6f + signal.level * 0.8f, signal.surged)
            }
        }
        var hint by remember { mutableStateOf(true) }
        LaunchedEffect(hint) { if (hint) { delay(3_500); hint = false } }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { hint = !hint }) },
        ) {
            // The shader on Android 13 and up; if a phone's shader compiler won't take it, the simpler one.
            val shader = remember(forceSimple) {
                if (forceSimple || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null
                else runCatching { android.graphics.RuntimeShader(TRIP_SHADER) }.getOrNull()
            }
            if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ShaderTrip(shader, stage, signal, Modifier.fillMaxSize())
            } else {
                SimpleTrip(stage, signal, Modifier.fillMaxSize())
            }
            AnimatedVisibility(hint, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.padding(bottom = 48.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                    if (artist.isNotBlank()) Text(artist, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.TextButton(onClick = { stage.change() }) { Text("New look", color = Color.White) }
                        androidx.compose.material3.TextButton(onClick = onExit) { Text("Exit trip", color = Color.White) }
                    }
                }
            }
        }
    }
}

/** A colour's place on the colour wheel, 0 to 1, as the shader counts it. */
internal fun hueOf(color: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv[0] / 360f
}

/**
 * What's showing: a design and its clock, and while one is replacing it, the next with its own.
 * Each design runs on a clock that starts at zero when it is made, so the numbers the shader
 * sees stay small however long the screen has been on. [step] is called every frame.
 */
@Stable
internal class TripStage(private val random: Random, private val anchor: () -> Float?) {
    var a by mutableStateOf(TripDesigns.generate(random, anchor())); private set
    var b by mutableStateOf<TripDesign?>(null); private set
    /** How far the screen has melted from [a] to [b], 0 to 1. */
    var mix by mutableFloatStateOf(0f); private set
    var timeA by mutableFloatStateOf(0f); private set
    var timeB by mutableFloatStateOf(0f); private set
    private var sinceChange = 0f
    private var changeAfter = gap()

    fun step(dt: Float, pace: Float, surged: Boolean) {
        timeA += dt * pace
        val next = b
        if (next != null) {
            timeB += dt * pace
            mix += dt / FADE_S
            if (mix >= 1f) { a = next; timeA = timeB; b = null; mix = 0f }
        } else {
            sinceChange += dt
            // A surge in the music brings the next look sooner, but not on top of one that has just arrived.
            if (sinceChange >= changeAfter || (surged && sinceChange > SURGE_AFTER_S)) change()
        }
    }

    /** Makes up another design and starts melting into it; one still on its way in is taken as shown. */
    fun change() {
        b?.let { a = it; timeA = timeB }
        b = TripDesigns.generate(random, anchor())
        timeB = 0f; mix = 0f; sinceChange = 0f; changeAfter = gap()
    }

    private fun gap() = MIN_GAP_S + random.nextFloat() * (MAX_GAP_S - MIN_GAP_S)

    private companion object {
        const val FADE_S = 4f
        const val MIN_GAP_S = 30f
        const val MAX_GAP_S = 55f
        const val SURGE_AFTER_S = 12f
    }
}

/**
 * The music, read for the visuals once a frame: how loud it is, the bass (quick to rise,
 * slow to fall, so it swells rather than flickers), the kick, which jumps to 1 when the
 * bass leaps well above its recent level and dies away over a fraction of a second, and a
 * surge, when the whole sound rises well above where it has been (a drop, a chorus coming in).
 */
internal class TripSignal {
    var level by mutableFloatStateOf(0f); private set
    var bass by mutableFloatStateOf(0f); private set
    var kick by mutableFloatStateOf(0f); private set
    var surged by mutableStateOf(false); private set
    private var bassAverage = 0.05f
    private var levelAverage = 0.2f
    private var sinceKick = 1f

    fun step(heard: AudioLevels.Level?, dt: Float) {
        val l = ((heard?.level ?: 0f) * 3.2f).coerceIn(0f, 1f)
        val b = ((heard?.bass ?: 0f) * 4.5f).coerceIn(0f, 1.2f)
        level += (l - level) * (1f - exp(-dt * 8f))
        bass += (b - bass) * (1f - exp(-dt * if (b > bass) 18f else 4f))
        bassAverage += (b - bassAverage) * (1f - exp(-dt * 1.2f))
        levelAverage += (l - levelAverage) * (1f - exp(-dt * 0.15f))
        surged = level > 0.5f && level > levelAverage * 1.6f + 0.1f
        sinceKick += dt
        if (b > bassAverage * 1.4f && b > 0.12f && sinceKick > 0.22f) {
            kick = 1f
            sinceKick = 0f
        } else {
            kick *= exp(-dt * 5f)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderTrip(shader: android.graphics.RuntimeShader, stage: TripStage, signal: TripSignal, modifier: Modifier) {
    Canvas(modifier) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("bass", signal.bass)
        shader.setFloatUniform("level", signal.level)
        shader.setFloatUniform("kick", signal.kick)
        val a = stage.a
        val b = stage.b ?: a
        shader.setFloatUniform("timeA", stage.timeA)
        shader.setFloatUniform("timeB", stage.timeB)
        shader.setFloatUniform("mixAmt", if (stage.b == null) 0f else stage.mix)
        for (i in 0 until TripDesign.VECS) {
            shader.setFloatUniform(A_NAMES[i], a.values[i * 4], a.values[i * 4 + 1], a.values[i * 4 + 2], a.values[i * 4 + 3])
            shader.setFloatUniform(B_NAMES[i], b.values[i * 4], b.values[i * 4 + 1], b.values[i * 4 + 2], b.values[i * 4 + 3])
        }
        drawRect(ShaderBrush(shader))
    }
}

private val A_NAMES = Array(TripDesign.VECS) { "a$it" }
private val B_NAMES = Array(TripDesign.VECS) { "b$it" }

/** For phones before Android 13: soft colour fields in the design's colours, swelling with the bass, a ring on each kick. */
@Composable
private fun SimpleTrip(stage: TripStage, signal: TripSignal, modifier: Modifier) {
    Canvas(modifier) {
        val t = stage.timeA
        val hue = stage.a.hue * 360f
        val colors = List(3) { Color.hsv((hue + it * 120f) % 360f, 0.9f, 1f) }
        val center = Offset(size.width / 2f, size.height / 2f)
        val reach = minOf(size.width, size.height)
        for (i in 0 until 6) {
            val phase = i * 1.0472f
            val r = reach * (0.26f + 0.18f * signal.bass)
            val at = center + Offset(
                cos(t * (0.5f + i * 0.07f) + phase) * reach * 0.32f,
                sin(t * (0.37f + i * 0.05f) + phase * 1.3f) * reach * 0.45f,
            )
            val c = colors[i % colors.size]
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.85f), c.copy(alpha = 0f)), at, r), r, at)
        }
        if (signal.kick > 0.02f) {
            val ring = reach * (0.15f + (1f - signal.kick) * 0.6f)
            drawCircle(Color.White.copy(alpha = signal.kick * 0.6f), ring, center, style = Stroke(6.dp.toPx() * signal.kick))
        }
    }
}

/**
 * Two kinds of picture, chosen by the design's first number, with all their proportions taken
 * from the design (a0 to a6, and b0 to b6 for the design being melted into): vec4s p0 to p3
 * are the settings and l0 to l2 the lobes of a fluid.
 *
 * Kaleidoscope: the plane is folded into mirrored slices round the middle (p0.y of them), the
 * distance from the centre is warped by a petal outline (p0.z far, with a finer ripple on it),
 * and the warped distance is cut into rings of colour that run out from the middle (p0.w rings;
 * p1.x/p1.y say where on the colour wheel, p1.z/p1.w how the colour is spread and edged).
 *
 * Fluid: each lobe (x, y, size, hue) is a bright core falling off with distance, twisted into a
 * spiral (p3.w) with p2.y arms, over a faint filament warp (p3.x/p3.y), white where it is
 * densest, on a dark ground tinted by the first and last lobe's colours.
 *
 * The bass breathes through either, the kick adds a pulse, the level a little brightness.
 */
private const val TRIP_SHADER = """
uniform float2 res;
uniform float timeA;
uniform float timeB;
uniform float mixAmt;
uniform float bass;
uniform float level;
uniform float kick;
uniform float4 a0;
uniform float4 a1;
uniform float4 a2;
uniform float4 a3;
uniform float4 a4;
uniform float4 a5;
uniform float4 a6;
uniform float4 b0;
uniform float4 b1;
uniform float4 b2;
uniform float4 b3;
uniform float4 b4;
uniform float4 b5;
uniform float4 b6;

float3 hsv(float h, float s, float v) {
    float3 p = abs(mod(fract(h) * 6.0 + float3(5.0, 3.0, 1.0), 6.0) - 3.0);
    return v * (1.0 - s * clamp(p - 1.0, 0.0, 1.0));
}

float3 kaleido(float2 uv, float t, float4 p0, float4 p1, float4 p2, float4 p3) {
    float r = length(uv);
    float folds = p0.y;
    float ang = atan(uv.y, uv.x) + t * p2.x + p3.x * 0.35 * sin(r * p3.y - t * 0.5);
    float seg = 6.2831853 / folds;
    float a = abs(mod(ang, seg) - seg * 0.5);
    float shape = (cos(a * folds) + p2.z * cos(a * folds * p2.y)) / (1.0 + p2.z);
    float amp = p0.z * exp(-r * 0.7) * (0.85 + 0.35 * bass);
    float d = r * (1.0 - amp * shape);
    float tt = d * p0.w * (1.0 + 0.3 * bass) - t * p3.z - kick * 0.28;
    float tri = abs(fract(tt) * 2.0 - 1.0);
    float3 col = hsv(p1.x + p1.y * pow(max(tri, 0.0001), p1.z), 0.93, 1.0);
    float seam = smoothstep(0.0, p1.w, tri) * smoothstep(0.0, p1.w, 1.0 - tri);
    col *= 0.66 + 0.34 * seam;
    col += exp(-r * 8.0) * (p2.w + 0.5 * kick);
    col *= 0.96 + 0.12 * level;
    col *= 1.0 - 0.45 * smoothstep(0.85, 1.5, r);
    return col;
}

float3 lobe(float2 q, float t, float4 l, float4 p1, float4 p2, float4 p3, float boost) {
    if (l.z <= 0.0) return float3(0.0);
    float2 c = l.xy + 0.04 * float2(sin(t * 0.5 + l.x * 7.0), sin(t * 0.43 + l.y * 5.0));
    float2 dp = q - c;
    float dist = length(dp);
    float th = p3.w * exp(-dist * 5.0) * (1.0 + 0.4 * bass) + t * 0.25 * sign(p3.w);
    float cs = cos(th);
    float sn = sin(th);
    float2 rp = float2(dp.x * cs - dp.y * sn, dp.x * sn + dp.y * cs);
    float ang = atan(rp.y, rp.x);
    float arms = 0.5 + 0.5 * sin(ang * p2.y + dist * 16.0 - t * 0.9 * p3.z);
    float f = boost * l.z / (dist * dist + l.z * 0.16) * (0.60 + 0.60 * arms);
    float body = smoothstep(0.55, 1.0, f);
    float rim = exp(-(f - 0.80) * (f - 0.80) * 18.0);
    float3 interior = hsv(l.w + p1.y * (arms + 0.5 * sin(dist * 22.0 - t * 0.5)), 0.88, 1.0);
    float hot = smoothstep(2.4, 4.2, f);
    float3 c3 = interior * body * (1.0 - 0.55 * hot) + 0.85 * hot;
    c3 += hsv(l.w + 0.05, 0.55, 1.0) * rim * 0.8;
    c3 += hsv(l.w, 0.55, 1.0) * clamp(f * 0.30, 0.0, 0.40) * (1.0 - body);
    return c3;
}

float3 fluid(float2 uv, float t, float4 p0, float4 p1, float4 p2, float4 p3, float4 l0, float4 l1, float4 l2) {
    float2 q = uv;
    for (int i = 3; i < 9; i++) {
        float fi = float(i);
        float amp = p3.x / sqrt(fi);
        q += amp * float2(sin(q.y * fi * p3.y * 0.5 + t * 0.40 * p3.z + fi * 1.3 + bass * 0.8),
                          cos(q.x * fi * p3.y * 0.46 - t * 0.34 * p3.z + fi * 1.9));
    }
    float boost = 1.0 + 0.25 * bass + 0.40 * kick;
    float lastHue = p0.y > 2.5 ? l2.w : l1.w;
    float3 ground = mix(hsv(l0.w, 0.40, 0.26), hsv(lastHue, 0.40, 0.30), smoothstep(-0.35, 0.35, uv.y)) * 0.8;
    float3 col = ground + lobe(q, t, l0, p1, p2, p3, boost) + lobe(q, t, l1, p1, p2, p3, boost) + lobe(q, t, l2, p1, p2, p3, boost);
    col *= 0.97 + 0.12 * level;
    col *= 1.0 - 0.30 * smoothstep(0.8, 1.5, length(uv));
    return col;
}

float3 scene(float2 uv, float t, float4 p0, float4 p1, float4 p2, float4 p3, float4 l0, float4 l1, float4 l2) {
    if (p0.x < 0.5) return kaleido(uv, t, p0, p1, p2, p3);
    return fluid(uv, t, p0, p1, p2, p3, l0, l1, l2);
}

half4 main(float2 fc) {
    float2 uv = (fc - 0.5 * res) / min(res.x, res.y);
    float3 col = scene(uv, timeA, a0, a1, a2, a3, a4, a5, a6);
    if (mixAmt > 0.001) {
        float3 other = scene(uv, timeB, b0, b1, b2, b3, b4, b5, b6);
        col = mix(col, other, smoothstep(0.0, 1.0, mixAmt));
    }
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""
