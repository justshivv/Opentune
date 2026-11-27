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
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Pictures of the content pages: a playlist and an album, Home, Explore, the Library's tabs and
 * its empty state, a song's menu and the dialogs. Off unless asked for, as [DesktopShots] is:
 * ./gradlew :desktop:test --tests '*PageShots*' -Dopentune.shots=/some/folder
 * Home, Explore and the album page are drawn from YouTube Music, so they need the network.
 */
class PageShots {
    private val folder = System.getProperty("opentune.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun shoot(name: String, width: Int, height: Int, waitMs: Long = 2_500, density: Float = 1f, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(density)) { content() }
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

    private val ids = listOf("4NRXx6U8ABQ", "XXYlFuWEuKI", "JGwWNGJdvx8", "lp-EO5I60KA", "OPf0YbXqDm0", "fHI8X4OXluQ", "kJQP7kiw5Fk", "60ItHLz5WEA")
    private val names = listOf(
        "Blinding Lights" to "The Weeknd", "Save Your Tears" to "The Weeknd", "Shape of You" to "Ed Sheeran", "Perfect" to "Ed Sheeran",
        "Uptown Funk" to "Mark Ronson, Bruno Mars", "Starboy" to "The Weeknd, Daft Punk", "Despacito" to "Luis Fonsi", "Faded" to "Alan Walker",
    )
    private val albums = listOf("After Hours", "After Hours", "÷ (Deluxe)", "÷ (Deluxe)", null, "Starboy", "VIDA", null)

    private fun songs(n: Int) = (0 until n).map { i ->
        val k = i % ids.size
        Song(
            if (i < ids.size) ids[k] else ids[k].dropLast(1) + ('a' + i % 26), names[k].first + if (i >= ids.size) " (Live)" else "", names[k].second,
            "https://i.ytimg.com/vi/${ids[k]}/hqdefault.jpg", "%d:%02d".format(3 + i % 2, (17 * i + 9) % 60), albumName = albums[k], isExplicit = i % 5 == 2,
        )
    }

    @Before fun setUp() {
        assumeTrue(folder != null)
        AppSettings.init(appContext)
        shotVault()
        AppSettings.updateTheme { it.copy(mode = com.opentune.data.settings.ThemeMode.DARK) }
    }

    @Test fun menus() {
        shoot("page-menu", 900, 700, density = 1.5f) {
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
        shoot("page-dialog-name", 700, 420, 1_500) { Page { PageNameDialog("New playlist", "Late night drive", "Create", {}) {} } }
        shoot("page-dialog-delete", 700, 420, 1_500) { Page { PageConfirmDialog("Delete Late night drive?", "The playlist goes; the songs stay wherever else you keep them.", "Delete", {}) {} } }
        shoot("page-shortcuts", 700, 900, 2_000, density = 1.5f) { Page { ShortcutsDialog {} } }
    }

    @Test fun collections() {
        Library.useFile(java.nio.file.Files.createTempDirectory("pageshots").resolve("desktop.json").toFile())
        val list = songs(14)
        val id = Library.createPlaylist("Late night drive")
        list.forEach { Library.addToPlaylist(id, it) }
        Library.setLiked(list[1], true)
        Library.setLiked(list[4], true)
        // Library keeps no albums, so the rows show a dash where the playlist page reads them back.
        shoot("page-playlist", 1660, 1100, 4_000) { Page { PlaylistPage(id) {} } }
        shoot("page-playlist-narrow", 760, 1000, 4_000) { Page { PlaylistPage(id) {} } }
        val album = list.take(8)
        shoot("page-album-sample", 1660, 1100, 4_000) {
            Page {
                CollectionLayout("After Hours", album.first().thumbnailUrl, album, failed = false) {
                    CollectionHero(
                        cover = { m -> CoverFill(album.first().thumbnailUrl, m) }, coverUrl = album.first().thumbnailUrl, kind = "Album • 2020", title = "After Hours", subtitle = "The Weeknd", songs = album,
                        extra = { ListMoreButton(album, "https://music.youtube.com/browse/x") },
                    )
                }
            }
        }
        shoot("page-album", 1660, 1100, 12_000) {
            Page { CollectionPage(ShelfItem("After Hours", "Album • The Weeknd • 2020", "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg", null, "MPREb_ZlRCjVWDsrE")) }
        }
        shoot("page-playlists", 1660, 900, 4_000) { Page { LibraryPage(Shelf.PLAYLISTS, {}, {}) } }
    }

    /** Moods as YouTube Music sends them (it doesn't answer for moods from every network), some without a colour or a cover. */
    private fun preloadMoods() {
        val titles = listOf("Chill", "Commute", "Energy boosters", "Feel good", "Focus", "Gaming", "Party", "Romance", "Sad", "Sleep", "Workout", "Hip-hop", "Pop", "Rock", "Jazz")
        val colours = listOf(0xFF1D7A8C, 0xFF7C5CBF, 0xFFE0573E, 0xFFDBA22C, 0xFF3C7D5A, 0xFF4651C2, 0xFFC2407E, 0xFFB8394B, 0xFF5D6B87, 0xFF2D3E75, 0xFFD3542C)
        Pages.preloadMoods(
            titles.map { com.opentune.data.model.MoodGenre(it, "FEmusic_moods_and_genres_category", it) },
            colours = titles.zip(colours).toMap(),
            covers = titles.mapIndexed { i, t -> t to if (i % 4 == 3) "" else "https://i.ytimg.com/vi/${ids[i % ids.size]}/hqdefault.jpg" }.toMap(),
        )
    }

    @Test fun home() {
        preloadMoods()
        Library.useFile(java.nio.file.Files.createTempDirectory("pageshots").resolve("desktop.json").toFile())
        songs(16).forEach { Library.played(it) }
        Library.settle()
        shoot("page-home", 1660, 1800, 14_000) { Page { HomePage(onOpen = {}, onOpenAny = {}) } }
        shoot("page-home-narrow", 800, 1400, 12_000) { Page { HomePage(onOpen = {}, onOpenAny = {}) } }
    }

    @Test fun explore() {
        preloadMoods()
        shoot("page-explore", 1660, 1500, 14_000) { Page { NewPage(onOpen = {}) } }
        shoot("page-explore-narrow", 800, 1300, 10_000) { Page { NewPage(onOpen = {}) } }
    }

    @Test fun library() {
        Library.useFile(java.nio.file.Files.createTempDirectory("pageshots").resolve("desktop.json").toFile())
        shoot("page-library-empty", 1660, 900, 3_000) { Page { LibraryHome({}, {}, {}) } }
        shoot("page-liked-empty", 1660, 900, 3_000) { Page { LibraryPage(Shelf.LIKED, {}, {}) } }
        val list = songs(14)
        list.take(9).forEach { Library.setLiked(it, true) }
        val id = Library.createPlaylist("Late night drive")
        list.drop(5).forEach { Library.addToPlaylist(id, it) }
        Library.createPlaylist("Gym").also { g -> list.take(3).forEach { Library.addToPlaylist(g, it) } }
        list.forEach { Library.played(it) }
        Library.settle()
        listOf("songs", "artists", "albums", "playlists", "overview").forEachIndexed { i, name ->
            shoot("page-library-$name", 1660, 1100, 4_000) { Page { LibraryHome({}, {}, {}, startTab = i) } }
        }
        shoot("page-library-songs-narrow", 760, 1000, 3_000) { Page { LibraryHome({}, {}, {}) } }
        shoot("page-liked", 1660, 1000, 3_000) { Page { LibraryPage(Shelf.LIKED, {}, {}) } }
        shoot("page-history", 1660, 1000, 3_000) { Page { LibraryPage(Shelf.RECENT, {}, {}) } }
        shoot("page-dialog-new-signed-out", 700, 520, 1_500) { Page { NewPlaylistDialog(onDismiss = {}) {} } }
    }

    /** The account's library as YouTube Music sends it: Liked Music and Connect's playlist among the playlists, as they come. */
    private fun signedInLibrary(): AccountLibrary.State {
        val list = songs(14)
        fun cover(i: Int) = "https://i.ytimg.com/vi/${ids[i % ids.size]}/hqdefault.jpg"
        return AccountLibrary.State(
            signedIn = true, loaded = true, readAt = System.currentTimeMillis(),
            playlists = listOf(
                ShelfItem("Liked Music", "Auto playlist", null, null, "VLLM"),
                ShelfItem("Road trip", "Playlist • 42 songs", cover(2), null, "VLPLroadtrip"),
                ShelfItem("OpenTune Connect", "Playlist • Private", null, null, "VLPLconnect"),
                ShelfItem("Focus", "Playlist • 18 songs", cover(5), null, "VLPLfocus"),
                ShelfItem("Road trip", "Playlist • 42 songs", cover(2), null, "PLroadtrip"),
            ),
            albums = listOf(ShelfItem("After Hours", "Album • The Weeknd • 2020", cover(0), null, "MPREb_after")),
            artists = listOf(ShelfItem("Daft Punk", "1.2M subscribers", cover(5), null, "UCdaftpunk")),
            // Liked on the account: some only there, one liked here too.
            liked = list.drop(6).take(6) + list[1],
            saved = list.takeLast(2),
        )
    }

    @Test fun signedIn() {
        Library.useFile(java.nio.file.Files.createTempDirectory("pageshots").resolve("desktop.json").toFile())
        val list = songs(14)
        list.take(3).forEach { Library.setLiked(it, true) }
        Library.createPlaylist("Late night drive").also { id -> list.drop(4).take(5).forEach { Library.addToPlaylist(id, it) } }
        list.forEach { Library.played(it) }
        Library.settle()
        AccountLibrary.preload(signedInLibrary())
        try {
            listOf("songs", "artists", "albums", "playlists", "overview").forEachIndexed { i, name ->
                shoot("page-library-signed-in-$name", 1660, 1100, 4_000) { Page { LibraryHome({}, {}, {}, startTab = i) } }
            }
            shoot("page-liked-signed-in", 1660, 1000, 3_000) { Page { LibraryPage(Shelf.LIKED, {}, {}) } }
            shoot("page-history-signed-in", 1660, 1000, 3_000) { Page { LibraryPage(Shelf.RECENT, {}, {}) } }
            shoot("page-playlists-signed-in", 1660, 900, 4_000) { Page { LibraryPage(Shelf.PLAYLISTS, {}, {}) } }
            shoot("page-dialog-new-signed-in", 700, 520, 1_500) { Page { NewPlaylistDialog(onDismiss = {}) {} } }
            shoot("page-dialog-privacy", 700, 420, 1_500) { Page { PagePrivacyDialog(com.opentune.data.model.PlaylistPrivacy.UNLISTED, {}) {} } }
            // A playlist the account owns: who can see it in the head, a button to take each song out.
            val owned = list.take(6).mapIndexed { i, s -> s.copy(setVideoId = "set$i") }
            Pages.keepListing("VLPLroadtrip", Pages.Listing(owned, owned = true, privacy = com.opentune.data.model.PlaylistPrivacy.PRIVATE))
            shoot("page-account-playlist", 1660, 1000, 4_000) {
                Page { CollectionPage(ShelfItem("Road trip", "Playlist • You • 2025", owned.first().thumbnailUrl, null, "VLPLroadtrip")) }
            }
            // The window: the sidebar lists the account's playlists after yours, without Liked Music or Connect's.
            shoot("page-window-signed-in", 1660, 1000, 5_000) { DesktopApp() }
        } finally {
            AccountLibrary.preload(AccountLibrary.State())
        }
    }
}
