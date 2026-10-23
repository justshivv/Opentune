package com.opentune.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/**
 * A picture of a playlist or album to send with its link: its covers blurred
 * into the background, and in the middle a framed collage of up to four of
 * them (or the one cover), with the name and a line under it, and the
 * OpenTune mark and name at the foot, as on the other cards.
 */
object PlaylistCard {
    const val SIZE = ShareCard.SIZE

    fun draw(covers: List<Bitmap>, title: String, subtitle: String?): Bitmap {
        val w = SIZE.toFloat()
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // The background: the first cover, blown up from a few pixels so it blurs softly.
        val first = covers.firstOrNull()
        if (first != null) {
            val side = w * 1.15f
            c.drawBitmap(ShareCard.blurred(ShareCard.centerSquare(first)), null, RectF((w - side) / 2f, (w - side) / 2f, (w + side) / 2f, (w + side) / 2f), image)
        } else {
            c.drawColor(Color.rgb(72, 64, 78))
        }
        // Darker toward the foot, where the words go.
        paint.shader = LinearGradient(0f, 0f, 0f, w, intArrayOf(Color.argb(40, 0, 0, 0), Color.argb(150, 0, 0, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, w, paint)
        paint.shader = null

        // The collage, framed, with a soft shadow under it.
        val side = w * 0.56f
        val top = w * 0.11f
        val frame = RectF((w - side) / 2f, top, (w + side) / 2f, top + side)
        val corner = side * 0.07f
        paint.color = Color.argb(110, 0, 0, 0)
        paint.maskFilter = android.graphics.BlurMaskFilter(side * 0.06f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(frame.left, frame.top + side * 0.03f, frame.right, frame.bottom + side * 0.04f), corner, corner, paint)
        paint.maskFilter = null
        c.save()
        c.clipPath(Path().apply { addRoundRect(frame, corner, corner, Path.Direction.CW) })
        val tiles = covers.distinct().take(4)
        when {
            tiles.size >= 4 -> {
                val half = side / 2f
                for (i in 0 until 4) {
                    val x = frame.left + (i % 2) * half
                    val y = frame.top + (i / 2) * half
                    c.drawBitmap(ShareCard.centerSquare(tiles[i]), null, RectF(x, y, x + half, y + half), image)
                }
            }
            tiles.isNotEmpty() -> c.drawBitmap(ShareCard.centerSquare(tiles[0]), null, frame, image)
            else -> {
                paint.color = Color.rgb(110, 98, 120)
                c.drawRect(frame, paint)
            }
        }
        c.restore()
        // A fine light edge, as on glass.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = side * 0.006f
        paint.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(frame, corner, corner, paint)
        paint.style = Paint.Style.FILL

        // The name, and what it holds, under the collage.
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = w * 0.06f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            textSize = w * 0.036f
            textAlign = Paint.Align.CENTER
        }
        val maxText = w * 0.84f
        val name = TextUtils.ellipsize(title, titlePaint, maxText, TextUtils.TruncateAt.END).toString()
        c.drawText(name, w / 2f, frame.bottom + w * 0.13f, titlePaint)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            val line = TextUtils.ellipsize(it, subPaint, maxText, TextUtils.TruncateAt.END).toString()
            c.drawText(line, w / 2f, frame.bottom + w * 0.195f, subPaint)
        }
        // The mark and name, small and centred at the foot.
        val brandH = w * 0.034f
        ShareCard.drawBrand(c, w / 2f + ShareCard.brandWidth(brandH) / 2f, w * 0.935f, brandH, Color.argb(225, 255, 255, 255))
        return out
    }
}
