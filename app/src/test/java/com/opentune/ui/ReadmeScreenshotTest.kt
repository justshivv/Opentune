package com.opentune.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.ExploreFeed
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.podcasts.PodcastEpisode
import com.opentune.data.podcasts.PodcastShow
import com.opentune.data.radio.Radio
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import com.opentune.data.together.Track
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.explore.ExploreBoard
import com.opentune.ui.player.PlayerActions
import com.opentune.ui.player.PlayerLayout
import com.opentune.ui.player.PlayerUiState
import com.opentune.ui.podcasts.EpisodeRow
import com.opentune.ui.podcasts.ShowRow
import com.opentune.ui.radio.StationRow
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.ui.together.RoomView
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The README's screenshots: the app's own screens, drawn with real covers.
 * Covers aren't kept in the repository, so this runs only when
 * OPENTUNE_SHOT_ART points at a folder of them, and writes to
 * build/screenshots/readme.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ReadmeScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val art = System.getenv("OPENTUNE_SHOT_ART")?.let(::File)

    /** Cover "name" is served from art/name.jpg for any URL ending in /name.jpg. */
    private fun cover(name: String) = "https://covers.invalid/$name.jpg"

    @Before fun setUp() {
        assumeTrue(art?.isDirectory == true)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        AppSettings.updateUi { it.copy(liquidGlass = false) }
        val engine = FakeImageLoaderEngine.Builder().apply {
            art!!.listFiles { f -> f.extension == "jpg" }!!.forEach { f ->
                val bmp = BitmapFactory.decodeFile(f.path)
                intercept({ it is String && it.substringBefore('?').endsWith("/${f.name}") }, android.graphics.drawable.BitmapDrawable(context.resources, bmp).asImage())
            }
        }.build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components { add(engine) }.coroutineContext(Dispatchers.Unconfined).build(),
        )
    }

    private fun shoot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            OpenTuneTheme(ThemeSettings().copy(mode = if (dark) com.opentune.data.settings.ThemeMode.DARK else com.opentune.data.settings.ThemeMode.LIGHT, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 28.dp)) { content() }
                }
            }
        }
        capture(name)
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots/readme").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val blinding = Song("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", cover("00hd"), "3:20", albumName = "After Hours")
    private val queue = listOf(
        blinding,
        Song("TUVcZfQe-Kw", "Levitating", "Dua Lipa", cover("01"), "3:23"),
        Song("H5v3kku4y6Q", "As It Was", "Harry Styles", cover("16"), "2:47"),
        Song("4m1EFMoRFvY", "Do I Wanna Know?", "Arctic Monkeys", cover("08"), "4:32"),
    )
    private val noActions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun player(
        song: Song,
        lyrics: com.opentune.ui.LyricsState,
        initialLyrics: Boolean,
        position: Long,
        style: com.opentune.data.settings.PlayerStyle = com.opentune.data.settings.PlayerStyle.CLASSIC,
        fullCover: Boolean = false,
        controls: com.opentune.data.settings.ControlStyle = com.opentune.data.settings.ControlStyle.BLOOM,
        playing: Boolean = true,
    ) = PlayerUiState(
        song = song, isPlaying = playing, isBuffering = false, hasNext = true, shuffle = false, repeatMode = Player.REPEAT_MODE_OFF,
        durationMs = 200_000, lyrics = lyrics, queue = queue, currentIndex = 0, upNext = listOf(1, 2, 3),
        sound = SoundSettings(), theme = ThemeSettings(), source = song.albumName,
        // Liquid Glass needs the GPU's shaders, which this software drawing can't run; the frosted look stands in.
        // The new designs move every frame while playing; a still needs that held, or the test never goes idle.
        ui = com.opentune.data.settings.InterfaceSettings(liquidGlass = false, playerStyle = style, reduceAnimation = style != com.opentune.data.settings.PlayerStyle.CLASSIC, fullScreenCover = fullCover, controlStyle = controls),
    ).let { state -> @Composable { PlayerLayout(state, position = { position }, buffered = { position + 30_000 }, actions = noActions, initialLyrics = initialLyrics) } }

    @Test fun nowPlaying() = shoot("player", content = player(blinding, com.opentune.ui.LyricsState.NotFound, false, 64_000))

    @Test fun fullCover() = shoot("player-full-cover", content = player(blinding, com.opentune.ui.LyricsState.NotFound, false, 64_000, fullCover = true))

    /** Each button design, playing and paused, swapped into one composition (a test can set content only once). */
    @Test fun buttons() {
        // Orbit's comet circles for as long as music plays, so this one runs on a hand-moved clock.
        compose.mainClock.autoAdvance = false
        var shown by androidx.compose.runtime.mutableStateOf<@Composable () -> Unit>({})
        var first = true
        com.opentune.data.settings.ControlStyle.entries.forEach { c ->
            listOf(true, false).forEach { playing ->
                shown = player(blinding, com.opentune.ui.LyricsState.NotFound, false, 64_000, controls = c, playing = playing)
                val name = "buttons-${c.name.lowercase()}-${if (playing) "playing" else "paused"}"
                if (first) shoot(name) { shown() } else capture(name)
                first = false
            }
        }
    }

    @Test fun cassette() = shoot("style-cassette", content = player(blinding, com.opentune.ui.LyricsState.NotFound, false, 64_000, com.opentune.data.settings.PlayerStyle.CASSETTE))
    @Test fun halo() = shoot("style-halo", content = player(blinding, com.opentune.ui.LyricsState.NotFound, false, 64_000, com.opentune.data.settings.PlayerStyle.HALO))
    @Test fun polaroid() = shoot("style-polaroid", content = player(blinding, com.opentune.ui.LyricsState.NotFound, false, 64_000, com.opentune.data.settings.PlayerStyle.POLAROID))

    @Test fun lyricsBounce() {
        AppSettings.updateUi { it.copy(lyricsAnimation = com.opentune.data.settings.LyricsAnimation.BOUNCE) }
        try { lyricsShot("lyrics-bounce") } finally { AppSettings.updateUi { it.copy(lyricsAnimation = com.opentune.data.settings.LyricsAnimation.FLUID) } }
    }

    @Test fun lyricsReveal() {
        AppSettings.updateUi { it.copy(lyricsAnimation = com.opentune.data.settings.LyricsAnimation.REVEAL) }
        try { lyricsShot("lyrics-reveal") } finally { AppSettings.updateUi { it.copy(lyricsAnimation = com.opentune.data.settings.LyricsAnimation.FLUID) } }
    }

    @Test fun lyrics() = lyricsShot("lyrics")

    private fun lyricsShot(name: String) {
        // Words by Frederic Weatherly, 1913: in the public domain.
        val text = listOf(
            "Oh Danny boy, the pipes, the pipes are calling",
            "From glen to glen, and down the mountain side",
            "The summer's gone, and all the roses falling",
            "'Tis you, 'tis you must go and I must bide",
            "But come ye back when summer's in the meadow",
            "Or when the valley's hushed and white with snow",
        )
        val lines = text.mapIndexed { i, t ->
            val start = 10_000L + i * 7_000L
            val words = t.split(' ')
            LyricLine(start, start + 7_000, t, words.mapIndexed { j, w -> LyricWord("$w ", start + j * 7_000L / words.size, start + (j + 1) * 7_000L / words.size) }, wordSynced = true)
        }
        val danny = Song("dannyboycash", "Danny Boy", "Johnny Cash", cover("danny"), "3:20", albumName = "American IV: The Man Comes Around")
        shoot(name, content = player(danny, com.opentune.ui.LyricsState.Found(Lyrics.Synced(lines, "LRCLIB")), true, 25_500))
    }

    @Test fun explore() {
        val artists = listOf("The Weeknd" to "00", "Dua Lipa" to "01", "Taylor Swift" to "05", "SZA" to "06", "Billie Eilish" to "07")
            .mapIndexed { i, (n, c) -> ExploreFeed.Artist("UC$i", n, cover(c)) }
        val canvas = ExploreFeed.Canvas(
            artists = artists,
            tiles = ExploreFeed.weave(
                listOf(
                    listOf("Chill", "Workout", "Focus", "Party", "Sleep", "Romance").map { ExploreFeed.Tile.Mood(MoodGenre(it, "FEmusic_moods_and_genres_category", it), ExploreFeed.Kind.MOOD) },
                    listOf("SOS" to "06", "Midnights" to "22", "Short n' Sweet" to "19", "HIT ME HARD AND SOFT" to "20")
                        .map { (n, c) -> ExploreFeed.Tile.Item(ShelfItem(n, "Album", cover(c), null, "MPRE$n"), "New albums", ExploreFeed.Kind.NEW) },
                    listOf("After Hours" to "00", "Harry's House" to "16")
                        .map { (n, c) -> ExploreFeed.Tile.Item(ShelfItem(n, "Album", cover(c), null, "MPRE$n"), "Charts", ExploreFeed.Kind.CHART) },
                ),
            ),
        )
        shoot("explore") { ExploreBoard(canvas, PaddingValues(), null, {}, {}, {}) }
    }

    @Test fun radio() {
        val body = javaClass.classLoader!!.getResource("radio-browser-nearby.json")!!.readText()
        val found = Radio.parseNearby(body, 28.61, 77.21)
        shoot("radio") {
            Column {
                com.opentune.ui.components.PageHeader("Radio", onBack = {})
                SectionHeader("Live near you", subtitle = "${found.size} stations within 36 km")
                found.take(7).forEach { StationRow(it.station, false, {}, details = it.station.nearbyDetails(it.distanceKm)) }
            }
        }
    }

    @Test fun together() {
        val room = Together.Room(
            code = RoomCode.parse("K7QX-M2PA-9DTE")!!, hosting = true, myName = "Asha", phase = Together.Phase.Live, relays = 6,
            members = listOf(Member("a", "Asha", host = true), Member("b", "Ravi"), Member("c", "Meera")),
            hostName = "Asha", track = Track("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", cover("00")),
            chat = listOf(
                Together.ChatLine("", "Ravi joined", system = true),
                Together.ChatLine("Ravi", "this one's a classic"),
                Together.ChatLine("Asha", "turn it up", mine = true),
            ),
        )
        shoot("together") { RoomView(room, onBack = {}) }
    }

    @Test fun podcasts() {
        val actions = SongActions(null, false, { _, _, _, _ -> }, {}, {})
        val show = PodcastShow("MPSPexploder", "Song Exploder", "Hrishikesh Hirway", cover("exploder"))
        val episodes = listOf(
            "Olivia Rodrigo - The Cure" to "26 min", "Natalie Merchant - Sister Tilly" to "25 min", "Paula Cole - I Don't Want to Wait" to "29 min",
            "Charli XCX - Camera" to "23 min", "Mitski - Your Best American Girl" to "15 min",
        ).mapIndexed { i, (t, d) -> PodcastEpisode("ep$i", t, "Song Exploder", "MPSPexploder", cover("exploder"), published = listOf("Sep 23", "Sep 16", "Sep 9", "Aug 19", "Aug 5")[i], durationText = d) }
        shoot("podcasts") {
            Column {
                com.opentune.ui.components.PageHeader("Podcasts", onBack = {})
                SectionHeader("Your shows")
                ShowRow(show) {}
                SectionHeader("Latest episodes")
                episodes.forEach { EpisodeRow(it, actions, showShow = true, onOpenShow = {}) {} }
            }
        }
    }

    @Test fun library() {
        val actions = SongActions(null, false, { _, _, _, _ -> }, {}, {})
        val nav = com.opentune.ui.library.LibraryNav({}, {}, {}, {}, {}, {}, {}, {}, {})
        shoot("library") { com.opentune.ui.library.LibraryScreen(androidx.compose.foundation.layout.PaddingValues(), actions, nav) }
    }
}
