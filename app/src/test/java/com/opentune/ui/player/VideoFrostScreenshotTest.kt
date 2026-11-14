package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Canvas frost over a busy picture, from halfway down, drawn to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class VideoFrostScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /** Stripes and blobs in strong colours, so a blur shows plainly. */
    private fun picture(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt())
        for (i in 0 until 24) {
            p.color = colors[i % colors.size]
            c.drawRect(0f, i * h / 24f, w.toFloat(), (i + 1) * h / 24f, p)
        }
        p.color = 0xFFFFFFFF.toInt()
        for (i in 0 until 6) c.drawCircle(w * (0.15f + 0.14f * i), h * (0.3f + 0.1f * (i % 3)), w * 0.06f, p)
        return b
    }

    @Test fun frost() {
        lateinit var full: Bitmap
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(Modifier.fillMaxSize()) {
                val w = 1233; val h = 2673
                full = remember0 { picture(w, h) }
                Image(full.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                val frames = object : FrostFrames {
                    override fun size() = IntSize(w, h)
                    override fun copyInto(bitmap: Bitmap): Boolean {
                        android.graphics.Canvas(bitmap).drawBitmap(full, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        return true
                    }
                }
                VideoFrost(frames, playing = false, Modifier.fillMaxSize(), remember0 { mutableFloatStateOf(h * 0.45f) }, with(density) { 112f * this.density })
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/canvas-frost.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@androidx.compose.runtime.Composable
private fun <T> remember0(f: () -> T): T = androidx.compose.runtime.remember { f() }
