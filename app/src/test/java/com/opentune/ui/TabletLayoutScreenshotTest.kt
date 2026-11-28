package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.Collection
import com.opentune.data.library.LibraryCache
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.player.PlayerActions
import com.opentune.ui.player.PlayerLayout
import com.opentune.ui.player.PlayerUiState
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app's main screens at phone, foldable and tablet sizes, inside the same
 * chrome AppRoot puts around them (the rail, the docked player, the page's
 * share of the width), to build/screenshots/tablet/<size>. Invented songs and
 * drawn covers only. Each size is a subclass below.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TabletLayoutScreenshotTest(private val size: String) {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        LibraryCache.init(context)
        AppSettings.updateUi { it.copy(reduceAnimation = true, reduceBlur = true) }
        val engine = FakeImageLoaderEngine.Builder().apply {
            ART.forEachIndexed { i, colors ->
                intercept({ it is String && "/art$i." in it }, android.graphics.drawable.BitmapDrawable(context.resources, art(colors.first, colors.second, i)).asImage())
            }
        }.default(android.graphics.drawable.BitmapDrawable(context.resources, art(0xFF3B1F5C.toInt(), 0xFFE0508A.toInt(), 0)).asImage()).build()
        // Every cover asked for, with the size the image loader was asked for it at.
        val recorder = coil3.intercept.Interceptor { chain ->
            requests += chain.request.data.toString() to chain.size
            chain.proceed()
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(recorder); add(engine) }.coroutineContext(Dispatchers.Unconfined).build())
    }

    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, coil3.size.Size>>())

    private fun cover(i: Int) = "https://lh3.googleusercontent.com/art$i.jpg=w120-h120-l90-rj"

    private val nowPlaying = Song("np1", "Paper Lanterns Over the Harbour (Extended Version)", "Mira Solen & The Quiet Tides", cover(1), "4:12", albumName = "Harbour Lights")
    private val albumSongs = listOf(
        "Harbour Lights", "Paper Lanterns Over the Harbour", "Saltwater Letters", "Low Tide Choir", "Northbound", "Lamplighter",
        "The Long Way Round", "Moth & Moon", "Glass Bottles", "Undertow", "Winter Ferry", "Goodnight, Lighthouse",
    ).mapIndexed { i, t -> Song("al$i", t, "Mira Solen", cover(1), "${3 + i % 3}:${10 + i * 4}", albumId = "MPREb_harbour", albumName = "Harbour Lights") }
    private val album = Collection("MPREb_harbour", "Harbour Lights", "Mira Solen • 2026", cover(1), albumSongs)
    private val actions = SongActions(null, false, { _, _, _, _ -> }, {}, {})

    private val lyrics = com.opentune.ui.LyricsState.Found(
        Lyrics.Synced(
            listOf(
                "Paper lanterns over the harbour", "Every one a wish we never said", "Out where the cold water gathers",
                "I keep your name above my head", "And the ferry lights go dimmer", "Carry me home before the tide",
            ).mapIndexed { i, t -> LyricLine(10_000L + i * 8_000L, 17_000L + i * 8_000L, t, emptyList(), false) },
            "test",
        ),
    )

    private fun playerState(docked: Boolean) = PlayerUiState(
        song = nowPlaying, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true, shuffle = false, repeatMode = Player.REPEAT_MODE_OFF,
        durationMs = 252_000, lyrics = lyrics, queue = listOf(nowPlaying) + albumSongs.take(4), currentIndex = 0, upNext = listOf(1, 2, 3, 4),
        sound = SoundSettings(), theme = ThemeSettings(), ui = InterfaceSettings(reduceAnimation = true), source = "Harbour Lights",
    )

    private val noActions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    /**
     * The chrome AppRoot puts around a page for this window: the rail, the
     * docked player, and the page's width between them (see AppRoot's pageArea).
     */
    @Composable
    private fun Shell(hasSong: Boolean, page: @Composable (PaddingValues) -> Unit) {
        val config = LocalConfiguration.current
        val layout = appLayout(config.screenWidthDp, config.screenHeightDp, hasSong)
        CompositionLocalProvider(LocalAppLayout provides layout) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val start = if (layout.nav == NavChrome.RAIL) RAIL_GUTTER else 0.dp
                val end = when (layout.nowPlaying) {
                    NowPlaying.PANE -> PANE_GUTTER
                    NowPlaying.STRIP -> STRIP_GUTTER
                    else -> 0.dp
                }
                Box(
                    Modifier.align(Alignment.TopCenter).padding(start = start, end = end)
                        .then(layout.contentMaxWidthDp?.let { Modifier.widthIn(max = it.dp) } ?: Modifier).fillMaxSize(),
                ) { page(PaddingValues(bottom = 24.dp)) }
                if (layout.nav == NavChrome.RAIL) {
                    val tabs = listOf(
                        ChromeTab("Home", ImageVector.vectorResource(AppIcons.HomeOutline), ImageVector.vectorResource(AppIcons.Home)),
                        ChromeTab("Radio", ImageVector.vectorResource(AppIcons.RadioOutline), ImageVector.vectorResource(AppIcons.Radio)),
                        ChromeTab("Podcasts", ImageVector.vectorResource(AppIcons.Podcasts)),
                        ChromeTab("Library", ImageVector.vectorResource(AppIcons.LibraryMusicOutline), ImageVector.vectorResource(AppIcons.LibraryMusic)),
                    )
                    NavRail(tabs, 0, {}, searchSelected = false, onSearch = {}, modifier = Modifier.align(Alignment.CenterStart))
                }
                if (layout.nowPlaying == NowPlaying.PANE) {
                    PlayerPaneFrame(Modifier.align(Alignment.CenterEnd)) {
                        PlayerLayout(playerState(true), position = { 64_000 }, buffered = { 90_000 }, actions = noActions, docked = true)
                    }
                }
            }
        }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            OpenTuneTheme(ThemeSettings().copy(mode = ThemeMode.DARK, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("opentune.shots") ?: "build/screenshots/tablet", size).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun albumWithPlayer() {
        LibraryCache.save(album, LibraryCache.owner())
        shoot("album-with-player") {
            Shell(hasSong = true) { padding ->
                com.opentune.ui.browse.CollectionScreen(album.browseId, padding, actions, onBack = {})
            }
        }
        // The page's cover and the docked player's were each asked for, at a real size: none
        // waits on a box measured at nothing, and none asks for a size that isn't on the ladder.
        val asked = requests.toList()
        File(File(System.getProperty("opentune.shots") ?: "build/screenshots/tablet", size), "album-with-player.requests.txt")
            .writeText(asked.joinToString("\n") { (url, at) -> "$url  $at" })
        val covers = asked.filter { "/art1." in it.first }
        assertTrue("the album's cover was asked for", covers.any { "w1200-h1200" in it.first })
        covers.forEach { (url, at) ->
            val w = (at.width as? coil3.size.Dimension.Pixels)?.px
            val h = (at.height as? coil3.size.Dimension.Pixels)?.px
            assertTrue("$url asked at $at", w == null || w > 0)
            assertTrue("$url asked at $at", h == null || h > 0)
        }
    }

    @Test fun home() {
        val quick = HomeShelf("Quick picks", (0 until 16).map { ShelfItem(albumSongs[it % 12].title, "Mira Solen", cover(it % 8), "q$it", null) })
        val albums = HomeShelf("New releases", (0 until 10).map { ShelfItem(listOf("Harbour Lights", "Copper Sky", "Velvet Static", "Kites", "Northern Rooms")[it % 5], "Album • Artist $it", cover((it + 2) % 8), null, "MPREb_$it") })
        val mixes = HomeShelf("Mixed for you", (0 until 8).map { ShelfItem("Daily Mix ${it + 1}", "Mira Solen, Ode Lark and more", cover((it + 4) % 8), null, "VLmix$it") })
        val artists = HomeShelf("Artists you follow", (0 until 8).map { ShelfItem(listOf("Mira Solen", "Ode Lark", "Tavi Rune", "The Hollow Pines")[it % 4], "Artist", cover((it + 5) % 8), null, "UCartist$it") })
        ScreenCache.put(com.opentune.ui.home.homeCacheKey(), listOf(quick, albums, mixes, artists))
        shoot("home") { Shell(hasSong = false) { padding -> com.opentune.ui.home.HomeScreen(padding, actions, {}, {}, {}) } }
    }

    @Test fun artist() {
        val page = ArtistPage(
            songs = albumSongs.take(5),
            moreSongsBrowseId = null,
            sections = listOf(
                HomeShelf("Albums", (0 until 6).map { ShelfItem(listOf("Harbour Lights", "Copper Sky", "Kites")[it % 3], "${2026 - it}", cover((it + 1) % 8), null, "MPREb_a$it") }),
                HomeShelf("Singles & EPs", (0 until 6).map { ShelfItem("Single ${it + 1}", "${2025 - it}", cover((it + 3) % 8), null, "MPREb_s$it") }),
                HomeShelf("Fans might also like", (0 until 6).map { ShelfItem(listOf("Ode Lark", "Tavi Rune", "The Hollow Pines")[it % 3], "Artist", cover((it + 6) % 8), null, "UCfan$it") }),
            ),
            thumbnailUrl = cover(2),
            name = "Mira Solen",
            description = "Mira Solen writes quiet songs about harbours, ferries and the people who wait for them.",
            monthlyListenerCount = "1.2M monthly listeners",
        )
        shoot("artist") {
            Shell(hasSong = false) { padding ->
                com.opentune.ui.browse.ArtistPageView("UCmira", page, padding, actions, onBack = {}, onItemClick = {}, onShowAll = {})
            }
        }
    }

    @Test fun library() {
        val nav = com.opentune.ui.library.LibraryNav({}, {}, {}, {}, {}, {}, {}, {}, {})
        shoot("library") { Shell(hasSong = false) { padding -> com.opentune.ui.library.LibraryScreen(padding, actions, nav) } }
    }

    @Test fun search() {
        shoot("search") {
            Shell(hasSong = false) { padding ->
                com.opentune.ui.search.SearchScreen(padding, null, false, {}, {}, {}, {})
            }
        }
    }

    @Test fun settings() {
        shoot("settings") { Shell(hasSong = false) { padding -> com.opentune.ui.settings.SettingsScreen(padding, onBack = {}, onOpenEqualizer = {}, onOpenReplay = {}) } }
    }

    @Test fun player() {
        shoot("player") { PlayerLayout(playerState(false), position = { 64_000 }, buffered = { 90_000 }, actions = noActions) }
    }

    @Test fun playerLyrics() {
        shoot("player-lyrics") { PlayerLayout(playerState(false), position = { 27_000 }, buffered = { 90_000 }, actions = noActions, initialLyrics = true) }
    }

    @Test fun dockedLyrics() {
        LibraryCache.save(album, LibraryCache.owner())
        shoot("docked-lyrics") {
            val config = LocalConfiguration.current
            val layout = appLayout(config.screenWidthDp, config.screenHeightDp, true)
            if (layout.nowPlaying == NowPlaying.PANE) Box(Modifier.fillMaxSize()) {
                PlayerPaneFrame(Modifier.align(Alignment.CenterEnd)) {
                    PlayerLayout(playerState(true), position = { 27_000 }, buffered = { 90_000 }, actions = noActions, docked = true, initialLyrics = true)
                }
            }
        }
    }

    private companion object {
        val ART = listOf(
            0xFF5C1F1F.toInt() to 0xFFE2553C.toInt(), 0xFF3A0B0B.toInt() to 0xFFD3271F.toInt(), 0xFF1F3A5C.toInt() to 0xFF5CB8E2.toInt(),
            0xFF1F5C3A.toInt() to 0xFF9BE25C.toInt(), 0xFF4A3B12.toInt() to 0xFFE2C25C.toInt(), 0xFF2C1F5C.toInt() to 0xFFA57CF0.toInt(),
            0xFF0F3D3D.toInt() to 0xFF4FD1C5.toInt(), 0xFF3D0F2E.toInt() to 0xFFF07CB4.toInt(),
        )

        /** A drawn cover: a glow over a dark ground, and a disc, placed by [seed]. */
        fun art(dark: Int, light: Int, seed: Int): Bitmap {
            val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            c.drawColor(dark)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.shader = android.graphics.RadialGradient(200f + seed * 40f, 220f, 360f, light, dark and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 600f, 600f, p)
            p.shader = null
            p.color = 0x55000000
            c.drawCircle(420f - seed * 20f, 430f, 150f, p)
            return b
        }
    }
}

@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class PhoneLayoutShots : TabletLayoutScreenshotTest("phone")

@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi", application = android.app.Application::class)
class PhoneLandscapeLayoutShots : TabletLayoutScreenshotTest("phone-land")

@Config(sdk = [35], qualifiers = "w673dp-h841dp-xhdpi", application = android.app.Application::class)
class FoldableLayoutShots : TabletLayoutScreenshotTest("foldable")

@Config(sdk = [35], qualifiers = "w880dp-h1280dp-port-xhdpi", application = android.app.Application::class)
class TabletPortraitLayoutShots : TabletLayoutScreenshotTest("tablet-port")

@Config(sdk = [35], qualifiers = "w1280dp-h880dp-land-xxhdpi", application = android.app.Application::class)
class TabletLandscapeLayoutShots : TabletLayoutScreenshotTest("tablet-land")
