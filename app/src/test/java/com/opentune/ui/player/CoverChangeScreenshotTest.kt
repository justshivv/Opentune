package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.model.Song
import com.opentune.data.settings.CoverChange
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Each way the cover can change, caught part-way through, to build/screenshots/cover. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CoverChangeScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val songs = listOf(
        Song("a", "First", "Artist", "https://covers.invalid/red.jpg", "3:00"),
        Song("b", "Second", "Artist", "https://covers.invalid/blue.jpg", "3:00"),
    )

    @Before fun setUp() {
        val context = compose.activity
        com.opentune.data.settings.AppSettings.init(context)
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ it is String && it.contains("red") }, android.graphics.drawable.ColorDrawable(0xFFE0457B.toInt()).asImage())
            .intercept({ it is String && it.contains("blue") }, android.graphics.drawable.ColorDrawable(0xFF3A7BD5.toInt()).asImage())
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.coroutineContext(Dispatchers.Unconfined).build())
    }

    @Test fun eachChange() {
        var change by mutableStateOf(CoverChange.FADE)
        var index by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF15131A)).padding(40.dp), contentAlignment = Alignment.Center) {
                    ArtworkPane(songs[index], isPlaying = true, onSwipeNext = {}, onSwipePrevious = {}, change = change, index = index)
                }
            }
        }
        CoverChange.entries.forEach { c ->
            // Changed on the UI thread so each round's state reaches the screen at once.
            compose.runOnUiThread { change = c; index = 0 }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
            save("cover/${c.name.lowercase()}-before")
            compose.runOnUiThread { index = 1 }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            var at = 0L
            listOf(0L, 100, 200, 300, 450, 800).forEach { t ->
                compose.mainClock.advanceTimeBy(t - at)
                at = t
                save("cover/${c.name.lowercase()}-${"%03d".format(t)}")
            }
            compose.mainClock.advanceTimeBy(1_500)
        }
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
