package com.opentune.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.NerdStats
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioFormatInfo
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pipeline lists BitChord's stages with the figures the player reports, PCM data rate included. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SignalPathScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun showsTheStagesAndFigures() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        NerdStats.onPlaybackSource(NerdStats.Source("Lossless Bridge", bits = 24, sampleRate = 48_000, kbps = 1855, mediaId = "x",
            selectionDetail = "Lossless Bridge: verified FLAC 24-bit/48 kHz\nQobuz Mirror: rate limited (HTTP 429)\nFLAC: no verified matching stream available\nJioSaavn: no matching 320 kbps recording (title, artists, album and duration)"))
        NerdStats.onPipeline { it.copy(decoderName = "c2.android.flac.decoder", decoderOutput = "24-bit PCM", outputSampleRateHz = 48_000, outputEncoding = "Float32", bufferMs = 120, bufferFrames = 5760) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                SignalPathDialog(AudioFormatInfo("FLAC", 1855, 48_000, 2, bitDepth = 24), onDismiss = {})
            }
        }
        // Into the run, so the light is on its way round the first rings.
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("TRACK INFO").assertIsDisplayed()
        compose.onNodeWithText("DECODER").assertIsDisplayed()
        compose.onNodeWithText("HOW IT WAS CHOSEN").assertIsDisplayed()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val image = android.graphics.Bitmap.createBitmap(sheet.width, sheet.height, android.graphics.Bitmap.Config.ARGB_8888)
        sheet.draw(android.graphics.Canvas(image))
        File("build/screenshots/signal-path.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun eachSourceSaysHowItWent() {
        val answers = sourceAnswers("Lossless Bridge: verified FLAC\nQobuz Mirror: rate limited (HTTP 429)\nFLAC: no verified matching stream available\nJioSaavn: no matching 320 kbps recording")
        org.junit.Assert.assertEquals(listOf("Lossless Bridge", "Qobuz Mirror", "Add-ons", "JioSaavn"), answers.map { it.who })
        org.junit.Assert.assertEquals(
            listOf(SourceAnswer.Outcome.CHOSEN, SourceAnswer.Outcome.TROUBLE, SourceAnswer.Outcome.NONE, SourceAnswer.Outcome.NONE),
            answers.map { it.outcome },
        )
    }
}
