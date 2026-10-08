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
 * A picture of a song to send along with its link, drawn like a phone's
 * lock screen playing it: the cover blurred into the background, the time
 * and date, the cover itself and a frosted panel with the song, the time
 * and the controls.
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
                val duration = durationMs.takeIf { it > 0 } ?: durationOf(song.durationText)
                withContext(Dispatchers.Default) { draw(cover, song.title, song.artist, positionMs, duration) }
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

    /** How tall the card is; it's [SIZE] wide, a phone-friendly 4:5. */
    const val HEIGHT = 1350

    /**
     * The card itself, [SIZE] by [HEIGHT], drawn like a lock screen: the
     * cover blurred into the whole background, the time and date at the
     * top, the cover large in the middle, and under it a frosted panel with
     * the song, the time and the controls.
     */
    fun draw(
        cover: Bitmap?,
        title: String,
        artist: String,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        playing: Boolean = positionMs > 0,
    ): Bitmap {
        val w = SIZE.toFloat()
        val h = HEIGHT.toFloat()
        val out = Bitmap.createBitmap(SIZE, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Pictures go down with their own opaque paint; the shapes' paint carries see-through colours.
        val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // The background: the cover blown up from a few pixels, which blurs it softly, a little dimmed.
        if (cover != null) {
            val tiny = blurred(centerSquare(cover))
            // Wider than tall, so it's cropped at the sides to fill the height.
            val side = h * 1.1f
            c.drawBitmap(tiny, null, RectF((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f), image)
        } else {
            c.drawColor(Color.rgb(92, 82, 70))
        }
        c.drawColor(Color.argb(46, 0, 0, 0))
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(40, 0, 0, 0), Color.TRANSPARENT, Color.argb(70, 0, 0, 0)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val white = Color.WHITE
        val soft = Color.argb(185, 255, 255, 255)

        // Top: a small lock, then the time and the date.
        drawLock(c, w / 2f, h * 0.045f, w * 0.014f)
        val clock = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = w * 0.036f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val date = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = w * 0.036f }
        val time = java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault()).format(java.util.Date(nowMs))
        val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(nowMs))
        val gap = w * 0.03f
        val row = clock.measureText(time) + gap + date.measureText(day)
        val rowY = h * 0.105f
        c.drawText(time, (w - row) / 2f, rowY, clock)
        c.drawText(day, (w - row) / 2f + clock.measureText(time) + gap, rowY, date)

        // The cover, large, with a soft shadow under it.
        val coverSide = w * 0.64f
        val coverRect = RectF((w - coverSide) / 2f, h * 0.15f, (w + coverSide) / 2f, h * 0.15f + coverSide)
        val radius = w * 0.034f
        paint.color = Color.argb(70, 0, 0, 0)
        paint.maskFilter = android.graphics.BlurMaskFilter(w * 0.03f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(coverRect.left, coverRect.top + w * 0.012f, coverRect.right, coverRect.bottom + w * 0.02f), radius, radius, paint)
        paint.maskFilter = null
        c.save()
        c.clipPath(Path().apply { addRoundRect(coverRect, radius, radius, Path.Direction.CW) })
        if (cover != null) {
            c.drawBitmap(centerSquare(cover), null, coverRect, image)
        } else {
            paint.color = Color.argb(70, 255, 255, 255)
            c.drawRect(coverRect, paint)
            drawNote(c, coverRect.centerX(), coverRect.centerY(), coverSide * 0.12f)
        }
        c.restore()

        // The now-playing panel: frosted glass the cover's width, with a hairline edge.
        val panel = RectF(coverRect.left, coverRect.bottom + w * 0.026f, coverRect.right, coverRect.bottom + w * 0.026f + w * 0.27f)
        paint.color = Color.argb(48, 255, 255, 255)
        c.drawRoundRect(panel, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.0018f
        paint.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(panel, radius, radius, paint)
        paint.style = Paint.Style.FILL

        val inner = panel.width() * 0.07f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = w * 0.032f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = w * 0.028f }
        val textMax = panel.width() - inner * 3.2f
        val t = TextUtils.ellipsize(title, titlePaint, textMax, TextUtils.TruncateAt.END).toString()
        val a = TextUtils.ellipsize(artist, subPaint, textMax, TextUtils.TruncateAt.END).toString()
        c.drawText(t, panel.centerX() - titlePaint.measureText(t) / 2f, panel.top + panel.height() * 0.22f, titlePaint)
        c.drawText(a, panel.centerX() - subPaint.measureText(a) / 2f, panel.top + panel.height() * 0.37f, subPaint)
        drawWave(c, panel.right - inner * 0.9f, panel.top + panel.height() * 0.16f, w * 0.012f)

        // The time either side of a progress line.
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = w * 0.021f }
        val barY = panel.top + panel.height() * 0.555f
        val elapsed = formatTime(positionMs)
        val remaining = if (durationMs > 0) "−" + formatTime((durationMs - positionMs).coerceAtLeast(0)) else ""
        val left = panel.left + inner * 0.55f + timePaint.measureText("00:00") + w * 0.014f
        val right = panel.right - inner * 0.55f - timePaint.measureText("−00:00") - w * 0.014f
        c.drawText(elapsed, panel.left + inner * 0.55f, barY + timePaint.textSize * 0.35f, timePaint)
        if (remaining.isNotEmpty()) c.drawText(remaining, panel.right - inner * 0.55f - timePaint.measureText(remaining), barY + timePaint.textSize * 0.35f, timePaint)
        val bar = w * 0.0075f
        paint.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(RectF(left, barY - bar / 2f, right, barY + bar / 2f), bar, bar, paint)
        val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (f > 0f) {
            paint.color = Color.argb(235, 255, 255, 255)
            c.drawRoundRect(RectF(left, barY - bar / 2f, left + (right - left) * f, barY + bar / 2f), bar, bar, paint)
        }

        // Back, play or pause, forward, and where it's playing at the side.
        val controlsY = panel.top + panel.height() * 0.8f
        val step = panel.width() * 0.2f
        drawSeek(c, panel.centerX() - step, controlsY, w * 0.022f, forward = false)
        if (playing) drawPause(c, panel.centerX(), controlsY, w * 0.026f) else drawPlay(c, panel.centerX(), controlsY, w * 0.026f)
        drawSeek(c, panel.centerX() + step, controlsY, w * 0.022f, forward = true)
        drawSpeaker(c, panel.right - inner * 0.9f, controlsY, w * 0.014f)

        // At the foot, quietly: where it came from.
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = w * 0.022f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.14f }
        val word = "OPENTUNE"
        c.drawText(word, (w - brand.measureText(word)) / 2f, h - h * 0.035f, brand)
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

    /** Two arrowheads, like ⏩, pointing back or forward. */
    private fun drawSeek(c: Canvas, x: Float, y: Float, r: Float, forward: Boolean) {
        val d = if (forward) 1f else -1f
        for (k in 0..1) {
            val tip = x + d * (r * 0.05f + k * r)
            val p = Path().apply {
                moveTo(tip - d * r, y - r * 0.75f)
                lineTo(tip, y)
                lineTo(tip - d * r, y + r * 0.75f)
                close()
            }
            c.drawPath(p, ink)
        }
    }

    private fun drawLock(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawRoundRect(RectF(x - r, y - r * 0.2f, x + r, y + r * 1.25f), r * 0.25f, r * 0.25f, ink)
        val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r * 0.32f }
        c.drawArc(RectF(x - r * 0.62f, y - r * 1.15f, x + r * 0.62f, y + r * 0.2f), 180f, 180f, false, shackle)
        c.drawLine(x - r * 0.62f, y - r * 0.45f, x - r * 0.62f, y - r * 0.15f, shackle)
        c.drawLine(x + r * 0.62f, y - r * 0.45f, x + r * 0.62f, y - r * 0.15f, shackle)
    }

    /** Four little bars, the "playing" mark in the panel's corner. */
    private fun drawWave(c: Canvas, x: Float, y: Float, r: Float) {
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        val heights = floatArrayOf(0.5f, 1f, 0.7f, 0.35f)
        val bw = r * 0.32f
        heights.forEachIndexed { i, f ->
            val bx = x - r * 1.2f + i * r * 0.8f
            c.drawRoundRect(RectF(bx, y - r * f, bx + bw, y + r * f), bw / 2f, bw / 2f, soft)
        }
    }

    private fun drawSpeaker(c: Canvas, x: Float, y: Float, r: Float) {
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) }
        val body = Path().apply {
            moveTo(x - r * 1.1f, y - r * 0.45f)
            lineTo(x - r * 0.5f, y - r * 0.45f)
            lineTo(x + r * 0.2f, y - r * 1.1f)
            lineTo(x + r * 0.2f, y + r * 1.1f)
            lineTo(x - r * 0.5f, y + r * 0.45f)
            lineTo(x - r * 1.1f, y + r * 0.45f)
            close()
        }
        c.drawPath(body, soft)
        val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = r * 0.22f; strokeCap = Paint.Cap.ROUND }
        c.drawArc(RectF(x - r * 0.3f, y - r * 0.7f, x + r * 1.0f, y + r * 0.7f), -50f, 100f, false, wave)
        c.drawArc(RectF(x - r * 0.6f, y - r * 1.2f, x + r * 1.5f, y + r * 1.2f), -50f, 100f, false, wave)
    }

    private fun drawNote(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x - r * 0.35f, y + r * 0.6f, r * 0.42f, ink)
        c.drawRect(x - r * 0.0f, y - r * 0.9f, x + r * 0.14f, y + r * 0.6f, ink)
        c.drawRect(x, y - r * 0.9f, x + r * 0.7f, y - r * 0.62f, ink)
    }
}
