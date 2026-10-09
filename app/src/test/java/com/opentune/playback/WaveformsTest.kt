package com.opentune.playback

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.opentune.ui.player.SeekBar
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h200dp-xxhdpi", application = android.app.Application::class)
class WaveformsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun fillsInAsItPlaysAndKeepsIt() {
        val context = compose.activity
        val wave = Waveforms.forSong(context, "stream-1")
        assertTrue(wave.bins.all { it == Waveforms.UNKNOWN })
        // The first half heard: louder toward the middle.
        for (ms in 0L until 100_000L step 250) Waveforms.observe(wave, ms, 200_000, 0.1f + ms / 400_000f)
        val known = wave.bins.count { it != Waveforms.UNKNOWN }
        assertEquals(Waveforms.BINS / 2, known)
        assertTrue(wave.bins[40] > wave.bins[5])
        // A quieter moment never lowers a step already heard louder.
        val before = wave.bins[10]
        Waveforms.observe(wave, 21_000, 200_000, 0f)
        assertEquals(before, wave.bins[10])
        // Kept on the phone, and read back the next time.
        Waveforms.write(wave)
        Waveforms.forgetLoaded()
        val again = Waveforms.forSong(context, "stream-1")
        assertEquals(known, again.bins.count { it != Waveforms.UNKNOWN })
        assertTrue(abs(again.bins[40] - wave.bins[40]) < 0.01f)
    }

    @Test fun bar() {
        val wave = Waveforms.Wave("w", FloatArray(Waveforms.BINS) { i -> if (i > 70) Waveforms.UNKNOWN else (0.3f + 0.7f * abs(sin(i * 0.37f)) * (0.4f + i / 120f)) })
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SeekBar({ 80_000L }, { 130_000L }, 200_000L, onSeek = {}, modifier = Modifier.fillMaxWidth().padding(24.dp), playing = true, waveform = wave)
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/waveform.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
