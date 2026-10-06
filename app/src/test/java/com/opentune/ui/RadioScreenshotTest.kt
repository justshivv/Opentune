package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.radio.Radio
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.radio.NearbyPrompt
import com.opentune.ui.radio.StationRow
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the radio "Near you" prompt and list to build/screenshots, from a saved Radio Browser answer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class RadioScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun nearYou() {
        val body = javaClass.classLoader!!.getResource("radio-browser-nearby.json")!!.readText()
        val found = Radio.parseNearby(body, 28.61, 77.21)
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) {
                androidx.compose.material3.Surface(color = Color(0xFF0B0B0E), contentColor = Color(0xFFECE6F0)) {
                    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) {
                        NearbyPrompt(refused = false, countryName = "India", onAllow = {}, onOpenSettings = {}, onCountry = {})
                        SectionHeader("Live near you", subtitle = "${found.size} stations within 36 km")
                        found.take(5).forEach { StationRow(it.station, false, {}, details = it.station.nearbyDetails(it.distanceKm)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "radio-near-you.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
