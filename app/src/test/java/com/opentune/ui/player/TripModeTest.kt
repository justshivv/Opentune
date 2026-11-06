package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.playback.AudioLevels
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

/** Trip mode hears the kick drum, and draws; the simpler visual to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TripModeTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun hearsTheKickOnTheBeat() {
        val s = TripSignal()
        val dt = 1f / 60f
        var kicks = 0
        // 4 seconds at 120 BPM: a bass hit every half second, quiet between.
        for (frame in 0 until 240) {
            val t = frame * dt
            val onBeat = (t % 0.5f) < 0.06f
            val before = s.kick
            s.step(AudioLevels.Level(level = 0.2f, bass = if (onBeat) 0.12f else 0.02f), dt)
            if (s.kick == 1f && before < 1f) kicks++
        }
        assertTrue("kicks heard: $kicks", kicks in 7..9)
        // A steady bass with no hits is no kick.
        val steady = TripSignal()
        var any = 0
        for (frame in 0 until 240) { steady.step(AudioLevels.Level(0.2f, 0.06f), dt); if (frame > 60 && steady.kick == 1f) any++ }
        assertEquals(0, any)
    }

    @Test fun coloursAreBrightEvenFromADarkCover() {
        val c = tripColors(listOf(Color(0xFF101418)))
        assertEquals(3, c.size)
        assertTrue(c.all { (it.red + it.green + it.blue) / 3f > 0.25f })
    }

    @Test fun drawsTheSimpleTrip() {
        // It moves every frame for as long as it's open, so the clock is stepped by hand rather than waited out.
        compose.mainClock.autoAdvance = false
        compose.setContent { TripMode("Blinding Lights", "The Weeknd", null, onExit = {}, forceSimple = true) }
        compose.mainClock.advanceTimeBy(1_000)
        val view = ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/trip.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
