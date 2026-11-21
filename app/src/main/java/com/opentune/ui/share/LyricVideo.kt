package com.opentune.ui.share

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.opentune.data.lyrics.LyricLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A lyric video's frames: the picked lines sung in time over the album's own moving cover
 * (Spotify's Canvas, or Apple's animated cover), or over the cover's colours drifting like
 * liquid, with the song's cover, title and artist at the foot. Every frame is drawn from the
 * time alone, so the preview in the sheet and the video saved are the same frames.
 *
 * The line being sung is bright; with word timing, each word lights as it's sung, the light's
 * edge feathered. The lines glide up as each new one begins, those further off softer and a
 * touch smaller.
 */
internal class LyricVideo(
    private val lines: List<LyricLine>,
    private val cover: Bitmap?,
    private val motion: MotionFrames?,
    private val title: String,
    private val artist: String,
    private val layout: Layout,
    private val showLogo: Boolean,
    val width: Int = WIDTH,
    val height: Int = HEIGHT,
) {
    /** Full-screen lines (a story), or the lines on a frosted card in the middle. */
    enum class Layout { STORY, CARD }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val FPS = 30
        /** The longest video made: a story's own limit is a minute; half that keeps it to the part. */
        const val MAX_MS = 30_000L
        private const val LEAD_MS = 700L
        private const val TAIL_MS = 1_200L
        /** How long a line takes to glide into place, and to brighten or dim. */
        private const val GLIDE_MS = 620f
        private const val FADE_MS = 420f

        /**
         * The lines a video of [picked] covers, cut to [MAX_MS], with ends filled in where a
         * source leaves them open (the next line's start, or three seconds).
         */
        fun span(picked: List<LyricLine>): List<LyricLine> {
            val sorted = picked.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
            if (sorted.isEmpty()) return emptyList()
            val ended = sorted.mapIndexed { i, l ->
                val next = sorted.getOrNull(i + 1)?.startMs
                val end = when {
                    l.endMs > l.startMs -> l.endMs
                    next != null -> next
                    else -> l.startMs + 3_000
                }
                l.copy(endMs = end)
            }
            val first = ended.first().startMs
            return ended.filter { it.startMs - first < MAX_MS - LEAD_MS - TAIL_MS }
        }

        /** Where the video starts, in the song's lyric time. */
        fun startOf(lines: List<LyricLine>): Long = (lines.first().startMs - LEAD_MS).coerceAtLeast(0)

        /** How long the video runs. */
        fun lengthOf(lines: List<LyricLine>): Long =
            (min(lines.last().endMs + TAIL_MS, startOf(lines) + MAX_MS) - startOf(lines)).coerceAtLeast(3_000)

        private fun ease(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            // A soft start and a long, feathered landing.
            return 1f - (1f - t).pow(4)
        }
    }

    val startMs = startOf(lines)
    val lengthMs = lengthOf(lines)

    private val story = layout == Layout.STORY
    private val w = width.toFloat()
    private val h = height.toFloat()

    // The background is made small and stretched up: it's a soft wash, and that keeps a frame quick.
    private val bg = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888)
    private val bgCanvas = Canvas(bg)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blurredCover = cover?.let { ShareCard.blurred(ShareCard.centerSquare(it), 48, 4) }
    private val square = cover?.let(ShareCard::centerSquare)
    private val palette = palette(cover)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Where the lines go: the whole frame for a story, the card's top part for a card.
    private val margin = w * 0.08f
    private val card = RectF(w * 0.11f, h * 0.24f, w * 0.89f, h * 0.76f)
    private val footH = if (story) w * 0.14f else card.height() * 0.17f
    private val area = if (story) RectF(margin, h * 0.16f, w - margin, h - margin - footH - h * 0.04f)
        else RectF(card.left + card.width() * 0.08f, card.top + card.height() * 0.07f, card.right - card.width() * 0.08f, card.bottom - footH - card.height() * 0.04f)
    private val anchor = area.top + area.height() * (if (story) 0.30f else 0.30f)
    private val textSize = if (story) w * 0.074f else w * 0.056f
    private val gap = textSize * 0.55f

    private val dimPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.textSize = this@LyricVideo.textSize; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val litPaint = TextPaint(dimPaint)
    private val dim = lines.map { build(it.text, dimPaint) }
    private val lit = lines.map { build(it.text, litPaint) }
    /** Each line's top, laid out one under another from 0. */
    private val tops = run {
        var y = 0f
        dim.map { l -> y.also { y += l.height + gap } }
    }
    private val blurs = HashMap<Int, BlurMaskFilter>()
    /** Each word-timed line's words as character spans of its text. */
    private val wordSpans = lines.map { spans(it) }

    private fun build(text: String, p: TextPaint) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, area.width().toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.04f)
            .setIncludePad(false)
            .build()

    /** Draws the frame [t] ms into the video onto [c], which is [width] by [height]. */
    fun draw(c: Canvas, t: Long) {
        val song = startMs + t
        background(t)
        c.drawBitmap(bg, null, RectF(0f, 0f, w, h), bgPaint)
        // Darker towards the foot, where the title sits, so white reads on any cover.
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(95, 0, 0, 0), Color.argb(170, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        if (!story) {
            // The frosted card: smoked glass over the wash, so white words read on any colour,
            // with a faint sheen and a hairline rim.
            val r = w * 0.05f
            paint.color = Color.argb(88, 0, 0, 0)
            c.drawRoundRect(card, r, r, paint)
            paint.color = Color.argb(18, 255, 255, 255)
            c.drawRoundRect(card, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.0025f
            paint.color = Color.argb(60, 255, 255, 255)
            c.drawRoundRect(card, r, r, paint)
            paint.style = Paint.Style.FILL
        }

        lyrics(c, song)
        foot(c)
        if (showLogo) {
            val bh = w * (if (story) 0.036f else 0.028f)
            if (story) ShareCard.drawBrand(c, w - margin, margin + bh, bh, Color.argb(235, 255, 255, 255))
            else ShareCard.drawBrand(c, card.right - card.width() * 0.07f, card.bottom - footH * 0.5f, bh, Color.argb(210, 255, 255, 255))
        }

        // In from black at the start, and out to it at the end.
        val fade = min(1f, t / 350f) * min(1f, (lengthMs - t) / 450f)
        if (fade < 1f) {
            paint.color = Color.argb(((1f - fade.coerceIn(0f, 1f)) * 255).toInt(), 0, 0, 0)
            c.drawRect(0f, 0f, w, h, paint)
        }
    }

    // ---- The background ----------------------------------------------------------------

    private fun background(t: Long) {
        val bw = bg.width.toFloat()
        val bh = bg.height.toFloat()
        bgCanvas.drawColor(Color.BLACK)
        val m = motion
        if (m != null) {
            // The clip, filling the frame, drifting very slowly larger.
            val side = bh * (1.05f + 0.06f * (t / lengthMs.toFloat()))
            m.draw(bgCanvas, t, RectF((bw - side) / 2f, (bh - side) / 2f, (bw + side) / 2f, (bh + side) / 2f))
            return
        }
        // Ambient: the blurred cover turning slowly underneath, and the cover's colours
        // flowing over it as large soft lights.
        val s = t / 1000f
        blurredCover?.let { b ->
            val side = bh * 1.9f
            bgCanvas.save()
            bgCanvas.rotate(s * 7f, bw / 2f, bh / 2f)
            bgPaint.alpha = 255
            bgCanvas.drawBitmap(b, null, RectF((bw - side) / 2f, (bh - side) / 2f, (bw + side) / 2f, (bh + side) / 2f), bgPaint)
            bgCanvas.restore()
        } ?: bgCanvas.drawColor(palette[0])
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        palette.forEachIndexed { i, colour ->
            val sp = 0.21f + i * 0.07f
            val x = bw * (0.5f + 0.42f * sin(s * sp + i * 1.7f))
            val y = bh * (0.5f + 0.40f * cos(s * sp * 0.8f + i * 2.3f))
            val r = bh * (0.42f + 0.08f * sin(s * 0.5f + i))
            paint.shader = RadialGradient(x, y, r, Color.argb(150, Color.red(colour), Color.green(colour), Color.blue(colour)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            bgCanvas.drawCircle(x, y, r, paint)
        }
        paint.shader = null
        paint.xfermode = null
    }

    /** Four colours from the cover, the most varied first; a deep violet set without one. */
    private fun palette(cover: Bitmap?): List<Int> {
        cover ?: return listOf(Color.rgb(48, 26, 92), Color.rgb(120, 40, 140), Color.rgb(30, 80, 160), Color.rgb(170, 60, 110))
        val tiny = Bitmap.createScaledBitmap(ShareCard.centerSquare(cover), 6, 6, true)
        val all = (0 until 36).map { tiny.getPixel(it % 6, it / 6) }
        val picked = mutableListOf(all.maxBy { saturation(it) * lightness(it) })
        while (picked.size < 4) picked += all.maxBy { c -> picked.minOf { distance(it, c) } }
        return picked.map { lift(it) }
    }

    private fun saturation(c: Int): Float {
        val mx = max(Color.red(c), max(Color.green(c), Color.blue(c)))
        val mn = min(Color.red(c), min(Color.green(c), Color.blue(c)))
        return if (mx == 0) 0f else (mx - mn) / mx.toFloat()
    }

    private fun lightness(c: Int) = (Color.red(c) + Color.green(c) + Color.blue(c)) / 765f + 0.2f

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /** Colours a little brighter and more saturated, so the lights glow rather than muddy. */
    private fun lift(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)
        hsv[2] = (hsv[2] * 1.15f + 0.08f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }

    // ---- The lines ---------------------------------------------------------------------

    private fun lyrics(c: Canvas, song: Long) {
        val active = lines.indexOfLast { it.startMs <= song }
        // Drawn in a layer of their own, so the lines fade out softly at its top and bottom
        // edges rather than being cut there.
        val box = if (story) RectF(0f, area.top - textSize, w, area.bottom) else RectF(card.left, area.top - textSize * 0.3f, card.right, area.bottom)
        c.saveLayer(box, null)
        c.clipRect(box)
        for (i in lines.indices) {
            val line = lines[i]
            // Moved up by each line's height as each new line begins (see [scroll]).
            val y = anchor + tops[i] - scroll(song, i)
            if (y > area.bottom + textSize || y + dim[i].height < area.top - textSize * 2) continue
            val sinceStart = (song - line.startMs).toFloat()
            val sinceEnd = lines.getOrNull(i + 1)?.let { (song - it.startMs).toFloat() } ?: (song - line.endMs - 600).toFloat()
            // 1 while it's the line being sung, easing in at its start and out once the next begins.
            val on = when {
                sinceStart < 0 -> 0f
                sinceEnd < 0 -> ease(sinceStart / FADE_MS)
                else -> 1f - ease(sinceEnd / FADE_MS)
            }
            val distanceAway = if (active < 0) i + 1 else abs(i - active)
            val alpha = 0.34f + 0.66f * on
            val scale = 0.955f + 0.045f * on
            val blur = ((1f - on) * min(distanceAway, 3) * w * 0.0016f)
            c.save()
            c.translate(area.left, y)
            c.scale(scale, scale, 0f, dim[i].height / 2f)
            val words = wordSpans[i]
            if (words != null && on > 0f) {
                // Word by word: the line dim, and lit up to where the singing has got, with a feathered edge.
                dimPaint.maskFilter = null
                dimPaint.alpha = (255 * (0.34f + 0.16f * on)).toInt()
                dim[i].draw(c)
                wipe(c, i, song, words, on)
            } else {
                dimPaint.maskFilter = blurOf(blur)
                dimPaint.alpha = (255 * alpha).toInt()
                dim[i].draw(c)
            }
            dimPaint.maskFilter = null
            c.restore()
        }
        val edge = textSize * (if (story) 1.4f else 0.8f)
        paint.shader = LinearGradient(
            0f, box.top, 0f, box.bottom,
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, edge / box.height(), 1f - edge / box.height(), 1f), Shader.TileMode.CLAMP,
        )
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(box, paint)
        paint.xfermode = null
        paint.shader = null
        c.restore()
    }

    /** How far the lines have moved for line [i], at [song]: each line's start lifts them by its height. */
    private fun scroll(song: Long, i: Int): Float {
        var s = 0f
        for (k in 1 until lines.size) {
            // Lines below the new one follow a little after it, a ripple down the page.
            val delay = (max(0, i - k) * 45).coerceAtMost(180)
            val p = ease((song - lines[k].startMs - delay) / GLIDE_MS)
            s += (tops[k] - tops[k - 1]) * p
        }
        return s
    }

    private fun blurOf(radius: Float): BlurMaskFilter? {
        if (radius < 0.6f) return null
        val key = (radius * 2).toInt()
        return blurs.getOrPut(key) { BlurMaskFilter(key / 2f, BlurMaskFilter.Blur.NORMAL) }
    }

    /** The lit part of a word-timed line, each visual line lit to where the singing is. */
    private fun wipe(c: Canvas, i: Int, song: Long, words: List<Pair<IntRange, LyricLine>>, on: Float) {
        val layout = lit[i]
        val at = charAt(song, words, lines[i])
        val feather = w * 0.06f
        litPaint.alpha = (255 * on).toInt()
        for (j in 0 until layout.lineCount) {
            val start = layout.getLineStart(j)
            val end = layout.getLineEnd(j)
            val top = layout.getLineTop(j).toFloat()
            val bottom = layout.getLineBottom(j).toFloat()
            if (at <= start) break
            c.save()
            c.clipRect(0f, top, area.width(), bottom)
            if (at >= end) {
                litPaint.shader = null
            } else {
                val whole = at.toInt().coerceIn(start, end)
                val x0 = layout.getPrimaryHorizontal(whole)
                val x1 = if (whole + 1 <= end) layout.getPrimaryHorizontal((whole + 1).coerceAtMost(end)) else x0
                val x = x0 + (x1 - x0) * (at - whole)
                litPaint.shader = LinearGradient(x - feather, 0f, x + feather * 0.25f, 0f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            layout.draw(c)
            c.restore()
        }
        litPaint.shader = null
    }

    /** Where the singing is in line [line] at [song], as a character position, fractional within a character. */
    private fun charAt(song: Long, words: List<Pair<IntRange, LyricLine>>, line: LyricLine): Float {
        for ((range, timing) in words) {
            if (song < timing.startMs) return range.first.toFloat()
            if (song < timing.endMs) {
                val p = (song - timing.startMs).toFloat() / (timing.endMs - timing.startMs).coerceAtLeast(1)
                return range.first + p * (range.last + 1 - range.first)
            }
        }
        return line.text.length.toFloat()
    }

    /**
     * A line's words as character ranges of its text. Lines without word timing carry it
     * spread by character count, as the player sweeps them; null when there's none at all.
     */
    private fun spans(line: LyricLine): List<Pair<IntRange, LyricLine>>? {
        if (line.words.isEmpty()) return null
        var from = 0
        val out = mutableListOf<Pair<IntRange, LyricLine>>()
        for (word in line.words) {
            val text = word.text.trim()
            if (text.isEmpty()) continue
            val at = line.text.indexOf(text, from)
            if (at < 0) return null
            out += (at until at + text.length) to LyricLine(word.startMs, max(word.endMs, word.startMs + 1), text, emptyList(), false)
            from = at + text.length
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // ---- The foot ----------------------------------------------------------------------

    private fun foot(c: Canvas) {
        val left = if (story) margin else card.left + card.width() * 0.07f
        val right = if (story) w - margin else card.right - card.width() * 0.07f
        val thumb = if (story) footH else footH * 0.62f
        val top = if (story) h - margin - thumb else card.bottom - footH / 2f - thumb / 2f
        if (!story) {
            // The card's foot, set apart by a slightly lighter band.
            paint.color = Color.argb(26, 255, 255, 255)
            c.save()
            c.clipRect(card.left, card.bottom - footH, card.right, card.bottom)
            c.drawRoundRect(card, w * 0.05f, w * 0.05f, paint)
            c.restore()
        }
        val rect = RectF(left, top, left + thumb, top + thumb)
        square?.let { s ->
            c.save()
            c.clipPath(Path().apply { addRoundRect(rect, thumb * 0.14f, thumb * 0.14f, Path.Direction.CW) })
            c.drawBitmap(s, null, rect, image)
            c.restore()
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = thumb * 0.3f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255); textSize = thumb * 0.25f }
        val x = if (square != null) rect.right + thumb * 0.28f else left
        // Room left for the logo on the card's foot.
        val room = right - x - if (!story && showLogo) ShareCard.brandWidth(w * 0.028f) + thumb * 0.3f else 0f
        c.drawText(TextUtils.ellipsize(title, titlePaint, room, TextUtils.TruncateAt.END).toString(), x, top + thumb * 0.45f, titlePaint)
        c.drawText(TextUtils.ellipsize(artist, artistPaint, room, TextUtils.TruncateAt.END).toString(), x, top + thumb * 0.82f, artistPaint)
    }
}
