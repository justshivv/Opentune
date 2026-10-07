package com.opentune.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.opentune.data.settings.AudioQuality
import com.opentune.playback.SleepTimer
import com.opentune.ui.components.ChoiceSheet
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.player.RemixSheet
import com.opentune.ui.player.SleepTimerDialog
import androidx.compose.material3.Text
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Draws the option picker to build/screenshots, and checks a pick lands and closes it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ChoiceSheetScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun pick() {
        var quality by mutableStateOf(AudioQuality.entries.first())
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) {
                Surface(color = Color(0xFF0B0B0E)) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A2A4A), Color(0xFF101014)))))
                    if (open) {
                        ChoiceSheet(
                            title = "Quality on Wi-Fi",
                            options = AudioQuality.entries,
                            selected = quality,
                            label = { "${it.label} · ${it.summary}" },
                            onSelect = { quality = it },
                            onDismiss = { open = false },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        save("choice_sheet")
        val target = AudioQuality.entries.last()
        compose.onNodeWithText(target.label).performClick()
        compose.waitForIdle()
        assertEquals(target, quality)
        assertTrue(!open)
    }

    @Test fun sleepTimer() {
        SleepTimer.startMinutes(30)
        SleepTimer.addMinutes(-12)
        show { SleepTimerDialog(onDismiss = {}) }
        save("sleep_timer")
        SleepTimer.cancel()
    }

    @Test fun confirm() {
        show {
            FloatingDialog(
                onDismissRequest = {},
                title = { Text("Delete playlist?") },
                text = { Text("Late night drive and its 42 songs go from this phone. Songs you saved stay saved.") },
                confirmButton = { SheetButton("Delete", tone = SheetTone.Danger, onClick = {}) },
                dismissButton = { SheetButton("Cancel", onClick = {}, closes = true) },
            )
        }
        save("confirm_dialog")
    }

    @Test fun remix() {
        show { RemixSheet(onDismiss = {}) }
        save("remix_sheet")
    }

    /** The song menu opens at half height, and dragging its top takes it up. */
    @Test fun songMenuHalfThenFull() {
        com.opentune.data.library.LibraryStore.init(compose.activity)
        val song = com.opentune.data.model.Song("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", null, "3:20")
        val menu = com.opentune.ui.components.SongMenuActions({}, {}, {}, {}, {}, dislike = {}, playVideoVersion = {})
        show {
            androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongMenu provides menu) {
                com.opentune.ui.components.SongMenuSheet(song.copy(artistId = "UC1", albumId = "MPRE1"), onDismiss = {})
            }
        }
        save("song_menu_half")
        val cardTop = { compose.onNodeWithText("Blinding Lights").fetchSemanticsNode().boundsInRoot.top }
        val before = cardTop()
        compose.onNodeWithText("Blinding Lights").performTouchInput { swipeUp(startY = centerY, endY = centerY - 900f, durationMillis = 300) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        save("song_menu_full")
        assertTrue("the menu should grow when its top is dragged up ($before -> ${cardTop()})", cardTop() < before - 200f)
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), onPrimary = Color(0xFF3A1206))) {
                Surface(color = Color(0xFF0B0B0E)) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A2A4A), Color(0xFF101014)))))
                    content()
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val back = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        back.draw(canvas)
        canvas.drawColor(android.graphics.Color.argb(90, 0, 0, 0))
        val sheet = ShadowDialog.getLatestDialog().window!!.decorView
        canvas.save()
        canvas.translate(0f, (back.height - sheet.height).toFloat())
        sheet.draw(canvas)
        canvas.restore()
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
