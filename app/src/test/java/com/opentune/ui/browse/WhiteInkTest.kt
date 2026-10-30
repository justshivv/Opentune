package com.opentune.ui.browse

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A signature comes out as white ink on clear, whatever it was drawn in. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class WhiteInkTest {
    private fun ink(bitmap: Bitmap) = runBlocking { WhiteInk.transform(bitmap, coil3.size.Size.ORIGINAL) }

    @Test fun darkInkOnClearTurnsWhiteAndKeepsItsShape() {
        val b = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        for (x in 0 until 20) b.setPixel(x, 5, Color.argb(200, 10, 10, 10))
        val out = ink(b)
        assertEquals(Color.argb(200, 255, 255, 255), out.getPixel(3, 5))
        assertEquals(0, Color.alpha(out.getPixel(3, 2)))
    }

    @Test fun inkOnPaperLosesThePaper() {
        val b = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        for (x in 0 until 20) b.setPixel(x, 5, Color.BLACK)
        val out = ink(b)
        assertTrue(Color.alpha(out.getPixel(3, 5)) > 240)
        assertTrue(Color.alpha(out.getPixel(3, 2)) < 10)
        assertEquals(255, Color.red(out.getPixel(3, 5)))
    }
}
