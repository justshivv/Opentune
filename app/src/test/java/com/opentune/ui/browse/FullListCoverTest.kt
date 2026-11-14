package com.opentune.ui.browse

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class FullListCoverTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun wideArtworkKeepsBothEdgesInsteadOfCroppingThemOff() {
        val context = compose.activity
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 40f, 100f, Paint().apply { color = Color.RED })
            drawRect(160f, 0f, 200f, 100f, Paint().apply { color = Color.GREEN })
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder().default(BitmapDrawable(context.resources, source).asImage()).build())
        }.coroutineContext(Dispatchers.Unconfined).build())
        compose.setContent { MaterialTheme { Box(Modifier.size(300.dp)) { FullListCover("https://covers.invalid/wide") } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(frame))
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        assertTrue("left edge of cover remains visible", pixels.count { Color.red(it) > 180 && Color.blue(it) < 50 && Color.green(it) < 50 } > 200)
        assertTrue("right edge of cover remains visible", pixels.count { Color.green(it) > 180 && Color.blue(it) < 50 && Color.red(it) < 50 } > 200)
    }
}
