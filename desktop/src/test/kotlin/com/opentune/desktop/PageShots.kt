package com.opentune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pictures of the pages drawn as the web player's: a song's menu, an album's numbered rows,
 * the playlists grid and the shortcuts list. Off unless asked for, as [DesktopShots] is:
 * ./gradlew :desktop:test --tests '*PageShots*' -Dopentune.shots=/some/folder
 */
class PageShots {
    private val folder = System.getProperty("opentune.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun shoot(name: String, width: Int, height: Int, waitMs: Long = 2_500, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1.5f)) { content() }
        val start = System.nanoTime()
        var at = 0L
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

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        DesktopTheme {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalPageTop provides TOP_BAR_HEIGHT) { content() }
            }
        }
    }

    private val cover = "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg"

    @Test fun pages() {
        assumeTrue(folder != null)
        AppSettings.init(appContext)
        AppSettings.updateTheme { it.copy(mode = com.opentune.data.settings.ThemeMode.DARK) }

        shoot("page-menu", 900, 700) {
            Page {
                Box(Modifier.fillMaxSize()) {
                    WebMenu(MenuAnchor.At(IntOffset(120, 90)), onDismiss = {}, label = "Blinding Lights") {
                        WebMenuItem("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) {}
                        WebMenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) {}
                        WebMenuItem("Start radio", Icons.Rounded.Radio) {}
                        WebMenuSeparator()
                        WebMenuItem("Like", Icons.Rounded.FavoriteBorder) {}
                        WebMenuItem("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) {}
                        WebMenuSeparator()
                        WebMenuItem("Go to artist", Icons.Rounded.Person) {}
                        WebMenuItem("Go to album", Icons.Rounded.Album, enabled = false) {}
                        WebMenuSeparator()
                        WebMenuItem("Copy link", Icons.Rounded.Link) {}
                    }
                }
            }
        }

        // An album: every song with the album's cover, so the rows are numbered.
        val album = listOf("Alone Again", "Too Late", "Hardest To Love", "Scared To Live", "Snowchild", "Escape From LA", "Heartless", "Faith").mapIndexed { i, t ->
            Song("a${i.toString().padStart(10, '0')}", t, "The Weeknd", cover, "3:%02d".format((10 + i * 7) % 60), isExplicit = i % 3 == 1)
        }
        shoot("page-album-numbered", 1400, 1100, 4_000) {
            Page {
                CollectionLayout("After Hours", cover, album, failed = false) {
                    CollectionHero(
                        cover = { m -> CoverFill(cover, m) }, coverUrl = cover, kind = "Album · 2020", title = "After Hours", subtitle = "The Weeknd", songs = album,
                        extra = { ListMoreButton(album, "https://music.youtube.com/browse/x") },
                    )
                }
            }
        }

        Library.useFile(java.nio.file.Files.createTempDirectory("pageshots").resolve("desktop.json").toFile())
        val songs = listOf("4NRXx6U8ABQ", "XXYlFuWEuKI", "JGwWNGJdvx8", "lp-EO5I60KA", "OPf0YbXqDm0").mapIndexed { i, id ->
            Song(id, "Song $i", "Artist $i", "https://i.ytimg.com/vi/$id/hqdefault.jpg", "3:30", albumName = "Album $i")
        }
        Library.createPlaylist("Late night drive").also { id -> songs.forEach { Library.addToPlaylist(id, it) } }
        Library.createPlaylist("Gym").also { id -> songs.take(2).forEach { Library.addToPlaylist(id, it) } }
        Library.createPlaylist("Empty one")
        shoot("page-playlists", 1400, 800, 4_000) { Page { LibraryPage(Shelf.PLAYLISTS, {}, {}) } }
        val id = Library.playlists.value.first().id
        shoot("page-playlist", 1400, 1000, 4_000) { Page { PlaylistPage(id) {} } }
        shoot("page-shortcuts", 700, 900, 2_000) { Page { ShortcutsDialog {} } }
    }
}
