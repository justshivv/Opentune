package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class WhatsNewTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun picksTheVersionsSinceLastSeen() {
        assertEquals(listOf("0.3.7", "0.3.6"), WhatsNew.since("0.3.5", "0.3.7").map { it.first })
        assertEquals(listOf("0.3.7"), WhatsNew.since("0.3.6", "0.3.7").map { it.first })
        // A version with no notes of its own still shows the ones before it it hasn't seen.
        assertEquals(listOf("0.3.9", "0.3.8", "0.3.7"), WhatsNew.since("0.3.6", "0.3.10").map { it.first })
        assertEquals(emptyList<String>(), WhatsNew.since("0.3.7", "0.3.7").map { it.first })
        assertEquals(1, WhatsNew.compare("0.3.10", "0.3.9"))
        assertEquals(0, WhatsNew.compare("0.3", "0.3.0"))
    }

    @Test fun sheet() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) {
                WhatsNewSheet(WhatsNew.since("0.3.5", "0.3.7"), onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        val sheet = ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(sheet.width, sheet.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(16, 14, 20))
        sheet.draw(canvas)
        File("build/screenshots/whatsnew/sheet.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
