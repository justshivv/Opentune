package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frames of the opening over a stand-in page, to build/screenshots/opening. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class OpeningRevealScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun frames() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var dive by remember { mutableFloatStateOf(0f) }
            var on by remember { mutableStateOf(true) }
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().graphicsLayer { val s = 1.14f - 0.14f * dive; scaleX = s; scaleY = s }
                        .background(Color(0xFF1C1B22)).padding(24.dp),
                ) {
                    Text("Good evening", color = Color.White, fontSize = 34.sp, modifier = Modifier.padding(top = 60.dp, bottom = 20.dp))
                    listOf(0xFFE0457B, 0xFF3A7BD5, 0xFF2BB673, 0xFFF2A93B).forEach { c ->
                        Box(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp).background(Color(c), RoundedCornerShape(16.dp)))
                    }
                }
                if (on) OpeningReveal(onDive = { dive = it }, onDone = { on = false })
            }
        }
        var at = 0L
        listOf(0L, 300, 700, 900, 1100, 1250, 1400, 1550, 1700).forEach { t ->
            compose.mainClock.advanceTimeBy(t - at)
            at = t
            save("opening/${"%04d".format(t)}")
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
