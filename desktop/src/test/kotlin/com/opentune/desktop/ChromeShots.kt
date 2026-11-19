package com.opentune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pictures of the window's chrome (sidebar, top bar, the column beside the
 * page, the full player), drawn offscreen, for looking at a change to them
 * without a display. Off unless asked for:
 * ./gradlew :desktop:test --tests '*ChromeShots*' -Dopentune.shots=/some/folder
 */
class ChromeShots {
    private val folder = System.getProperty("opentune.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun shoot(name: String, width: Int, height: Int, waitMs: Long = 6_000, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1.5f)) { content() }
        val start = System.nanoTime()
        var at = 0L
        // Let loading finish and animations settle, drawing as time passes.
        while (at < waitMs) {
            scene.render(System.nanoTime() - start)
            Thread.sleep(50)
            at = (System.nanoTime() - start) / 1_000_000
        }
        val image = scene.render(System.nanoTime() - start)
        folder!!.mkdirs()
        File(folder, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test fun chrome() {
        assumeTrue(folder != null)
        AppSettings.init(appContext)
        AppSettings.updateTheme { it.copy(mode = ThemeMode.DARK) }
        shoot("chrome-empty", 1440, 900) { DesktopApp() }
        Library.useFile(java.nio.file.Files.createTempDirectory("chrome").resolve("desktop.json").toFile())
        val songs = listOf(
            "4NRXx6U8ABQ" to "Blinding Lights", "XXYlFuWEuKI" to "Save Your Tears", "JGwWNGJdvx8" to "Shape of You",
            "60ItHLz5WEA" to "Faded", "YQHsXMglC9A" to "Hello", "kJQP7kiw5Fk" to "Despacito",
        ).map { (id, t) -> com.opentune.data.model.Song(id, t, "The Weeknd", "https://i.ytimg.com/vi/$id/hqdefault.jpg", "3:20", albumName = "After Hours") }
        Library.createPlaylist("Late night drive").also { id -> songs.take(4).forEach { Library.addToPlaylist(id, it) } }
        Library.createPlaylist("Gym").also { id -> songs.drop(2).forEach { Library.addToPlaylist(id, it) } }
        Player.cue(songs, 0)
        shoot("chrome-wide-song", 1440, 900) { DesktopApp() }
        shoot("chrome-narrow-song", 640, 900) { DesktopApp() }
        shoot("chrome-wide-now", 1700, 1000, 9_000) { DesktopApp(startPanel = SidePanel.NOW) }
        shoot("chrome-wide-lyrics", 1700, 1000, 9_000) { DesktopApp(startPanel = SidePanel.LYRICS) }
        shoot("chrome-wide-queue", 1700, 1000) { DesktopApp(startPanel = SidePanel.QUEUE) }
        shoot("chrome-player", 640, 1000) { DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}) } }
        shoot("chrome-player-wide", 1700, 1000, 9_000) { DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}) } }
        AppSettings.updateTheme { it.copy(mode = ThemeMode.LIGHT) }
        try {
            shoot("chrome-light-queue", 1700, 1000) { DesktopApp(startPanel = SidePanel.QUEUE) }
        } finally {
            AppSettings.updateTheme { it.copy(mode = ThemeMode.DARK) }
        }
    }
}
