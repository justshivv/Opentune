package com.opentune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.opentune.data.account.SavedAccount
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pictures of the window's chrome (the sidebar, the top bar idle and playing, the account menu,
 * search as it's typed and once it's run, the column beside the page, the full player), drawn
 * offscreen, for looking at a change to them without a display. Off unless asked for:
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
        Library.useFile(java.nio.file.Files.createTempDirectory("chrome").resolve("desktop.json").toFile())
        // Nothing playing: the music note in the middle of the bar.
        shoot("chrome-empty", 1440, 900) { DesktopApp() }
        shoot("chrome-xwide-idle", 2400, 1400) { DesktopApp() }
        // Signed out, the menu offers Sign in.
        shoot("chrome-account-signed-out", 2400, 1400) { DesktopApp(startAccountMenu = true) }
        val songs = listOf(
            "4NRXx6U8ABQ" to "Blinding Lights", "XXYlFuWEuKI" to "Save Your Tears", "JGwWNGJdvx8" to "Shape of You",
            "60ItHLz5WEA" to "Faded", "YQHsXMglC9A" to "Hello", "kJQP7kiw5Fk" to "Despacito",
        ).map { (id, t) -> com.opentune.data.model.Song(id, t, "The Weeknd", "https://i.ytimg.com/vi/$id/hqdefault.jpg", "3:20", albumName = "After Hours") }
        listOf("Late night drive", "Gym", "Sunday morning coffee and slow songs for the rain", "Road trip", "Focus").forEachIndexed { i, name ->
            Library.createPlaylist(name).also { id -> songs.drop(i % 3).take(4).forEach { Library.addToPlaylist(id, it) } }
        }
        Player.cue(songs, 0)
        shoot("chrome-wide-song", 1440, 900) { DesktopApp() }
        shoot("chrome-xwide-song", 2400, 1400) { DesktopApp() }
        shoot("chrome-narrow-song", 640, 900) { DesktopApp() }
        // Signed in with two accounts, the first active.
        ChromeAccountsPreview.accounts = listOf(
            SavedAccount("a", "Alex Rivera", "alex.rivera@example.com", null, active = true),
            SavedAccount("b", "Night Owl Mixes", "nightowl@example.com", null, active = false),
        )
        try {
            shoot("chrome-account", 2400, 1400) { DesktopApp(startAccountMenu = true) }
        } finally {
            ChromeAccountsPreview.accounts = null
        }
        // Search: typed in the sidebar's field (suggestions, then the first results), then run.
        shoot("chrome-search-typing", 2400, 1400, 9_000) { DesktopApp(startSearch = "the weeknd") }
        shoot("chrome-search-results", 2400, 1400, 12_000) { DesktopApp(startSearch = "the weeknd", startSearched = true) }
        shoot("chrome-narrow-search", 900, 1100, 9_000) { DesktopApp(startSearch = "daft punk") }
        // The search page's rows with known data: a suggestion, songs, an artist and an album.
        shoot("chrome-result-rows", 1500, 800, 5_000) {
            DesktopTheme {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background, modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) {
                        ChromeSuggestionRow("the weeknd blinding lights", {}, {})
                        ChromeSongResultRow(songs[0], songs, 0)
                        ChromeSongResultRow(songs[1], songs, 1, kind = "Song")
                        ChromeItemResultRow(com.opentune.data.model.ShelfItem("The Weeknd", "Artist • 243M monthly audience", "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg", null, "UClYV6hHlupm_S_ObS1W-DYw")) {}
                        ChromeItemResultRow(com.opentune.data.model.ShelfItem("After Hours", "Album • The Weeknd • 2020", "https://i.ytimg.com/vi/XXYlFuWEuKI/hqdefault.jpg", null, "MPREb_ZlRCjVWDsrE")) {}
                    }
                }
            }
        }
        shoot("chrome-wide-now", 1700, 1000, 9_000) { DesktopApp(startPanel = SidePanel.NOW) }
        shoot("chrome-wide-lyrics", 1700, 1000, 9_000) { DesktopApp(startPanel = SidePanel.LYRICS) }
        shoot("chrome-wide-queue", 1700, 1000) { DesktopApp(startPanel = SidePanel.QUEUE) }
        shoot("chrome-xwide-queue", 2400, 1400) { DesktopApp(startPanel = SidePanel.QUEUE) }
        shoot("chrome-player", 640, 1000) { DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}) } }
        AppSettings.updateTheme { it.copy(mode = ThemeMode.LIGHT) }
        try {
            shoot("chrome-light-queue", 1700, 1000) { DesktopApp(startPanel = SidePanel.QUEUE) }
            shoot("chrome-light-xwide", 2400, 1400) { DesktopApp() }
        } finally {
            AppSettings.updateTheme { it.copy(mode = ThemeMode.DARK) }
        }
    }
}
