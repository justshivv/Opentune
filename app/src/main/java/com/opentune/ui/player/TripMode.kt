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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The music as light, filling the screen with every button gone: liquid colour in the
 * cover's own palette, folded into a kaleidoscope, swirling with the music. The bass
 * draws it in and the kick drum sends a ring out, both heard from the song as it plays
 * (see [TripSignal]), so it moves on the beat. Back, or a tap and Exit, leaves.
 */
@Composable
internal fun TripMode(title: String, artist: String, artworkUrl: String?, onExit: () -> Unit, forceSimple: Boolean = false) {
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
        val palette = com.opentune.ui.theme.rememberArtworkPalette(artworkUrl)
        val colors = remember(palette) { tripColors(palette) }
        val signal = remember { TripSignal() }
        var time by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                signal.step(AudioLevels.at(System.nanoTime()), dt)
                // Time runs a little faster when the music is loud, so it flows with the energy.
                time += dt * (0.6f + signal.level * 0.8f)
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
                ShaderTrip(shader, colors, signal, { time }, Modifier.fillMaxSize())
            } else {
                SimpleTrip(colors, signal, { time }, Modifier.fillMaxSize())
            }
            AnimatedVisibility(hint, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.padding(bottom = 48.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                    if (artist.isNotBlank()) Text(artist, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1)
                    androidx.compose.material3.TextButton(onClick = onExit, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Exit trip", color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * The music, read for the visuals once a frame: how loud it is, the bass (quick to rise,
 * slow to fall, so it swells rather than flickers) and the kick, which jumps to 1 when the
 * bass leaps well above its recent level and dies away over a fraction of a second.
 */
internal class TripSignal {
    var level by mutableFloatStateOf(0f); private set
    var bass by mutableFloatStateOf(0f); private set
    var kick by mutableFloatStateOf(0f); private set
    private var bassAverage = 0.05f
    private var sinceKick = 1f

    fun step(heard: AudioLevels.Level?, dt: Float) {
        val l = ((heard?.level ?: 0f) * 3.2f).coerceIn(0f, 1f)
        val b = ((heard?.bass ?: 0f) * 4.5f).coerceIn(0f, 1.2f)
        level += (l - level) * (1f - exp(-dt * 8f))
        bass += (b - bass) * (1f - exp(-dt * if (b > bass) 18f else 4f))
        bassAverage += (b - bassAverage) * (1f - exp(-dt * 1.2f))
        sinceKick += dt
        if (b > bassAverage * 1.4f && b > 0.12f && sinceKick > 0.22f) {
            kick = 1f
            sinceKick = 0f
        } else {
            kick *= exp(-dt * 5f)
        }
    }
}

/** Three colours from the cover, bright enough to glow on black; vivid defaults when there's no cover. */
internal fun tripColors(palette: List<Color>): List<Color> {
    val base = palette.ifEmpty { listOf(Color(0xFFFF3D7F), Color(0xFF7C4DFF), Color(0xFF00E5FF)) }
    return List(3) { i ->
        val c = base[i % base.size]
        val hct = com.materialkolor.hct.Hct.fromInt(c.toArgb())
        // A second and third from the first, turned round the wheel, when the cover has only one.
        val hue = if (i < base.size) hct.hue else (hct.hue + 120.0 * i) % 360.0
        Color(com.materialkolor.hct.Hct.from(hue, maxOf(hct.chroma, 55.0), 62.0).toInt())
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderTrip(shader: android.graphics.RuntimeShader, colors: List<Color>, signal: TripSignal, time: () -> Float, modifier: Modifier) {
    Canvas(modifier) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("time", time())
        shader.setFloatUniform("bass", signal.bass)
        shader.setFloatUniform("level", signal.level)
        shader.setFloatUniform("kick", signal.kick)
        fun set(name: String, c: Color) = shader.setFloatUniform(name, c.red, c.green, c.blue, 1f)
        set("c1", colors[0]); set("c2", colors[1]); set("c3", colors[2])
        drawRect(ShaderBrush(shader))
    }
}

/** For phones before Android 13: soft colour fields drifting round, swelling with the bass, a ring on each kick. */
@Composable
private fun SimpleTrip(colors: List<Color>, signal: TripSignal, time: () -> Float, modifier: Modifier) {
    Canvas(modifier) {
        val t = time()
        val center = Offset(size.width / 2f, size.height / 2f)
        val reach = minOf(size.width, size.height)
        for (i in 0 until 6) {
            val phase = i * 1.0472f
            val r = reach * (0.22f + 0.18f * signal.bass)
            val at = center + Offset(
                cos(t * (0.5f + i * 0.07f) + phase) * reach * 0.32f,
                sin(t * (0.37f + i * 0.05f) + phase * 1.3f) * reach * 0.45f,
            )
            val c = colors[i % colors.size]
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f + 0.35f * signal.level), c.copy(alpha = 0f)), at, r), r, at)
        }
        if (signal.kick > 0.02f) {
            val ring = reach * (0.15f + (1f - signal.kick) * 0.6f)
            drawCircle(Color.White.copy(alpha = signal.kick * 0.6f), ring, center, style = Stroke(6.dp.toPx() * signal.kick))
        }
    }
}

/**
 * Liquid colour: noise warped through itself (domain warping), folded into six mirrored
 * slices round the centre, drawn in the cover's colours. The bass zooms it in, the level
 * brightens it, and the kick sends a ring out from the middle.
 */
private const val TRIP_SHADER = """
uniform float2 res;
uniform float time;
uniform float bass;
uniform float level;
uniform float kick;
uniform half4 c1;
uniform half4 c2;
uniform half4 c3;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; }
    return v;
}
half4 main(float2 fc) {
    float2 uv = (fc - 0.5 * res) / min(res.x, res.y);
    float r = length(uv);
    float ang = atan(uv.y, uv.x) + time * 0.05;
    float seg = 6.2831853 / 6.0;
    ang = mod(ang, seg);
    ang = abs(ang - seg * 0.5);
    float2 k = float2(cos(ang), sin(ang)) * r;
    float t = time * 0.18;
    float zoom = 1.8 - 0.45 * bass - 0.3 * kick;
    float2 q = float2(fbm(k * zoom + t), fbm(k * zoom - t + 3.1));
    float2 w = float2(fbm(k * zoom + 2.0 * q + float2(1.7, 9.2) + 0.4 * t + bass * 0.6),
                      fbm(k * zoom + 2.0 * q + float2(8.3, 2.8) - 0.3 * t));
    float f = fbm(k * zoom + 3.0 * w);
    half3 col = mix(c1.rgb, c2.rgb, half(clamp(f * 1.7, 0.0, 1.0)));
    col = mix(col, c3.rgb, half(clamp(length(w) * 0.9 - 0.2 + 0.3 * bass, 0.0, 1.0)));
    float ring = smoothstep(0.06, 0.0, abs(r - (0.12 + (1.0 - kick) * 0.75))) * kick;
    col += half3(ring) * 0.8;
    col *= half(0.5 + 0.65 * level + 0.25 * kick);
    col *= half(1.0 - smoothstep(0.65, 1.35, r));
    return half4(col, 1.0);
}
"""
