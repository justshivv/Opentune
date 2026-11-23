package com.opentune.ui.home

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home with a shelf of albums and a shelf of music videos, drawn to build/screenshots/home/video-shelf.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h640dp-xxhdpi", application = android.app.Application::class)
class VideoShelfScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /** A made-up frame: a sky from [top] to [bottom], so the title is tried on light and dark ones. */
    private fun frame(top: Int, bottom: Int): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawRect(0f, 0f, 640f, 360f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 360f, top, bottom, Shader.TileMode.CLAMP) })
        canvas.drawCircle(470f, 230f, 90f, Paint().apply { color = 0xFF1B1B22.toInt(); isAntiAlias = true })
        return BitmapDrawable(compose.activity.resources, bitmap)
    }

    @Test fun homeWithAVideoShelf() {
        val frames = mapOf(
            "vidAAAAAAAA" to frame(0xFFF7F4EC.toInt(), 0xFFE6B980.toInt()),
            "vidBBBBBBBB" to frame(0xFF3A7BD5.toInt(), 0xFF00D2FF.toInt()),
            "vidCCCCCCCC" to frame(0xFFE0457B.toInt(), 0xFF2C3E50.toInt()),
        )
        val covers = listOf(0xFF8E44AD, 0xFF16A085, 0xFFD35400)
        val engine = FakeImageLoaderEngine.Builder().apply {
            frames.forEach { (id, d) -> intercept({ it is String && "/vi/$id/hq720" in it }, d.asImage()) }
            covers.forEachIndexed { i, c -> intercept({ it is String && "/cover$i" in it }, ColorDrawable(c.toInt()).asImage()) }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        val albums = HomeShelf(
            "Albums for you",
            listOf("Hurry Up, We're Dreaming" to "M83", "Melodrama" to "Lorde", "Blonde" to "Frank Ocean").mapIndexed { i, (t, a) ->
                ShelfItem(t, "Album • $a", "https://lh3.googleusercontent.com/cover$i=w544-h544", null, "MPREb_album$i")
            },
        )
        val videos = HomeShelf(
            "Music videos for you",
            listOf(
                Triple("vidAAAAAAAA", "Midnight City (Official Video)", "M83 • 214M views"),
                Triple("vidBBBBBBBB", "Green Light (Live on the Late Show with a much longer title)", "Lorde • 12M views"),
                Triple("vidCCCCCCCC", "Nights", "Frank Ocean • 40M views"),
            ).map { (id, t, s) -> ShelfItem(t, s, "https://i.ytimg.com/vi/$id/hqdefault.jpg?sqp=abc", id, null, video = true) },
            videos = true,
        )
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), background = Color(0xFF0B0B0E))) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        HomeShelfView(albums, {})
                        HomeShelfView(videos, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/home/video-shelf.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun framesTryTheFullSizeOneFirst() {
        // A small YouTube frame: the 1280x720 one first, then it, then the one every video has.
        assertEquals(
            listOf("https://i.ytimg.com/vi/abc/hq720.jpg", "https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=x", "https://i.ytimg.com/vi/abc/mqdefault.jpg"),
            videoFrames("abc", "https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=x"),
        )
        // Already the full-size one.
        assertEquals(
            listOf("https://i.ytimg.com/vi/abc/hq720.jpg", "https://i.ytimg.com/vi/abc/mqdefault.jpg"),
            videoFrames("abc", "https://i.ytimg.com/vi/abc/hq720.jpg"),
        )
        // Not YouTube's: taken as it is.
        assertEquals(listOf("https://example.com/frame.jpg"), videoFrames("abc", "https://example.com/frame.jpg"))
    }
}
