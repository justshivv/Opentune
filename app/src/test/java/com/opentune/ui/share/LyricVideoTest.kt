package com.opentune.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A lyric video's frames: the timing of the part, the lines lighting as they're sung, the
 * backgrounds moving. Frames are written to build/lyric-video for a look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class LyricVideoTest {
    private val out = File("build/lyric-video").apply { mkdirs() }

    private fun cover(): Bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, intArrayOf(Color.rgb(150, 30, 120), Color.rgb(230, 120, 40), Color.rgb(30, 60, 140)), null, Shader.TileMode.CLAMP) })
    }

    /** A line sung word by word, each word a third of the line. */
    private fun line(text: String, start: Long, end: Long): LyricLine {
        val parts = text.split(" ")
        val each = (end - start) / parts.size
        val words = parts.mapIndexed { i, w -> LyricWord(w, start + i * each, start + (i + 1) * each) }
        return LyricLine(start, end, text, words, wordSynced = true)
    }

    private val lines = listOf(
        line("Mere dil naal laare ni tu laun waliye", 10_000, 13_000),
        line("हो, पाछे घूम-घूम तेरे पढ्या रंग काळा", 13_000, 16_500),
        line("Dil naal laare ni tu laun waliye", 16_500, 19_000),
        LyricLine(19_000, 0, "Dil tera jado tutuga tan", emptyList(), false),
    )

    private fun frame(v: LyricVideo, t: Long, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width / 2, v.height / 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(0.5f, 0.5f)
        v.draw(c, t)
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    @Test fun thePartRunsFromJustBeforeTheFirstLineToJustAfterTheLast() {
        val span = LyricVideo.span(lines)
        assertEquals(4, span.size)
        // A line with no end of its own ends three seconds on.
        assertEquals(22_000L, span.last().endMs)
        assertEquals(9_300L, LyricVideo.startOf(span))
        assertEquals(22_000L + 1_200 - 9_300, LyricVideo.lengthOf(span))
        // Never more than thirty seconds: lines past that are left out.
        val long = (0 until 20).map { line("Line $it of many", it * 4_000L, it * 4_000L + 3_000) }
        val cut = LyricVideo.span(long)
        assertTrue(LyricVideo.lengthOf(cut) <= LyricVideo.MAX_MS)
        assertTrue(cut.size < long.size)
    }

    @Test fun framesMoveAndTheSungLineLights() {
        val span = LyricVideo.span(lines)
        val v = LyricVideo(span, cover(), null, "Excuses", "AP Dhillon, Gurinder Gill", LyricVideo.Layout.STORY, showLogo = true)
        val start = frame(v, 0, "story-0-start")
        val first = frame(v, 1_900, "story-1-first-line-half-sung")
        val second = frame(v, 4_400, "story-2-hindi-line")
        frame(v, 7_900, "story-3-third")
        frame(v, v.lengthMs - 100, "story-4-end")
        // The fade in from black: the first frame is darker than one a second in.
        assertTrue(brightness(start) < brightness(first))
        // The background flows and the lines move: frames apart differ.
        assertNotEquals(pixels(first).contentHashCode(), pixels(second).contentHashCode())
    }

    @Test fun theCardLayoutAndAnAnimatedCoverDraw() {
        val span = LyricVideo.span(lines)
        // Frames of a pretend clip: colours that change, as a moving cover's would.
        val clip = (0 until 6).map { i -> Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40 * i, 80, 200 - 30 * i)) } }
        val motion = MotionFrames(clip, 12f)
        val v = LyricVideo(span, cover(), motion, "Laado", "MC SQUARE & Hiten", LyricVideo.Layout.CARD, showLogo = true)
        val a = frame(v, 2_000, "card-motion-a")
        val b = frame(v, 2_150, "card-motion-b")
        assertNotEquals(pixels(a).contentHashCode(), pixels(b).contentHashCode())
        frame(LyricVideo(span, null, null, "No cover", "Someone", LyricVideo.Layout.CARD, showLogo = false), 5_000, "card-no-cover")
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun brightness(b: Bitmap) = pixels(b).sumOf { (Color.red(it) + Color.green(it) + Color.blue(it)).toLong() }
}
