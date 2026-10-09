package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h520dp-xxhdpi", application = android.app.Application::class)
class PlaylistCoverScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun covers() {
        assertEquals(gradientFor("Road trip"), gradientFor("road TRIP"))
        val colors = listOf(0xFFE0457B, 0xFF3A7BD5, 0xFF2BB673, 0xFFF2A93B)
        val engine = FakeImageLoaderEngine.Builder().apply {
            colors.forEachIndexed { i, c -> intercept({ it is String && it.contains("/c$i") }, android.graphics.drawable.ColorDrawable(c.toInt()).asImage()) }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        val four = (0..3).map { "https://img/c$it" }
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF101014)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlaylistCover("Late night drive", four, Modifier.size(180.dp))
                        PlaylistCover("Gym", listOf("https://img/c1"), Modifier.size(180.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlaylistCover("Late night drive", four, Modifier.size(56.dp))
                        PlaylistCover("Gym", emptyList(), Modifier.size(56.dp))
                        PlaylistCover("Road trip", emptyList(), Modifier.size(56.dp))
                        PlaylistCover("Monsoon", emptyList(), Modifier.size(56.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/library/playlist-covers.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
