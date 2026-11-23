package com.opentune.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.readsRightToLeft
import com.opentune.ui.player.tightTrackingFits

/**
 * The floating lyrics themselves: a dark pill with the sung line, and the next one under
 * it, dimmer. Plain views, changed only when the line does. As the song moves on, the next
 * line rises into the sung one's place and the one after fades in; with [animate] off
 * (Reduce animation, Lite mode) they just change.
 *
 * Each line is set out by its own script: Arabic, Urdu and other right-to-left lines start
 * at the right, and only Latin, Greek and Cyrillic take the lyrics' tight letter spacing,
 * as in the player ([tightTrackingFits]).
 */
@SuppressLint("ViewConstructor")
internal class FloatingLyricsPill(context: Context, maxWidthPx: Int) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val now = line(15f, Typeface.create("sans-serif-medium", Typeface.NORMAL), 0xFFFFFFFF.toInt(), maxLines = 2, maxWidthPx)
    private val next = line(13f, Typeface.create("sans-serif", Typeface.NORMAL), 0x99FFFFFF.toInt(), maxLines = 1, maxWidthPx)
    private var shown: LyricsNow? = null

    init {
        orientation = VERTICAL
        val h = (16 * density).toInt()
        val v = (10 * density).toInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            cornerRadius = 22 * density
            setColor(0xEB15161B.toInt())
            setStroke((1 * density).toInt().coerceAtLeast(1), 0x1FFFFFFF)
        }
        elevation = 6 * density
        // Both lines as wide as the pill, so a shorter right-to-left one still ends at its right.
        addView(now, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(next, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
    }

    private fun line(sp: Float, face: Typeface, color: Int, maxLines: Int, maxWidthPx: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = face
        setTextColor(color)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = maxWidthPx
        includeFontPadding = false
        // Start is the line's own start: the right for a right-to-left line.
        gravity = Gravity.START
    }

    /** Shows [lines]; a change from the line before slides up when [animate]. */
    fun setLines(lines: LyricsNow, animate: Boolean) {
        val before = shown
        shown = lines
        val advanced = before != null && before.videoId == lines.videoId && lines.current != null &&
            before.next?.startMs == lines.current.startMs
        now.show(lines.current)
        next.show(lines.next)
        contentDescription = lines.current?.text ?: lines.next?.text
        now.animate().cancel()
        next.animate().cancel()
        if (animate && advanced) {
            val rise = 14 * density
            now.alpha = 0.6f
            now.translationY = rise
            now.animate().alpha(1f).translationY(0f).setDuration(MOVE_MS).setInterpolator(DecelerateInterpolator()).start()
            next.alpha = 0f
            next.animate().alpha(1f).setDuration(MOVE_MS).setStartDelay(MOVE_MS / 3).start()
        } else {
            now.alpha = 1f
            now.translationY = 0f
            next.alpha = 1f
        }
    }

    private fun TextView.show(line: LyricLine?) {
        visibility = if (line == null) View.GONE else View.VISIBLE
        if (line == null) return
        text = line.text
        textDirection = if (readsRightToLeft(line)) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        letterSpacing = if (tightTrackingFits(line.text)) TIGHT_EM else 0f
    }

    private companion object {
        const val MOVE_MS = 260L
        /** A touch tighter than the font's own, as the player's lyrics are; only for scripts that take it. */
        const val TIGHT_EM = -0.01f
    }
}
