package com.opentune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pictures of the window, drawn offscreen, for looking at a change without a
 * display. Off unless asked for: ./gradlew :desktop:test -Dopentune.shots=/some/folder
 */
class DesktopShots {
    private val folder = System.getProperty("opentune.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun shoot(name: String, width: Int, height: Int, waitMs: Long = 2_500, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1.5f)) { content() }
        val start = System.nanoTime()
        var at = 0L
        // Let loading finish and animations settle, drawing as time passes.
        while (at < waitMs) {
            scene.render((System.nanoTime() - start))
            Thread.sleep(50)
            at = (System.nanoTime() - start) / 1_000_000
        }
        val image = scene.render(System.nanoTime() - start)
        folder!!.mkdirs()
        File(folder, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test fun windows() {
        assumeTrue(folder != null)
        AppSettings.init(appContext)
        AppSettings.updateTheme { it.copy(mode = com.opentune.data.settings.ThemeMode.DARK) }
        shoot("desktop-wide", 1280, 800, 9_000) { DesktopApp() }
        shoot("desktop-narrow", 640, 900, 9_000) { DesktopApp() }
        // With a song loaded: the docked player beside the pages, the mini player over the dock, the full player.
        val song = com.opentune.data.model.Song(
            "4NRXx6U8ABQ", "Blinding Lights", "The Weeknd",
            "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg",
            albumName = "After Hours",
        )
        Player.cue(listOf(song, song.copy(videoId = "XXYlFuWEuKI", title = "Save Your Tears")), 0)
        shoot("desktop-wide-song", 1440, 900, 9_000) { DesktopApp() }
        shoot("desktop-narrow-song", 640, 900, 9_000) { DesktopApp() }
        shoot("desktop-player", 640, 1000, 6_000) { DesktopTheme { NowPlaying(song, docked = false, onClose = {}) } }
        shoot("desktop-sources", 900, 1100) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) { SourcesSection() }
                }
            }
        }
        shoot("desktop-qr", 300, 300, 300) { DesktopTheme { Box(Modifier.padding(20.dp)) { QrCode("opentune://addon-approve?id=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ", 180.dp) } } }
    }
}
