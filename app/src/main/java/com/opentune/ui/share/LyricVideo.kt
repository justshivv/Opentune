package com.opentune.ui.share

import android.graphics.Bitmap
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
        /** How strong the lines not being sung are. */
        private const val DIM = 0.42f
        private const val TAIL_MS = 1_200L
        /** How long a line takes to glide into place, and to brighten or dim. */
        /** A new page comes in this long before its first line, and takes this long to. */
        private const val PAGE_LEAD_MS = 450L
        private const val PAGE_FADE_MS = 420f
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
    // Shaders take their strength from the paint's own alpha too, so each one drawn has a
    // paint of its own at full strength: sharing [paint], whose colour was left at whatever
    // the last fill set (the rim's faint white, the fade's nearly clear black), had the
    // flowing lights not show.
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lights = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Where the lines go: the whole frame for a story, the card's top part for a card.
    private val margin = w * 0.08f
    private val card = RectF(w * 0.11f, h * 0.24f, w * 0.89f, h * 0.76f)
    private val footH = if (story) w * 0.14f else card.height() * 0.17f
    private val area = if (story) RectF(margin, h * 0.16f, w - margin, h - margin - footH - h * 0.04f)
        else RectF(card.left + card.width() * 0.08f, card.top + card.height() * 0.07f, card.right - card.width() * 0.08f, card.bottom - footH - card.height() * 0.04f)
    // The lines stay where they are laid out: the whole part at once, the text a little
    // smaller if that's what it takes to fit, and split into pages that follow one another
    // in place when even that isn't enough.
    private val laid = fit()
    private val textSize = laid.size
    private val gap = textSize * 0.5f
    private val dimPaint = laid.dimPaint
    private val litPaint = laid.litPaint
    private val dim = laid.dim
    private val lit = laid.lit
    /** Each page's lines, and each line's top on the frame. */
    private val pages: List<IntRange>
    private val lineTop = FloatArray(lines.size)
    init {
        val out = mutableListOf<IntRange>()
        var from = 0
        while (from < lines.size) {
            var to = from
            var height = dim[from].height.toFloat()
            while (to + 1 < lines.size && height + gap + dim[to + 1].height <= area.height()) {
                to++
                height += gap + dim[to].height
            }
            // Each page sits a little above the middle of the space for lines.
            var y = area.top + (area.height() - height) * 0.42f
            for (i in from..to) { lineTop[i] = y; y += dim[i].height + gap }
            out += from..to
            from = to + 1
        }
        pages = out
    }
    /** How far a sung word rises, and how far its glow reaches. */
    private val lift = textSize * 0.13f
    private val glow = textSize * 0.42f
    /** Each word-timed line's words as character spans of its text, the space after each included. */
    private val wordSpans = lines.map { spans(it) }

    private class Laid(val size: Float, val dimPaint: TextPaint, val litPaint: TextPaint, val dim: List<StaticLayout>, val lit: List<StaticLayout>)

    private fun fit(): Laid {
        var size = if (story) w * 0.074f else w * 0.056f
        val smallest = if (story) w * 0.056f else w * 0.044f
        while (true) {
            val d = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val l = TextPaint(d)
            val dimLayouts = lines.map { build(it.text, d) }
            val total = dimLayouts.sumOf { it.height.toDouble() } + size * 0.5f * (lines.size - 1)
            if (total <= area.height() || size <= smallest) {
                return Laid(size, d, l, dimLayouts, lines.map { build(it.text, l) })
            }
            size = max(smallest, size * 0.93f)
        }
    }

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
        shade.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(95, 0, 0, 0), Color.argb(170, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, shade)

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
        palette.forEachIndexed { i, colour ->
            val sp = 0.21f + i * 0.07f
            val x = bw * (0.5f + 0.42f * sin(s * sp + i * 1.7f))
            val y = bh * (0.5f + 0.40f * cos(s * sp * 0.8f + i * 2.3f))
            val r = bh * (0.42f + 0.08f * sin(s * 0.5f + i))
            lights.shader = RadialGradient(x, y, r, Color.argb(150, Color.red(colour), Color.green(colour), Color.blue(colour)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            bgCanvas.drawCircle(x, y, r, lights)
        }
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

    /** When page [p] comes in: a moment before its first line is sung. */
    private fun pageAt(p: Int): Long = if (p == 0) Long.MIN_VALUE else lines[pages[p].first].startMs - PAGE_LEAD_MS

    private fun lyrics(c: Canvas, song: Long) {
        val page = pages.indices.last { pageAt(it) <= song }
        // A new page fades in where the last one was, nothing moving.
        val k = if (page == 0) 1f else ((song - pageAt(page)) / PAGE_FADE_MS).coerceIn(0f, 1f)
        if (k < 1f) for (i in pages[page - 1]) line(c, i, song, 1f - k)
        for (i in pages[page]) line(c, i, song, k)
    }

    /** Line [i], at its place, [shown] as much as its page is. */
    private fun line(c: Canvas, i: Int, song: Long, shown: Float) {
        if (shown <= 0f) return
        val l = lines[i]
        val next = lines.getOrNull(i + 1)?.startMs ?: (l.endMs + 600)
        // 1 while it's the line being sung, easing in at its start and out once the next begins.
        val on = when {
            song < l.startMs -> 0f
            song < next -> ease((song - l.startMs) / FADE_MS)
            else -> 1f - ease((song - next) / FADE_MS)
        }
        // Sung already: a touch brighter than the lines still to come.
        val rest = if (song >= next) DIM + 0.08f else DIM
        c.save()
        c.translate(area.left, lineTop[i])
        val words = wordSpans[i]
        if (on <= 0f) {
            dimPaint.alpha = (255 * rest * shown).toInt()
            dim[i].draw(c)
        } else if (words == null) {
            // No word timing: the whole line rises a little and glows.
            c.translate(0f, -lift * on)
            dimPaint.alpha = (255 * rest * (1f - on) * shown).toInt()
            dim[i].draw(c)
            lit(on * shown, on)
            lit[i].draw(c)
        } else {
            for ((range, timing) in words) word(c, i, range, timing, song, on, rest, shown)
        }
        litPaint.shader = null
        litPaint.clearShadowLayer()
        c.restore()
    }

    /** One word of the line being sung: it rises as it's sung, lit with a feathered edge and glowing. */
    private fun word(c: Canvas, i: Int, range: IntRange, timing: LyricLine, song: Long, on: Float, rest: Float, shown: Float) {
        val layout = dim[i]
        val sung = when {
            song <= timing.startMs -> 0f
            song >= timing.endMs -> 1f
            else -> (song - timing.startMs).toFloat() / (timing.endMs - timing.startMs)
        }
        val row = layout.getLineForOffset(range.first)
        val rowStart = layout.getLineStart(row)
        val rowEnd = layout.getLineEnd(row)
        val a = layout.getPrimaryHorizontal(range.first)
        val b = if (range.last + 1 >= rowEnd) layout.getLineRight(row) else layout.getPrimaryHorizontal(range.last + 1)
        val left = min(a, b)
        val right = max(a, b)
        // The word's own box, so each word moves alone; its glow may spill past the ends of the
        // row and the top and bottom of the line, but not over a neighbour's letters.
        val clip = RectF(
            if (range.first <= rowStart) left - glow else left,
            layout.getLineTop(row) - if (row == 0) glow else 0f,
            if (range.last + 1 >= rowEnd) right + glow else right,
            layout.getLineBottom(row) + if (row == layout.lineCount - 1) glow else 0f,
        )
        c.save()
        c.translate(0f, -lift * ease(min(1f, sung * 1.6f)) * on)
        c.clipRect(clip)
        dimPaint.alpha = (255 * rest * shown).toInt()
        layout.draw(c)
        if (sung > 0f) {
            if (sung < 1f) {
                // Lit up to where the singing has got, the edge feathered.
                val rtl = a > b
                val x = if (rtl) right - (right - left) * sung else left + (right - left) * sung
                val feather = textSize * 0.6f
                litPaint.shader = if (rtl) LinearGradient(x + feather, 0f, x - feather * 0.25f, 0f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    else LinearGradient(x - feather, 0f, x + feather * 0.25f, 0f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            } else litPaint.shader = null
            lit(on * shown, on)
            lit[i].draw(c)
        }
        c.restore()
    }

    /** The lit paint at [alpha], with a soft white glow as strong as [on]. */
    private fun lit(alpha: Float, on: Float) {
        litPaint.alpha = (255 * alpha).toInt()
        litPaint.setShadowLayer(glow * 0.55f, 0f, 0f, Color.argb((150 * on * alpha).toInt(), 255, 255, 255))
    }

    /**
     * A line's words as character ranges of its text, each running on to the next word so
     * spaces and punctuation go with it. Lines without word timing carry it spread by
     * character count, as the player sweeps them; null when there's none at all.
     */
    private fun spans(line: LyricLine): List<Pair<IntRange, LyricLine>>? {
        if (line.words.isEmpty()) return null
        var from = 0
        val found = mutableListOf<Pair<Int, LyricLine>>()
        for (word in line.words) {
            val text = word.text.trim()
            if (text.isEmpty()) continue
            val at = line.text.indexOf(text, from)
            if (at < 0) return null
            found += at to LyricLine(word.startMs, max(word.endMs, word.startMs + 1), text, emptyList(), false)
            from = at + text.length
        }
        if (found.isEmpty()) return null
        return found.mapIndexed { k, (at, timing) ->
            val start = if (k == 0) 0 else at
            val end = found.getOrNull(k + 1)?.first ?: line.text.length
            (start until end) to timing
        }
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
