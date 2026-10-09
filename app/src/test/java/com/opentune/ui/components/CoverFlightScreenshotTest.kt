package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.ui.components.FlyingCover.card
import com.opentune.ui.components.FlyingCover.landing
import com.opentune.ui.components.FlyingCover.watchTaps
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tapped song's cover on its way into the now-playing card, frame by frame, to build/screenshots/flight. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CoverFlightScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun flies() {
        val engine = FakeImageLoaderEngine.Builder().intercept({ it is String }, android.graphics.drawable.ColorDrawable(0xFFE0457B.toInt()).asImage()).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        var song by mutableStateOf<String?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.watchTaps().fillMaxSize().background(Color(0xFF101014))) {
                    // A song row near the top of a list.
                    Box(Modifier.offset(y = 220.dp).padding(horizontal = 16.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E1E24)).testTag("row").clickable { song = "s1" })
                    // The now-playing card at the foot, its cover on the left.
                    Box(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().height(64.dp).card().clip(RoundedCornerShape(22.dp)).background(Color(0xFF2A2A33))) {
                        Box(Modifier.padding(10.dp).size(44.dp).landing().clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3A44)))
                    }
                    CoverFlight(song, "https://img/c", enabled = true)
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("row").performClick()
        var at = 0L
        listOf(16L, 120L, 260L, 420L, 600L).forEach { t ->
            compose.mainClock.advanceTimeBy(t - at)
            at = t
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File("build/screenshots/flight/${"%03d".format(t)}.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
