package com.opentune.ui.components

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * A fine grain laid over a soft gradient so it never shows steps. A wide,
 * slow gradient between two dark colours has fewer shades than it has pixels,
 * so 8-bit screens draw it as visible bands; a little noise, too fine to see,
 * breaks the bands up. The noise is one small tile, made once and repeated.
 */
object Dither {
    private const val SIDE = 96
    private val tile: ImageBitmap by lazy {
        val random = java.util.Random(7)
        val px = IntArray(SIDE * SIDE) {
            // Mid grey, a shade either way: overlaid, it lightens and darkens by under one step.
            val v = (128 + random.nextGaussian() * 22).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        android.graphics.Bitmap.createBitmap(px, SIDE, SIDE, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    private val brush by lazy { ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)) }

    /** Grain over everything drawn so far in this scope. */
    fun DrawScope.drawDither(strength: Float = 0.06f) {
        drawRect(brush, alpha = strength, blendMode = BlendMode.Overlay)
    }
}
