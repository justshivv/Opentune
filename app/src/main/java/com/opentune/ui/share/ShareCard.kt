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
import android.graphics.Rect
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

/**
 * A picture of a song to send along with its link: the cover blurred into
 * the background, and on it a rounded card of the cover with the song's
 * name in a pill at the top, share and like buttons, and the time and
 * controls along the bottom, like a now-playing widget.
 */
object ShareCard {
    private const val TAG = "ShareCard"
    const val SIZE = 1080
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Shares [song]'s link with its card attached; just the link if the card can't be made. */
    fun share(context: Context, song: Song, positionMs: Long = 0, durationMs: Long = 0) {
        val link = "https://music.youtube.com/watch?v=${song.videoId}"
        scope.launch {
            val uri = runCatching {
                val cover = loadCover(context, song)
                val duration = durationMs.takeIf { it > 0 } ?: durationOf(song.durationText)
                val bitmap = withContext(Dispatchers.Default) { draw(cover, song.title, song.artist, positionMs, duration) }
                withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    // One card at a time is enough; older ones are cleared away.
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, "OpenTune-${song.videoId}.png")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                }
            }.onFailure { Log.w(TAG, "couldn't make the share card", it) }.getOrNull()
            val text = "${song.title} · ${song.artist}\n$link"
            val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
            if (uri != null) {
                send.setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(song.title, uri)
            } else {
                send.setType("text/plain")
            }
            context.startActivity(Intent.createChooser(send, null))
        }
    }

    private suspend fun loadCover(context: Context, song: Song): Bitmap? {
        val url = Subsonic.resolveCover(song.thumbnailUrl.artworkAt(SIZE), SIZE) ?: return null
        val request = ImageRequest.Builder(context).data(url).size(SIZE).allowHardware(false).build()
        return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    internal fun durationOf(text: String?): Long =
        text?.split(':')?.map { it.trim().toLongOrNull() ?: return 0 }?.fold(0L) { acc, n -> acc * 60 + n }?.times(1000) ?: 0

    /** The card itself, [SIZE] pixels square. */
    fun draw(cover: Bitmap?, title: String, artist: String, positionMs: Long, durationMs: Long): Bitmap {
        val s = SIZE.toFloat()
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Pictures go down with their own opaque paint; the shapes' paint carries their see-through colours.
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // The background: the cover blown up from a few pixels, which blurs it softly, and dimmed.
        if (cover != null) {
            val tiny = Bitmap.createScaledBitmap(centerSquare(cover), 24, 24, true)
            c.drawBitmap(tiny, null, RectF(0f, 0f, s, s), image)
        } else {
            c.drawColor(Color.rgb(58, 52, 60))
        }
        c.drawColor(Color.argb(70, 0, 0, 0))

        // A darker glass frame round the card, then the card of the cover itself.
        val frame = RectF(s * 0.14f, s * 0.14f, s * 0.86f, s * 0.86f)
        paint.color = Color.argb(80, 0, 0, 0)
        c.drawRoundRect(frame, s * 0.105f, s * 0.105f, paint)
        val card = RectF(frame.left + s * 0.02f, frame.top + s * 0.02f, frame.right - s * 0.02f, frame.bottom - s * 0.02f)
        val radius = s * 0.085f
        c.save()
        val clip = Path().apply { addRoundRect(card, radius, radius, Path.Direction.CW) }
        c.clipPath(clip)
        if (cover != null) {
            c.drawBitmap(centerSquare(cover), null, card, image)
        } else {
            paint.color = Color.rgb(90, 84, 96)
            c.drawRect(card, paint)
        }
        // Darker toward the bottom, so the time and buttons read on any cover.
        paint.shader = LinearGradient(0f, card.top + card.height() * 0.45f, 0f, card.bottom, Color.TRANSPARENT, Color.argb(190, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(card, paint)
        paint.shader = LinearGradient(0f, card.top, 0f, card.top + card.height() * 0.3f, Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(card, paint)
        paint.shader = null
        c.restore()

        val pad = card.width() * 0.04f
        val glass = Color.argb(105, 30, 30, 30)

        // Top right: share and like.
        val button = card.width() * 0.13f
        val topY = card.top + pad + button / 2f
        val heartX = card.right - pad - button / 2f
        val shareX = heartX - button - pad * 0.6f
        paint.color = glass
        c.drawCircle(heartX, topY, button / 2f, paint)
        c.drawCircle(shareX, topY, button / 2f, paint)
        drawHeart(c, heartX, topY, button * 0.2f)
        drawShare(c, shareX, topY, button * 0.2f)

        // Top left: the song in a pill, the cover as its picture.
        val pillH = button
        val pillLeft = card.left + pad
        val pillMax = shareX - button / 2f - pad * 0.6f - pillLeft
        val avatar = pillH - pad * 0.5f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = s * 0.032f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255); textSize = s * 0.026f }
        val textMax = pillMax - avatar - pad * 1.1f
        val t = TextUtils.ellipsize(title, titlePaint, textMax, TextUtils.TruncateAt.END).toString()
        val a = TextUtils.ellipsize(artist, subPaint, textMax, TextUtils.TruncateAt.END).toString()
        val textW = maxOf(titlePaint.measureText(t), subPaint.measureText(a))
        val pill = RectF(pillLeft, topY - pillH / 2f, pillLeft + avatar + pad * 0.25f + pad * 0.7f + textW + pad * 0.8f, topY + pillH / 2f)
        paint.color = glass
        c.drawRoundRect(pill, pillH / 2f, pillH / 2f, paint)
        val avatarRect = RectF(pill.left + pad * 0.25f, topY - avatar / 2f, pill.left + pad * 0.25f + avatar, topY + avatar / 2f)
        if (cover != null) {
            c.save()
            c.clipPath(Path().apply { addOval(avatarRect, Path.Direction.CW) })
            c.drawBitmap(centerSquare(cover), null, avatarRect, image)
            c.restore()
        } else {
            paint.color = Color.argb(60, 255, 255, 255)
            c.drawOval(avatarRect, paint)
        }
        val textX = avatarRect.right + pad * 0.7f
        c.drawText(t, textX, topY - s * 0.004f, titlePaint)
        c.drawText(a, textX, topY + s * 0.03f, subPaint)

        // Bottom: elapsed and remaining time over a progress line, then the controls.
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = s * 0.026f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val controlsY = card.bottom - pad - button / 2f
        val barY = controlsY - button / 2f - pad * 0.9f
        val left = card.left + pad * 1.2f
        val right = card.right - pad * 1.2f
        // Without a known length there's no time left to show, only the bar.
        if (durationMs > 0) {
            c.drawText(formatTime(positionMs), left, barY - pad * 0.7f, timePaint)
            val remaining = "-" + formatTime((durationMs - positionMs).coerceAtLeast(0))
            c.drawText(remaining, right - timePaint.measureText(remaining), barY - pad * 0.7f, timePaint)
        }
        val bar = s * 0.007f
        paint.color = Color.argb(80, 255, 255, 255)
        c.drawRoundRect(RectF(left, barY - bar / 2f, right, barY + bar / 2f), bar, bar, paint)
        val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (f > 0f) {
            paint.color = Color.WHITE
            c.drawRoundRect(RectF(left, barY - bar / 2f, left + (right - left) * f, barY + bar / 2f), bar, bar, paint)
        }
        val gap = button * 1.15f
        paint.color = Color.argb(70, 255, 255, 255)
        for (x in listOf(card.centerX() - gap, card.centerX(), card.centerX() + gap)) c.drawCircle(x, controlsY, button / 2f, paint)
        drawSkip(c, card.centerX() - gap, controlsY, button * 0.17f, forward = false)
        drawPlay(c, card.centerX(), controlsY, button * 0.19f)
        drawSkip(c, card.centerX() + gap, controlsY, button * 0.17f, forward = true)

        // Under the card, quietly: where it came from.
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = s * 0.024f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.12f }
        val word = "OPENTUNE"
        c.drawText(word, (s - brand.measureText(word)) / 2f, frame.bottom + (s - frame.bottom) / 2f + s * 0.008f, brand)
        return out
    }

    private fun centerSquare(b: Bitmap): Bitmap {
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

    private fun drawSkip(c: Canvas, x: Float, y: Float, r: Float, forward: Boolean) {
        val d = if (forward) 1f else -1f
        val p = Path().apply {
            moveTo(x - d * r * 0.8f, y - r)
            lineTo(x + d * r * 0.6f, y)
            lineTo(x - d * r * 0.8f, y + r)
            close()
        }
        c.drawPath(p, ink)
        val barX = x + d * r * 0.75f
        c.drawRect(Rect((barX - r * 0.14f).toInt(), (y - r).toInt(), (barX + r * 0.14f).toInt(), (y + r).toInt()), ink)
    }

    private fun drawHeart(c: Canvas, x: Float, y: Float, r: Float) {
        val p = Path().apply {
            moveTo(x, y + r * 0.95f)
            cubicTo(x - r * 2.1f, y - r * 0.2f, x - r * 0.95f, y - r * 1.75f, x, y - r * 0.55f)
            cubicTo(x + r * 0.95f, y - r * 1.75f, x + r * 2.1f, y - r * 0.2f, x, y + r * 0.95f)
            close()
        }
        c.drawPath(p, ink)
    }

    private fun drawShare(c: Canvas, x: Float, y: Float, r: Float) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r * 0.24f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        // An open box with an arrow rising out of it.
        val box = Path().apply {
            moveTo(x - r * 0.45f, y - r * 0.2f)
            lineTo(x - r * 0.95f, y - r * 0.2f)
            lineTo(x - r * 0.95f, y + r * 1.05f)
            lineTo(x + r * 0.95f, y + r * 1.05f)
            lineTo(x + r * 0.95f, y - r * 0.2f)
            lineTo(x + r * 0.45f, y - r * 0.2f)
        }
        c.drawPath(box, line)
        c.drawLine(x, y - r * 1.25f, x, y + r * 0.4f, line)
        val head = Path().apply {
            moveTo(x - r * 0.5f, y - r * 0.75f)
            lineTo(x, y - r * 1.25f)
            lineTo(x + r * 0.5f, y - r * 0.75f)
        }
        c.drawPath(head, line)
    }
}
