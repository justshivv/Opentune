package com.opentune.ui.browse

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.GlassPage
import com.opentune.data.settings.AppSettings
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h520dp-xxhdpi", application = android.app.Application::class)
class CollapsingBarScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /** Scrolled past the header, the bar shows the title over a strip that fades out below it. */
    @Test fun collapsedBar() {
        // Robolectric can't run Haze's blur, so this draws the reduced-blur wash and checks the layout.
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { it.copy(reduceBlur = true) }
        val hues = listOf(0xFFE0457B, 0xFF3A7BD5, 0xFF2BB673, 0xFFF2A93B)
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                val list = rememberLazyListState(initialFirstVisibleItemIndex = 2, initialFirstVisibleItemScrollOffset = 60)
                GlassPage(Modifier.fillMaxSize(), overlay = { CollapsingBar("Late night drive", list, onBack = {}) }) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items(20) { i ->
                            Box(Modifier.fillMaxWidth().height(72.dp).padding(8.dp).background(Color(hues[i % 4].toInt()))) {
                                Text("Song ${i + 1}", Modifier.padding(12.dp), color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/browse/collapsed-bar.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
