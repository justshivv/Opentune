package com.opentune.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
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
import androidx.core.content.FileProvider
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.subsonic.Subsonic
import com.opentune.ui.formatTime
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A picture of a song to send along with its link, drawn like a little
 * now-playing widget: the cover blurred into a square background, and in
 * the middle the cover itself in a dark frame, with the artist's photo and
 * name at the top, the title, the time and the controls over its foot.
 */
object ShareCard {
    private const val TAG = "ShareCard"
    const val SIZE = 1080
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Shares [song]'s link with its card attached; just the link if the card can't be made. */
    fun share(context: Context, song: Song, positionMs: Long = 0, durationMs: Long = 0) {
        val link = "https://music.youtube.com/watch?v=${song.videoId}"
        scope.launch {
            val bitmap = runCatching {
                val cover = loadImage(context, song.thumbnailUrl, SIZE)
                // The artist's own photo for the name tag, if it comes quickly; the cover otherwise.
                val photo = withTimeoutOrNull(ARTIST_PHOTO_WAIT_MS) {
                    runCatching {
                        MusicRepository.artistIdFor(song)?.let { MusicRepository.artist(it).thumbnailUrl }
                            ?.let { loadImage(context, it, 160) }
                    }.getOrNull()
                }
                val duration = durationMs.takeIf { it > 0 } ?: durationOf(song.durationText)
                val liked = LibraryStore.isLiked(song.videoId)
                withContext(Dispatchers.Default) {
                    draw(cover, song.title, song.artist, positionMs, duration, avatar = photo, subtitle = song.albumName, liked = liked)
                }
            }.onFailure { Log.w(TAG, "couldn't make the share card", it) }.getOrNull()
            sendImage(context, bitmap, "OpenTune-${song.videoId}.png", "${song.title} · ${song.artist}\n$link", song.title)
        }
    }
    /**
     * Hands [bitmap] to the share sheet with [text], through the app's
     * FileProvider; just the text if there's no picture or it can't be saved.
     */
    suspend fun sendImage(context: Context, bitmap: Bitmap?, fileName: String, text: String, label: String) {
        val uri = bitmap?.let { b ->
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    // One picture at a time is enough; older ones are cleared away.
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, fileName)
                    file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                }
            }.onFailure { Log.w(TAG, "couldn't save the picture", it) }.getOrNull()
        }
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        if (uri != null) {
            send.setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(label, uri)
        } else {
            send.setType("text/plain")
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    /** A cover or photo as a software bitmap, [size] pixels across, for drawing on a Canvas. */
    suspend fun loadImage(context: Context, thumbnailUrl: String?, size: Int): Bitmap? {
        val url = Subsonic.resolveCover(thumbnailUrl.artworkAt(size), size) ?: return null
        val request = ImageRequest.Builder(context).data(url).size(size).allowHardware(false).build()
        return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    internal fun durationOf(text: String?): Long =
        text?.split(':')?.map { it.trim().toLongOrNull() ?: return 0 }?.fold(0L) { acc, n -> acc * 60 + n }?.times(1000) ?: 0

    /** How tall the card is; it's square. */
    const val HEIGHT = SIZE

    /**
     * The card itself, [SIZE] square: the cover blurred over the whole
     * background, and in the middle the cover in a dark rounded frame. Over
     * the cover's top, a tag with the artist's [avatar] (the cover when
     * there's none), name and [subtitle], and a share button and a heart
     * beside it; over its foot, the title, the time either side of a
     * progress bar, and back, play and forward.
     */
    fun draw(
        cover: Bitmap?,
        title: String,
        artist: String,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean = false,
        avatar: Bitmap? = null,
        subtitle: String? = null,
        liked: Boolean = true,
    ): Bitmap {
        val w = SIZE.toFloat()
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Pictures go down with their own opaque paint; the shapes' paint carries see-through colours.
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // The background: the cover blown up from a few pixels, which blurs it softly.
        if (cover != null) {
            val side = w * 1.12f
            c.drawBitmap(blurred(centerSquare(cover)), null, RectF((w - side) / 2f, (w - side) / 2f, (w + side) / 2f, (w + side) / 2f), image)
        } else {
            c.drawColor(Color.rgb(92, 82, 70))
        }
        c.drawColor(Color.argb(30, 0, 0, 0))

        // The frame: dark glass around the cover, with a soft shadow under it.
        val side = w * 0.6f
        val frame = RectF((w - side) / 2f, (w - side) / 2f, (w + side) / 2f, (w + side) / 2f)
        val outer = side * 0.1f
        paint.color = Color.argb(90, 0, 0, 0)
        paint.maskFilter = android.graphics.BlurMaskFilter(side * 0.05f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(frame.left, frame.top + side * 0.02f, frame.right, frame.bottom + side * 0.03f), outer, outer, paint)
        paint.maskFilter = null
        paint.color = Color.argb(185, 32, 26, 20)
        c.drawRoundRect(frame, outer, outer, paint)
        val border = side * 0.024f
        val card = RectF(frame.left + border, frame.top + border, frame.right - border, frame.bottom - border)
        val inner = outer - border
        val u = card.width()

        c.save()
        c.clipPath(Path().apply { addRoundRect(card, inner, inner, Path.Direction.CW) })
        if (cover != null) {
            c.drawBitmap(centerSquare(cover), null, card, image)
        } else {
            paint.color = Color.rgb(120, 108, 96)
            c.drawRect(card, paint)
            drawNote(c, card.centerX(), card.centerY() - u * 0.08f, u * 0.1f)
        }
        // Shade at the top and the foot so the white reads on any cover.
        paint.shader = LinearGradient(
            0f, card.top, 0f, card.bottom,
            intArrayOf(Color.argb(70, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.28f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(card, paint)
        paint.shader = null
        c.restore()

        val glass = Color.argb(115, 0, 0, 0)
        val white = Color.WHITE
        val soft = Color.argb(170, 255, 255, 255)
        val edge = u * 0.045f

        // The tag: the artist's photo in a circle, the name and the album.
        val tagH = u * 0.12f
        val pad = u * 0.012f
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = u * 0.037f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = u * 0.033f }
        val sub = subtitle?.takeIf { it.isNotBlank() } ?: "OpenTune"
        val textMax = u * 0.36f
        val name = TextUtils.ellipsize(artist, namePaint, textMax, TextUtils.TruncateAt.END).toString()
        val subLine = TextUtils.ellipsize(sub, subPaint, textMax, TextUtils.TruncateAt.END).toString()
        val dot = tagH - pad * 2
        val tag = RectF(card.left + edge * 0.6f, card.top + edge * 0.6f, 0f, card.top + edge * 0.6f + tagH)
        tag.right = tag.left + pad + dot + u * 0.03f + maxOf(namePaint.measureText(name), subPaint.measureText(subLine)) + u * 0.05f
        paint.color = glass
        c.drawRoundRect(tag, tagH / 2f, tagH / 2f, paint)
        val photo = RectF(tag.left + pad, tag.top + pad, tag.left + pad + dot, tag.top + pad + dot)
        val face = avatar ?: cover
        if (face != null) {
            c.save()
            c.clipPath(Path().apply { addOval(photo, Path.Direction.CW) })
            c.drawBitmap(centerSquare(face), null, photo, image)
            c.restore()
        } else {
            paint.color = Color.argb(90, 255, 255, 255)
            c.drawOval(photo, paint)
        }
        val textX = photo.right + u * 0.03f
        c.drawText(name, textX, tag.centerY() - u * 0.008f, namePaint)
        c.drawText(subLine, textX, tag.centerY() + subPaint.textSize * 0.95f, subPaint)

        // A heart in the corner and a share button beside it.
        val round = tagH / 2f
        val heartX = card.right - edge * 0.6f - round
        val shareX = heartX - round * 2f - u * 0.03f
        paint.color = glass
        c.drawCircle(heartX, tag.centerY(), round, paint)
        c.drawCircle(shareX, tag.centerY(), round, paint)
        drawHeart(c, heartX, tag.centerY() + round * 0.04f, round * 0.48f, liked)
        drawShare(c, shareX, tag.centerY(), round * 0.42f)

        // At the foot: the title, the time and the bar, and the controls.
        val controlsY = card.bottom - u * 0.13f
        val barY = controlsY - u * 0.135f
        val barLeft = card.left + edge
        val barRight = card.right - edge
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = u * 0.034f }
        val timeY = barY - u * 0.035f
        val elapsed = formatTime(positionMs)
        c.drawText(elapsed, barLeft + u * 0.02f, timeY, timePaint)
        if (durationMs > 0) {
            val remaining = "-" + formatTime((durationMs - positionMs).coerceAtLeast(0))
            c.drawText(remaining, barRight - u * 0.02f - timePaint.measureText(remaining), timeY, timePaint)
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = u * 0.052f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val t = TextUtils.ellipsize(title, titlePaint, barRight - barLeft, TextUtils.TruncateAt.END).toString()
        c.drawText(t, barLeft, timeY - u * 0.07f, titlePaint)

        val bar = u * 0.02f
        paint.color = Color.argb(95, 255, 255, 255)
        c.drawRoundRect(RectF(barLeft, barY - bar / 2f, barRight, barY + bar / 2f), bar, bar, paint)
        val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (f > 0f) {
            paint.color = white
            c.drawRoundRect(RectF(barLeft, barY - bar / 2f, barLeft + (barRight - barLeft) * f, barY + bar / 2f), bar, bar, paint)
        }

        val button = u * 0.085f
        val step = u * 0.24f
        paint.color = glass
        for (k in -1..1) c.drawCircle(card.centerX() + k * step, controlsY, button, paint)
        drawSkip(c, card.centerX() - step, controlsY, button * 0.38f, forward = false)
        if (playing) drawPause(c, card.centerX(), controlsY, button * 0.4f) else drawPlay(c, card.centerX() + button * 0.06f, controlsY, button * 0.4f)
        drawSkip(c, card.centerX() + step, controlsY, button * 0.38f, forward = true)

        // Under it all, quietly: where it came from.
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = w * 0.022f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.14f }
        val word = "OPENTUNE"
        c.drawText(word, (w - brand.measureText(word)) / 2f, w - w * 0.06f, brand)
        return out
    }

    /**
     * The cover shrunk to a few dozen pixels and box-blurred three times
     * over, which comes out close to a gaussian blur. Stretched back up with
     * filtering it's a smooth wash of the cover's colours, with no blocks.
     */
    internal fun blurred(cover: Bitmap, side: Int = 48, radius: Int = 4): Bitmap {
        val small = Bitmap.createScaledBitmap(cover, side, side, true)
        val px = IntArray(side * side)
        small.getPixels(px, 0, side, 0, 0, side, side)
        val tmp = IntArray(px.size)
        repeat(3) {
            boxPass(px, tmp, side, radius, horizontal = true)
            boxPass(tmp, px, side, radius, horizontal = false)
        }
        return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(src: IntArray, dst: IntArray, side: Int, r: Int, horizontal: Boolean) {
        for (line in 0 until side) {
            for (i in 0 until side) {
                var rs = 0; var gs = 0; var bs = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, side - 1)
                    val p = if (horizontal) src[line * side + j] else src[j * side + line]
                    rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF; n++
                }
                val v = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                if (horizontal) dst[line * side + i] = v else dst[i * side + line] = v
            }
        }
    }

    internal fun centerSquare(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        if (b.width == b.height) return b
        return Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; strokeJoin = Paint.Join.ROUND }

    private fun drawPlay(c: Canvas, x: Float, y: Float, r: Float) {
        val p = Path().apply {
            moveTo(x - r * 0.7f, y - r)
            lineTo(x + r, y)
            lineTo(x - r * 0.7f, y + r)
            close()
        }
        c.drawPath(p, ink)
    }

    private fun drawPause(c: Canvas, x: Float, y: Float, r: Float) {
        val bar = r * 0.5f
        c.drawRoundRect(RectF(x - r * 0.75f, y - r, x - r * 0.75f + bar, y + r), bar * 0.25f, bar * 0.25f, ink)
        c.drawRoundRect(RectF(x + r * 0.75f - bar, y - r, x + r * 0.75f, y + r), bar * 0.25f, bar * 0.25f, ink)
    }

    /** A bar and a triangle, like ⏮ or ⏭. */
    private fun drawSkip(c: Canvas, x: Float, y: Float, r: Float, forward: Boolean) {
        val d = if (forward) 1f else -1f
        val p = Path().apply {
            moveTo(x - d * r * 0.8f, y - r)
            lineTo(x + d * r * 0.55f, y)
            lineTo(x - d * r * 0.8f, y + r)
            close()
        }
        c.drawPath(p, ink)
        val bar = r * 0.32f
        val bx = x + d * (r * 0.62f)
        c.drawRoundRect(RectF(minOf(bx, bx + d * bar), y - r, maxOf(bx, bx + d * bar), y + r), bar * 0.3f, bar * 0.3f, ink)
    }

    /** A heart, filled red when the song is liked and outlined in white when not. */
    private fun drawHeart(c: Canvas, x: Float, y: Float, r: Float, filled: Boolean) {
        val p = Path().apply {
            moveTo(x, y + r * 0.9f)
            cubicTo(x - r * 1.45f, y - r * 0.05f, x - r * 0.8f, y - r * 1.15f, x, y - r * 0.42f)
            cubicTo(x + r * 0.8f, y - r * 1.15f, x + r * 1.45f, y - r * 0.05f, x, y + r * 0.9f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (filled) {
                color = Color.rgb(232, 68, 82)
            } else {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = r * 0.2f
                strokeJoin = Paint.Join.ROUND
            }
        }
        c.drawPath(p, paint)
    }

    /** An open box with an arrow up out of it. */
    private fun drawShare(c: Canvas, x: Float, y: Float, r: Float) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = r * 0.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val box = Path().apply {
            moveTo(x - r * 0.3f, y - r * 0.15f)
            lineTo(x - r * 0.75f, y - r * 0.15f)
            lineTo(x - r * 0.75f, y + r * 0.95f)
            lineTo(x + r * 0.75f, y + r * 0.95f)
            lineTo(x + r * 0.75f, y - r * 0.15f)
            lineTo(x + r * 0.3f, y - r * 0.15f)
        }
        c.drawPath(box, line)
        c.drawLine(x, y + r * 0.35f, x, y - r * 1.05f, line)
        val head = Path().apply {
            moveTo(x - r * 0.4f, y - r * 0.65f)
            lineTo(x, y - r * 1.05f)
            lineTo(x + r * 0.4f, y - r * 0.65f)
        }
        c.drawPath(head, line)
    }

    private fun drawNote(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x - r * 0.35f, y + r * 0.6f, r * 0.42f, ink)
        c.drawRect(x - r * 0.0f, y - r * 0.9f, x + r * 0.14f, y + r * 0.6f, ink)
        c.drawRect(x, y - r * 0.9f, x + r * 0.7f, y - r * 0.62f, ink)
    }

    private const val ARTIST_PHOTO_WAIT_MS = 4_000L
}
