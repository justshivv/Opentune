package com.opentune.lyrics

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.settings.AppSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

/**
 * The floating lyrics pill, drawn in English, Arabic and Urdu: right-to-left lines start at
 * the right and keep their own letter spacing. Saved to build/screenshots/floating-lyrics.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class FloatingLyricsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val maxWidth get() = (context.resources.displayMetrics.widthPixels * 0.72f).toInt()

    @Before fun setUp() {
        AppSettings.init(context)
    }

    @Test fun drawsLeftToRightAndRightToLeftLines() {
        val pills = listOf(
            pill(line("Is this the real life?"), line("Is this just fantasy? Caught in a landslide")),
            pill(line("أحبك دائما", rtl = true), line("وأنت معي في كل مكان أذهب إليه", rtl = true)),
            pill(line("میں تم سے پیار کرتا ہوں"), line("تم میری زندگی ہو، ہمیشہ کے لیے")),
            pill(null, line("No escape from reality")),
        )
        val (english, arabic, urdu, gap) = pills

        // English takes the lyrics' tight tracking and starts at the left.
        val en = english.lineView(0)
        assertEquals(View.TEXT_DIRECTION_LTR, en.textDirection)
        assertTrue(en.letterSpacing < 0f)
        assertEquals(0f, en.layout.getLineLeft(0), 1f)

        // Arabic and Urdu keep the font's spacing, and a line shorter than the pill ends at its right.
        for (p in listOf(arabic, urdu)) {
            for (i in 0..1) {
                val v = p.lineView(i)
                assertEquals(View.TEXT_DIRECTION_RTL, v.textDirection)
                assertEquals(0f, v.letterSpacing)
            }
            val short = p.lineView(0)
            assertTrue("the short line sits right", short.layout.getLineLeft(0) > 10f)
            assertEquals(short.layout.width.toFloat(), short.layout.getLineRight(0), 1f)
        }

        // Through a break, only the next line.
        assertEquals(View.GONE, gap.lineView(0).visibility)
        assertEquals(View.VISIBLE, gap.lineView(1).visibility)

        save(pills)
    }

    @Test fun anAdvancedLineSlidesUpUnlessMotionIsReduced() {
        val a = line("one", start = 0)
        val b = line("two", start = 1_000)
        val c = line("three", start = 2_000)
        val pill = FloatingLyricsPill(context, maxWidth)
        pill.setLines(LyricsNow("s", a, b), animate = true)
        pill.setLines(LyricsNow("s", b, c), animate = true)
        assertTrue(pill.lineView(0).translationY > 0f)
        pill.setLines(LyricsNow("s", c, null), animate = false)
        assertEquals(0f, pill.lineView(0).translationY)
        assertEquals(1f, pill.lineView(0).alpha)
    }

    @Test fun showsOnlyWhilePlayingWithTheAppInTheBackground() {
        ShadowSettings.setCanDrawOverlays(true)
        AppSettings.updateLyrics { it.copy(floating = true) }
        FloatingLyrics.install(context)
        assertTrue(FloatingLyrics.wanted(context, playing = true))
        assertFalse(FloatingLyrics.wanted(context, playing = false))

        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        assertFalse("hidden over the app", FloatingLyrics.wanted(context, playing = true))
        activity.pause().stop()
        assertTrue(FloatingLyrics.wanted(context, playing = true))

        ShadowSettings.setCanDrawOverlays(false)
        assertFalse("not without the permission", FloatingLyrics.wanted(context, playing = true))
        ShadowSettings.setCanDrawOverlays(true)
        AppSettings.updateLyrics { it.copy(floating = false) }
        assertFalse(FloatingLyrics.wanted(context, playing = true))
        activity.destroy()
    }

    private fun pill(current: LyricLine?, next: LyricLine?) = FloatingLyricsPill(context, maxWidth).apply {
        setLines(LyricsNow("song", current, next), animate = false)
        val spec = View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST)
        measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun LinearLayout.lineView(i: Int) = getChildAt(i) as TextView

    /** The pills over a wallpaper-like backdrop, one under another. */
    private fun save(pills: List<FloatingLyricsPill>) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val width = context.resources.displayMetrics.widthPixels
        val height = pills.sumOf { it.height + pad } + pad
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF5B7FA6.toInt(), 0xFFD9A07A.toInt(), Shader.TileMode.CLAMP)
        })
        var y = pad
        for (p in pills) {
            canvas.save()
            canvas.translate(pad.toFloat(), y.toFloat())
            p.draw(canvas)
            canvas.restore()
            y += p.height + pad
        }
        File("build/screenshots/floating-lyrics.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun line(text: String, start: Long = 0, rtl: Boolean? = null) =
        LyricLine(start, start + 1_000, text, listOf(LyricWord(text, start, start + 1_000)), wordSynced = true, rtl = rtl)
}
