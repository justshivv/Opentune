// The desktop's Liquid Glass, as the web player draws it (web/app/src/lib/liquidGlass.ts and the
// .glass-pill[data-liquid] rules): what's behind is bent strongly at the rim, as through a thick
// rounded slab, only lightly blurred, its colours lifted, under a thin film with light across the
// top and a rim lit brightest at the top left. See [liquidGlass].
package com.opentune.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeArea
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Glass that bends what's behind it. It draws the areas the window records for its frosted glass
 * (Haze's sources: the window's backdrop, the page) through a lens at the rim, then a light blur
 * and lifted colours, over which go [film], a soft light across the top and a lit rim.
 *
 * An area being recorded right now is the one this glass sits in (a button on the page): that
 * one is left out, so the glass shows what's under the page there, as the frosted glass does.
 * Haze's own effect node rides along, drawing nothing, so this is redrawn whenever what's behind
 * it changes, as the frosted glass is.
 */
@Composable
internal fun Modifier.liquidGlass(haze: HazeState, shape: Shape, film: Color): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    var at by remember { mutableStateOf(Offset.Zero) }
    val lens = remember { LensCache() }
    androidx.compose.runtime.DisposableEffect(lens) { onDispose { lens.drop() } }
    return this
        .onGloballyPositioned { at = it.positionOnScreen() }
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }
            clipPath(clip) {
                drawBent(haze, at, cornerOf(outline), lens)
                drawRect(film)
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.16f), 0.45f to Color.Transparent))
            }
        }
        .hazeEffect(haze) { alpha = 0f }
        .then(
            // A lit rim on rounded glass; a bar's straight edges (the top bar) keep the faint hairline.
            if (shape == androidx.compose.ui.graphics.RectangleShape) Modifier.border(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
            else Modifier.border(
                1.dp,
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.55f else 0.9f),
                    0.5f to Color.White.copy(alpha = if (dark) 0.07f else 0.18f),
                    1f to Color.White.copy(alpha = if (dark) 0.28f else 0.55f),
                ),
                shape,
            ),
        )
}

/** The corner radius of [outline], in pixels: a rounded shape's, or none for a plain rectangle. */
private fun cornerOf(outline: Outline): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
    is Outline.Generic -> minOf(outline.path.getBounds().width, outline.path.getBounds().height) / 2f
    else -> 0f
}

/**
 * One glass's lens filter and paint, made once and again only when its size or corners change.
 * Made afresh on every draw, a window of glass redrawing as the page scrolled left a trail of
 * native Skia objects behind it, and many of them crashed Skia.
 *
 * The ones it no longer needs are let go, never closed by hand: Skia frees each through its own
 * cleaner once nothing holds it. Closed at once, a frame still being drawn could reach a freed
 * filter, and the app crashed outright. On Windows that happened when the window's material was
 * changed, as the frame changing size had every glass make new ones at the same moment.
 */
private class LensCache {
    private var key: Long = 0
    var paint: org.jetbrains.skia.Paint? = null
        private set
    // Held so they live as long as the paint built from them.
    private val parts = mutableListOf<org.jetbrains.skia.impl.Managed>()

    fun paintFor(w: Float, h: Float, corner: Float, bezel: Float, sigma: Float): org.jetbrains.skia.Paint {
        val k = (w.toInt().toLong() shl 40) xor (h.toInt().toLong() shl 20) xor (corner.toInt().toLong() shl 8) xor bezel.toInt().toLong()
        paint?.let { if (k == key) return it }
        drop()
        key = k
        val builder = RuntimeShaderBuilder(LENS).apply {
            uniform("size", w, h)
            uniform("radius", minOf(corner, w / 2f, h / 2f))
            uniform("bezel", bezel)
            uniform("depth", bezel)
            uniform("aberration", 0.12f)
        }
        val bent = ImageFilter.makeRuntimeShader(builder, "content", null)
        val blurred = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP, bent, null)
        val lifted = ImageFilter.makeColorFilter(LIFT, blurred, null)
        val made = org.jetbrains.skia.Paint().apply { imageFilter = lifted }
        parts += listOf(builder, bent, blurred, lifted, made)
        paint = made
        return made
    }

    /** Lets go of the filters and paint, for Skia's cleaner to free once no frame draws with them. */
    fun drop() {
        parts.clear()
        paint = null
    }
}

/** What's behind, from the window's recorded areas, through the lens, the blur and the colour lift. */
private fun DrawScope.drawBent(haze: HazeState, at: Offset, corner: Float, lens: LensCache) {
    val w = size.width
    val h = size.height
    // Nothing to bend on glass with no area (a hidden or collapsed one).
    if (w < 1f || h < 1f) return
    val areas = haze.areas.filter { it.contentLayer != null && !it.drawingNow() }.sortedBy { it.zIndex }
    if (areas.isEmpty()) return
    val bezel = minOf(22.dp.toPx(), w / 2f, h / 2f)
    val paint = lens.paintFor(w, h, corner, bezel, 5.dp.toPx() / 2f)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.saveLayer(org.jetbrains.skia.Rect.makeWH(w, h), paint)
        for (area in areas) {
            val layer = area.contentLayer ?: continue
            translate(area.positionOnScreen.x - at.x, area.positionOnScreen.y - at.y) { drawLayer(layer) }
        }
        native.restore()
    }
}

/** Whether Haze is recording [this] area at this moment: the glass is inside it. */
private fun HazeArea.drawingNow(): Boolean = runCatching { DRAWING?.invoke(this) as? Boolean }.getOrNull() ?: false

private val DRAWING = runCatching { HazeArea::class.java.methods.firstOrNull { it.name.startsWith("getContentDrawing") } }.getOrNull()

/**
 * The lens: within [bezel] of the rounded rim, the backdrop is sampled from further in along the
 * rim's normal on a circular profile (barely at the bezel's inner side, steeply at the edge), as
 * the web player's displacement map does; red and blue a little apart where it bends most.
 */
private val LENS = RuntimeEffect.makeForShader(
    """
    uniform shader content;
    uniform float2 size;
    uniform float radius;
    uniform float bezel;
    uniform float depth;
    uniform float aberration;

    half4 main(float2 p) {
        float2 h = size * 0.5;
        float2 d = p - h;
        float r = min(radius, min(h.x, h.y));
        float2 q = abs(d) - (h - r);
        float inside;
        float2 n;
        if (q.x > 0.0 && q.y > 0.0) {
            float l = max(length(q), 0.001);
            inside = r - l;
            n = (q / l) * sign(d);
        } else if (q.x > q.y) {
            inside = r - q.x;
            n = float2(sign(d.x), 0.0);
        } else {
            inside = r - q.y;
            n = float2(0.0, sign(d.y));
        }
        float t = clamp(1.0 - inside / max(bezel, 1.0), 0.0, 1.0);
        float2 off = n * (1.0 - sqrt(1.0 - t * t)) * depth;
        half4 c = content.eval(p - off);
        c.r = content.eval(p - off * (1.0 + aberration)).r;
        c.b = content.eval(p - off * (1.0 - aberration)).b;
        return c;
    }
    """.trimIndent(),
)

/** Saturation 1.8 and a touch brighter, as the web player's backdrop-filter. */
private val LIFT: ColorFilter = run {
    val s = 1.8f
    val (lr, lg, lb) = Triple(0.2126f, 0.7152f, 0.0722f)
    val b = 1.04f
    ColorFilter.makeMatrix(
        ColorMatrix(
            (lr * (1 - s) + s) * b, lg * (1 - s) * b, lb * (1 - s) * b, 0f, 0f,
            lr * (1 - s) * b, (lg * (1 - s) + s) * b, lb * (1 - s) * b, 0f, 0f,
            lr * (1 - s) * b, lg * (1 - s) * b, (lb * (1 - s) + s) * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
