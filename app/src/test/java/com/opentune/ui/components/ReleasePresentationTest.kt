package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.UpdateCheck
import com.opentune.ui.settings.UpdateDialog
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class ReleasePresentationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun smallScreenLargeTextKeepsUpdateActionVisible() {
        val release = UpdateCheck.Release("0.4.16", "https://github.com/justshivv/Opentune/releases", "## Improvements\n" + (1..20).joinToString("\n") { "- Improvement $it with a readable description that wraps to fit your phone." }, UpdateCheck.Asset("app-arm64-v8a-release.apk", "https://example.com/app.apk", 30_000_000), null)
        compose.setContent { MaterialTheme(lightColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                UpdateDialog(release, onDismiss = {})
            }
        } }
        compose.onNodeWithText("Update WEAV").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.onNodeWithText("app-arm64-v8a-release.apk").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Update WEAV").assertIsDisplayed()
        capture("update-large-text")
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val view = ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/whatsnew/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
