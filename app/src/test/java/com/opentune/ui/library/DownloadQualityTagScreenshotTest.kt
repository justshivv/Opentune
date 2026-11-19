package com.opentune.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.library.SongRef
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.SongListItem
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Downloads page's rows, each with its quality label, drawn to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class DownloadQualityTagScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun downloadRows() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        fun entry(title: String, path: String, source: String? = null, bits: Int? = null, rate: Int? = null, codec: String? = null, bytes: Long = 0) =
            DownloadEntry(SongRef.of(Song(title.take(11).padEnd(11, 'x'), title, "NMIXX", null, durationText = "3:20")), state = DownloadState.DONE,
                path = path, source = source, bits = bits, sampleRate = rate, codec = codec, bytes = bytes)
        val rows = listOf(
            entry("Run For Roses", "/d/a.flac", "Lossless Bridge", 24, 96_000, "flac"),
            entry("Dice", "/d/b.flac", "Lossless Bridge", 16, 44_100, "flac"),
            entry("Love Me Like This", "/d/c.m4a", codec = "ec+3"),
            entry("O.O", "/d/d.m4a", "JioSaavn · 320 kbps"),
            entry("Party O'Clock", "/d/e.webm", bytes = 3_200_000),
        )
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Surface {
                    Column(Modifier.fillMaxWidth()) {
                        rows.forEach { e -> SongListItem(song = e.song.toSong(), onClick = {}, tag = { DownloadQualityTag(e) }) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/download-quality-tags.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
