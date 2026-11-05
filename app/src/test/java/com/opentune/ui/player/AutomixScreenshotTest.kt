package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.playback.MixState
import com.opentune.playback.mix.MixPlan
import com.opentune.playback.mix.Wave
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Automix transition view, with made-up songs, as a picture to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class AutomixScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun wave(startMs: Long, endMs: Long, fadeAt: Float) = Wave(startMs, endMs, FloatArray(240) { i ->
        val beat = abs(sin(i * 0.9f)) * 0.25f
        val body = 0.65f + beat
        val fade = if (i / 240f > fadeAt) (1f - (i / 240f - fadeAt) / (1f - fadeAt)).coerceIn(0.05f, 1f) else 1f
        body * fade
    })

    @Test fun transition() {
        val plan = MixPlan(mixAtMs = 230_000, outEndMs = 238_000, inFromMs = 400, blendMs = 7_500, beatMs = 468.75, outBeatMs = 230_000, inBeatMs = 400, bassSwap = true)
        MixState.picture(
            MixState.Picture(
                MixState.Side("Live It Up", "Jennifer Lopez", null, 243_000, 128.0, wave(198_000, 243_000, 0.75f)),
                MixState.Side("Whatever It Takes", "Imagine Dragons", null, 201_000, 135.0, wave(0, 30_000, 2f)),
                plan, automix = true,
            ),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) { AutomixSheet(onDismiss = {}) }
        }
        compose.mainClock.advanceTimeBy(1_500)
        val global = Class.forName("android.view.WindowManagerGlobal")
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(global.getMethod("getInstance").invoke(null)) as List<*>
        val view = views.filterIsInstance<android.view.View>().last { it.isAttachedToWindow }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File("build/screenshots/player/automix.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
