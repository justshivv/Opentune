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
        // With a song loaded: the song in the top bar, the lyrics and queue column beside the page, the full player.
        val song = com.opentune.data.model.Song(
            "4NRXx6U8ABQ", "Blinding Lights", "The Weeknd",
            "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg",
            albumName = "After Hours",
        )
        Player.cue(listOf(song, song.copy(videoId = "XXYlFuWEuKI", title = "Save Your Tears")), 0)
        shoot("desktop-wide-song", 1440, 900, 9_000) { DesktopApp() }
        shoot("desktop-narrow-song", 640, 900, 9_000) { DesktopApp() }
        shoot("desktop-wide-lyrics", 1700, 1000, 9_000) { DesktopApp(startPanel = SidePanel.LYRICS) }
        shoot("desktop-wide-queue", 1700, 1000, 6_000) { DesktopApp(startPanel = SidePanel.QUEUE) }
        shoot("desktop-player", 640, 1000, 6_000) { DesktopTheme { NowPlaying(song, docked = false, onClose = {}) } }
        shoot("desktop-sources", 900, 1100) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) { SourcesSection() }
                }
            }
        }
        // A library with some listening in it: the speed dial, mixes, year card and top lists fill in.
        Library.useFile(java.nio.file.Files.createTempDirectory("shots").resolve("desktop.json").toFile())
        val listened = listOf(
            "4NRXx6U8ABQ" to ("Blinding Lights" to "The Weeknd"), "XXYlFuWEuKI" to ("Save Your Tears" to "The Weeknd"),
            "JGwWNGJdvx8" to ("Shape of You" to "Ed Sheeran"), "lp-EO5I60KA" to ("Thinking Out Loud" to "Ed Sheeran"),
            "OPf0YbXqDm0" to ("Uptown Funk" to "Mark Ronson, Bruno Mars"), "hT_nvWreIhg" to ("Counting Stars" to "OneRepublic"),
            "60ItHLz5WEA" to ("Faded" to "Alan Walker"), "YQHsXMglC9A" to ("Hello" to "Adele"),
            "RgKAFK5djSk" to ("See You Again" to "Wiz Khalifa, Charlie Puth"), "CevxZvSJLk8" to ("Roar" to "Katy Perry"),
            "fRh_vgS2dFE" to ("Sorry" to "Justin Bieber"), "kJQP7kiw5Fk" to ("Despacito" to "Luis Fonsi"),
        ).map { (id, t) -> com.opentune.data.model.Song(id, t.first, t.second, "https://i.ytimg.com/vi/$id/hqdefault.jpg", "3:30") }
        listened.forEachIndexed { i, s -> repeat(listened.size - i) { Library.played(s) } }
        listened.take(5).forEach { Library.setLiked(it, true) }
        Library.createPlaylist("Late night drive").also { id -> listened.take(6).forEach { Library.addToPlaylist(id, it) } }
        Library.createPlaylist("Gym").also { id -> listened.drop(4).forEach { Library.addToPlaylist(id, it) } }
        shoot("desktop-home-full", 1600, 1800, 12_000) { DesktopApp() }
        shoot("desktop-now-playing", 1700, 1000, 12_000) { DesktopApp(startPanel = SidePanel.NOW) }
        shoot("desktop-artist", 1400, 1300, 12_000) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    ArtistView(com.opentune.data.model.ShelfItem("The Weeknd", "Artist", null, null, "UClYV6hHlupm_S_ObS1W-DYw"), onOpen = {})
                }
            }
        }
        shoot("desktop-artist-signed", 1400, 900, 12_000) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    ArtistView(com.opentune.data.model.ShelfItem("Real Boss", "Artist", null, null, "UCnotarealchannel"), onOpen = {})
                }
            }
        }
        shoot("desktop-album", 1400, 1100, 12_000) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    CollectionPage(com.opentune.data.model.ShelfItem("After Hours", "Album • The Weeknd • 2020", "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg", null, "MPREb_ZlRCjVWDsrE"))
                }
            }
        }
        shoot("desktop-search", 1400, 1000, 10_000) {
            DesktopTheme { Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { SearchPage("", {}, 0, {}, {}) } }
        }
        shoot("desktop-settings", 1400, 1000, 4_000) {
            DesktopTheme { Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { SettingsPage() } }
        }
        shoot("desktop-liked", 1400, 1000, 6_000) {
            DesktopTheme { Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { LibraryPage(Shelf.LIKED, {}, {}) } }
        }
        shoot("desktop-new", 1400, 1300, 12_000) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { NewPage(onOpen = {}) }
            }
        }
        shoot("desktop-library", 1400, 2000, 8_000) {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    LibraryHome(onShelf = {}, onPlaylist = {}, onOpenAny = {})
                }
            }
        }
        shoot("desktop-qr", 300, 300, 300) { DesktopTheme { Box(Modifier.padding(20.dp)) { QrCode("opentune://addon-approve?id=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ", 180.dp) } } }
    }
}
