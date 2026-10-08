package com.opentune.ui.wrapped

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import com.opentune.data.DebugLog as Log
import com.opentune.data.history.Wrapped
import com.opentune.ui.share.ShareCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Wrapped summary as a story-sized picture to share: a cream page with
 * a low terracotta sun, the year in a serif, the top artist's photo, the
 * five artists and five songs, the minutes and what kind of listener.
 */
object WrappedPoster {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun share(context: Context, summary: Wrapped.Summary, title: String) {
        scope.launch {
            val bitmap = runCatching {
                val urls = listOf(summary.topArtists.firstOrNull()?.thumbnailUrl) + summary.topSongs.take(5).map { it.thumbnailUrl }
                val images = urls.map { u -> async { u?.let { ShareCard.loadImage(context, it, 400) } } }.awaitAll()
                withContext(Dispatchers.Default) { draw(summary, title, images.first(), images.drop(1)) }
            }.onFailure { Log.w("WrappedPoster", "couldn't make the poster", it) }.getOrNull()
            ShareCard.sendImage(context, bitmap, "OpenTune-Wrapped.png", "My $title Wrapped on OpenTune", "Wrapped")
        }
    }

    /** The poster, [WIDTH] by [HEIGHT]. [songCovers] go with the top songs in order; any may be null. */
    fun draw(summary: Wrapped.Summary, title: String, artistPhoto: Bitmap?, songCovers: List<Bitmap?>): Bitmap {
        val look = LOOKS[0]
        val field = look.field.toArgb()
        val sun = look.shape.toArgb()
        val moon = look.accent.toArgb()
        val ink = look.ink.toArgb()
        val soft = (ink and 0x00FFFFFF) or (0x99 shl 24)
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(field)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // The low sun, and a small moon above it.
        paint.color = sun
        c.drawCircle(w * 0.93f, h * 1.04f, w * 0.52f, paint)
        paint.color = moon
        c.drawCircle(w * 0.5f, h * 0.83f, w * 0.03f, paint)

        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        fun text(size: Float, color: Int, face: Typeface = Typeface.DEFAULT, spacing: Float = 0f) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = face; letterSpacing = spacing }
        val left = w * 0.083f
        val eyebrow = text(w * 0.022f, soft, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 0.2f)

        c.drawText("OPENTUNE", left, h * 0.075f, eyebrow)
        c.drawText("My", left, h * 0.14f, text(w * 0.1f, ink, serif, -0.02f))
        c.drawText(title, left, h * 0.2f, text(w * 0.13f, sun, serif, -0.02f))
        c.drawText("Wrapped", left, h * 0.26f, text(w * 0.1f, ink, serif, -0.02f))

        // The top artist: a round photo and the name beside it.
        val top = summary.topArtists.firstOrNull()
        val photo = w * 0.22f
        val photoTop = h * 0.3f
        val photoRect = RectF(left, photoTop, left + photo, photoTop + photo)
        if (artistPhoto != null) {
            c.save()
            c.clipPath(Path().apply { addOval(photoRect, Path.Direction.CW) })
            c.drawBitmap(ShareCard.centerSquare(artistPhoto), null, photoRect, image)
            c.restore()
        } else {
            paint.color = (ink and 0x00FFFFFF) or (0x22 shl 24)
            c.drawOval(photoRect, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.003f
        paint.color = ink
        c.drawOval(RectF(photoRect.left - w * 0.012f, photoRect.top - w * 0.012f, photoRect.right + w * 0.012f, photoRect.bottom + w * 0.012f), paint)
        paint.style = Paint.Style.FILL
        val nameX = photoRect.right + w * 0.05f
        c.drawText("TOP ARTIST", nameX, photoTop + photo * 0.38f, eyebrow)
        val nameMax = w - nameX - left
        val namePaint = text(w * 0.06f, ink, serif, -0.02f)
        c.drawText(TextUtils.ellipsize(top?.title.orEmpty(), namePaint, nameMax, TextUtils.TruncateAt.END).toString(), nameX, photoTop + photo * 0.66f, namePaint)
        top?.let { c.drawText("${it.plays} plays", nameX, photoTop + photo * 0.86f, text(w * 0.026f, soft)) }

        // Five artists and five songs, side by side.
        val listTop = photoTop + photo + h * 0.06f
        val col = (w - left * 2) / 2f
        c.drawText("TOP ARTISTS", left, listTop, eyebrow)
        c.drawText("TOP SONGS", left + col, listTop, eyebrow)
        val number = text(w * 0.034f, sun, serif)
        val item = text(w * 0.03f, ink, Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
        val sub = text(w * 0.022f, soft)
        val rowH = h * 0.052f
        summary.topArtists.take(5).forEachIndexed { i, e ->
            val y = listTop + rowH * (i + 1)
            c.drawText("${i + 1}", left, y, number)
            c.drawText(TextUtils.ellipsize(e.title, item, col - w * 0.1f, TextUtils.TruncateAt.END).toString(), left + w * 0.05f, y, item)
        }
        summary.topSongs.take(5).forEachIndexed { i, e ->
            val y = listTop + rowH * (i + 1)
            val coverSide = rowH * 0.78f
            val r = RectF(left + col, y - coverSide * 0.78f, left + col + coverSide, y + coverSide * 0.22f)
            val cover = songCovers.getOrNull(i)
            if (cover != null) {
                c.save()
                c.clipPath(Path().apply { addRoundRect(r, coverSide * 0.12f, coverSide * 0.12f, Path.Direction.CW) })
                c.drawBitmap(ShareCard.centerSquare(cover), null, r, image)
                c.restore()
            }
            val tx = r.right + w * 0.02f
            val max = w - left - tx
            c.drawText(TextUtils.ellipsize(e.title, item, max, TextUtils.TruncateAt.END).toString(), tx, y - rowH * 0.12f, item)
            c.drawText(TextUtils.ellipsize(e.subtitle, sub, max, TextUtils.TruncateAt.END).toString(), tx, y + rowH * 0.22f, sub)
        }

        // The minutes, large, and what kind of listener.
        val statsTop = listTop + rowH * 6.4f
        c.drawText("MINUTES", left, statsTop, eyebrow)
        c.drawText("%,d".format(summary.minutes), left, statsTop + h * 0.07f, text(w * 0.12f, ink, serif, -0.02f))
        c.drawText("LISTENER", left, statsTop + h * 0.12f, eyebrow)
        c.drawText(summary.clock.title, left, statsTop + h * 0.165f, text(w * 0.065f, ink, serif, -0.02f))
        return out
    }
}
