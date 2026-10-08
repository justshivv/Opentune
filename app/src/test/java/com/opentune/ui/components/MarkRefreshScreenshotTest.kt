package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mark pull-to-refresh: part way, past the line, and loading, to build/screenshots/refresh. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MarkRefreshScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() = AppSettings.init(ApplicationProvider.getApplicationContext())

    @Test fun pull() {
        var refreshing by mutableStateOf(false)
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MarkRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true }, modifier = Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(12) { i ->
                                androidx.compose.foundation.layout.Box(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(72.dp)
                                        .background(Color(0xFF3A3640).copy(alpha = 0.5f + i % 3 * 0.15f), RoundedCornerShape(16.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(Offset(width / 2f, 400f)) }
        var y = 400f
        listOf(260f to "half", 520f to "past").forEach { (dy, name) ->
            repeat(10) { y += dy / 10; compose.onRoot().performTouchInput { moveTo(Offset(width / 2f, y)) }; compose.mainClock.advanceTimeBy(16) }
            compose.mainClock.advanceTimeBy(100)
            save("refresh/$name")
        }
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(700)
        save("refresh/loading")
        compose.mainClock.advanceTimeBy(350)
        save("refresh/loading-2")
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
