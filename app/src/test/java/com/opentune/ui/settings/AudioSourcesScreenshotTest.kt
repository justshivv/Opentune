package com.opentune.ui.settings

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.addons.AddonManifest
import com.opentune.data.addons.AddonStore
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PageLayout
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Audio sources page in the Poster layout, drawn to build/screenshots: rows flat on the page, one hairline between. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class AudioSourcesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun posterSources() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppSettings.init(context)
        AppSettings.updateUi { it.copy(pageLayout = PageLayout.POSTER) }
        AddonStore.init(context)
        AddonStore.add("https://first.example.com", AddonManifest("a", "Lossless Bridge", "1"))
        AddonStore.add("https://second.example.com", AddonManifest("b", "Asphyx Monochrome", "1"))
        AddonStore.add("https://third.example.com", AddonManifest("c", "Vengeance Lossless", "1"))
        compose.setContent { MaterialTheme(darkColorScheme()) { AudioAddonsPage(onDismiss = {}, onAdd = {}, notice = null, onNotice = {}) } }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/audio-sources-poster.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
