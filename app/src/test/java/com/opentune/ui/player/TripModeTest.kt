package com.opentune.ui.player

import android.graphics.Bitmap
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

    @Test fun aSurgeIsTheWholeSoundRisingAboveWhereItHasBeen() {
        val dt = 1f / 60f
        val s = TripSignal()
        // Ten quiet seconds, then the chorus comes in loud.
        for (frame in 0 until 600) s.step(AudioLevels.Level(0.06f, 0.03f), dt)
        assertTrue("quiet is no surge", !s.surged)
        var surged = false
        for (frame in 0 until 60) { s.step(AudioLevels.Level(0.34f, 0.10f), dt); surged = surged || s.surged }
        assertTrue("the loud part is a surge", surged)
        // Loud all along is not.
        val loud = TripSignal()
        var any = false
        for (frame in 0 until 1200) { loud.step(AudioLevels.Level(0.34f, 0.10f), dt); if (frame > 900 && loud.surged) any = true }
        assertTrue("steady loud is no surge", !any)
    }

    @Test fun aNewLookComesOnItsOwnAndMeltsIn() {
        val stage = TripStage(kotlin.random.Random(5)) { null }
        val first = stage.a
        var sawMelt = false
        // 70 seconds: more than the longest wait for the next look.
        for (frame in 0 until 4200) {
            stage.step(1f / 60f, 1f, surged = false)
            if (stage.b != null) { sawMelt = true; assertTrue(stage.mix in 0f..1f) }
        }
        assertTrue("it melted into another design", sawMelt)
        assertTrue("and the design changed", stage.a !== first)
        // Each design's clock restarts, so it stays small however long it plays.
        assertTrue(stage.timeA < 70f)
    }

    @Test fun newLookAsksForOneAtOnce() {
        val stage = TripStage(kotlin.random.Random(9)) { null }
        assertEquals(null, stage.b)
        stage.change()
        assertTrue(stage.b != null)
        assertEquals(0f, stage.mix, 0f)
    }

    @Test fun aSurgeBringsTheNextLookSoonerButNotRightAfterOne() {
        val stage = TripStage(kotlin.random.Random(3)) { null }
        for (frame in 0 until 300) stage.step(1f / 60f, 1f, surged = true) // 5 s in: too soon
        assertEquals(null, stage.b)
        for (frame in 0 until 600) stage.step(1f / 60f, 1f, surged = true) // 15 s in: ready
        assertTrue(stage.b != null)
    }

    @Test fun drawsTheSimpleTrip() {
        // It moves every frame for as long as it's open, so the clock is stepped by hand rather than waited out.
        compose.mainClock.autoAdvance = false
        compose.setContent { TripMode("Blinding Lights", "The Weeknd", null, onExit = {}, forceSimple = true, seed = 7) }
        compose.mainClock.advanceTimeBy(1_000)
        val view = ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/trip.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
