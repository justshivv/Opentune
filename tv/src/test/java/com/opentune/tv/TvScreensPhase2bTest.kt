package com.opentune.tv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.opentune.data.Collection
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioFormatInfo
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The album and playlist pages and Now Playing on a 1080p television, drawn in the shell as a
 * viewer finds them. All the songs, artists and words here are made up.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = android.app.Application::class)
class TvScreensPhase2bTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var player: TvPlayer
    private lateinit var nav: TvNav

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(app)
        LibraryStore.init(app)
        usePlayer(emptyFlow())
    }

    /** A fresh player whose songs' words come from [words] instead of the lyrics sources. */
    private fun usePlayer(words: Flow<Lyrics?>) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        player = TvPlayer(app, connectNow = false, findWords = { _, _ -> words })
        nav = TvNav(mutableStateListOf<Dest>(Dest.Home), player)
    }

    // ---- Made-up music ----

    private fun art(name: String) = "https://lh3.googleusercontent.com/$name=w544-h544"

    private val albumArt = art("evening-errands")
    private val titles = listOf(
        "Paper Lanterns", "Harbour Lights Again", "Small Hours", "Copper Kettle",
        "Windowsill Garden", "Late Train Home", "Northern Field", "Glass Orchard",
    )

    private fun albumSong(i: Int) = Song(
        "ee$i", titles[i], "The Quiet Hours", albumArt, "3:${10 + i * 5}", albumName = "Evening Errands",
    )

    private val album = Collection("MPREb_ee", "Evening Errands", "The Quiet Hours · 2024", albumArt, titles.indices.map(::albumSong))

    private val mix = Collection(
        "VLPLmix", "Rainy Window Mix", "Made for you", art("mix"),
        listOf(
            Song("m0", "Paper Lanterns", "The Quiet Hours", art("m0"), "3:41"),
            Song("m1", "Tin Roof Morning", "Mara Vell", art("m1"), "4:02"),
            Song("m2", "Slow Ferry", "Juniper Coast", art("m2"), "2:58"),
            Song("m3", "Chalk Lines", "Mara Vell", art("m3"), "3:33"),
            Song("m4", "Fog on the Pier", "The Quiet Hours", art("m4"), "5:10"),
            Song("m5", "Kite String", "Juniper Coast", art("m5"), "3:07"),
            Song("m6", "Borrowed Umbrella", "Mara Vell", art("m6"), "3:49"),
        ),
    )

    private val words = Lyrics.Synced(
        listOf(
            "Line one of the song", "The second line follows", "A third line comes along",
            "Here is the fourth line", "And then a fifth", "The sixth line closes the verse",
            "", "A new verse starts here", "One more line after that", "And the last line of all",
        ).mapIndexed { i, t -> LyricLine(i * 4_000L, i * 4_000L + 3_500, t, emptyList(), false) },
        "test",
    )

    // ---- The album and playlist pages ----

    @Test fun anAlbumOpensOnPlayWithItsNumberedSongsUnderIt() {
        tint(albumArt)
        set("_currentSong", albumSong(2))
        nav.open(Dest.Collection(album.browseId, album.title, albumArt))
        nav.memoryAt(1).loads[album.browseId] = Load.Done(album)
        show { TvShell(nav) }

        compose.onNodeWithText("Album").assertExists()
        compose.onNodeWithText("Evening Errands").assertExists()
        compose.onNodeWithText("The Quiet Hours · 2024 · 8 songs").assertExists()
        // Play takes the focus as the page opens; Down goes to the first song.
        compose.onNodeWithText("Play").assertIsFocused()
        save("tv-album")
        compose.onNodeWithText("Play").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Paper Lanterns").assertIsFocused()
    }

    @Test fun aPlaylistShowsEachSongsCover() {
        tint(mix.thumbnailUrl!!)
        set("_currentSong", mix.songs[1])
        nav.open(Dest.Collection(mix.browseId, mix.title, mix.thumbnailUrl))
        nav.memoryAt(1).loads[mix.browseId] = Load.Done(mix)
        show { TvShell(nav) }
        compose.onNodeWithText("Playlist").assertExists()
        compose.onNodeWithText("Play").assertIsFocused()
        // The playing song's row, focused: the mark, the accent title and the ring together.
        // (The bar's strip names the song too; the row is the one with its length.)
        val row = compose.onNode(hasText("Tin Roof Morning") and hasText("4:02"))
        row.requestFocus()
        compose.waitForIdle()
        row.assertIsFocused()
        save("tv-playlist")
    }

    /** Focusing the playing song's row changes only colours: its title stays where it was. */
    @Test fun thePlayingRowKeepsItsLayoutWhenFocused() {
        show {
            Column {
                TvSongRow("Paper Lanterns", "The Quiet Hours", "3:41", art("a"), {}, index = 1, playing = true, focusKey = "a")
                TvSongRow("Slow Ferry", "Juniper Coast", "2:58", art("b"), {}, playing = true, focusKey = "b")
                TvSongRow("Chalk Lines", "Mara Vell", "3:33", art("c"), {}, focusKey = "c")
            }
        }
        for (title in listOf("Paper Lanterns", "Slow Ferry")) {
            compose.onNodeWithText("Chalk Lines").requestFocus()
            compose.waitForIdle()
            val rest = compose.onNodeWithText(title, useUnmergedTree = true).getBoundsInRoot()
            compose.onNodeWithText(title).requestFocus()
            compose.waitForIdle()
            compose.onNodeWithText(title).assertIsFocused()
            assertEquals(rest, compose.onNodeWithText(title, useUnmergedTree = true).getBoundsInRoot())
        }
    }

    // ---- Now Playing ----

    /** The player as if [song] of the made-up album were playing [at] ms in, the album queued. */
    private fun playing(song: Int, at: Long, length: Long = 200_000, format: AudioFormatInfo? = null) {
        // The length first: the words are looked for once the song's length is known.
        set("_durationMs", length)
        set("_queue", album.songs)
        set("_currentIndex", song)
        set("_audioFormat", format)
        set("_currentSong", album.songs[song])
        setPosition(at)
        tint(albumArt)
        nav.open(Dest.NowPlaying)
    }

    @Test fun nowPlayingShowsTheLyricsWithPlayFocused() {
        usePlayer(flowOf(words))
        playing(3, at = 13_000, format = AudioFormatInfo("ec+3", 768, 48_000, 6, mime = "audio/eac3-joc"))
        show { TvShell(nav) }
        setPosition(13_000)
        compose.waitForIdle()

        compose.onNodeWithText("Copper Kettle").assertExists()
        compose.onNodeWithText("Here is the fourth line").assertExists()
        compose.onNodeWithContentDescription("Play").assertIsFocused()
        save("tv-nowplaying-lyrics")

        // Up reaches the seek bar; Down comes back to Play.
        compose.onNodeWithContentDescription("Play").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Seek").assertIsFocused()
        compose.onNodeWithContentDescription("Seek").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play").assertIsFocused()
    }

    @Test fun theQueueButtonShowsTheQueueOnThePlayingSong() {
        usePlayer(flowOf(words))
        playing(2, at = 70_000)
        show { TvShell(nav) }
        setPosition(70_000)

        compose.onNodeWithContentDescription("Queue").performClick()
        compose.waitForIdle()
        // The queue takes the focus on the playing song; the words are gone.
        compose.onNodeWithText("Line one of the song").assertDoesNotExist()
        compose.onNodeWithText("Copper Kettle").assertExists()
        focusedQueueRow("Small Hours")
        save("tv-nowplaying-queue")

        // Back from the queue returns to the Queue button, the page still open.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Queue").assertIsFocused()
        assertEquals(Dest.NowPlaying, nav.current)
    }

    @Test fun whileTheSongLoadsTheHeadingSaysSoAndNothingSpins() {
        playing(5, at = 0, length = 0)
        set("_isBuffering", true)
        show { TvShell(nav) }
        compose.onNodeWithText("Late Train Home").assertExists()
        compose.onNodeWithText("Loading…").assertExists()
        compose.onNodeWithText("Setting up the lyrics…").assertExists()
        compose.onNodeWithContentDescription("Play").assertIsFocused()
        save("tv-nowplaying-buffering")
    }

    /** The queue's row for [title] holds the focus (the heading above shows the same title, unfocused). */
    private fun focusedQueueRow(title: String) {
        compose.onAllNodes(hasText(title) and isFocused()).assertCountEquals(1)
    }

    // ---- Plumbing ----

    /** Sets one of the player connection's private flows, as the service would. */
    private fun set(field: String, value: Any?) {
        val f = com.opentune.playback.PlayerConnection::class.java.getDeclaredField(field).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        compose.runOnIdle { (f.get(player.player) as MutableStateFlow<Any?>).value = value }
    }

    private fun setPosition(ms: Long) {
        val f = TvPlayer::class.java.getDeclaredField("_position").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        compose.runOnIdle { (f.get(player) as MutableStateFlow<Long>).value = ms }
    }

    /**
     * Draws [content] with the remote driving and made-up covers, as on a TV: the keys are in use
     * before the page is drawn (a TV is never in touch mode), so the page places its first focus
     * as it would there.
     */
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        var modes: androidx.compose.ui.input.InputModeManager? = null
        val keys = mutableStateOf(false)
        compose.setContent {
            modes = androidx.compose.ui.platform.LocalInputModeManager.current
            TvTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(TvColors.Background)) {
                    if (keys.value) CompositionLocalProvider(
                        LocalInspectionMode provides true,
                        LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request -> fakeArt(request.data.toString()) },
                    ) { content() }
                }
            }
        }
        compose.runOnIdle {
            modes!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            keys.value = true
        }
        compose.waitForIdle()
    }

    /** The two colours of the made-up cover at [url]. */
    private fun artColors(url: String): Pair<Int, Int> {
        val hue = Math.floorMod(url.hashCode(), 360).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.55f)) to
            android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.5f, 0.8f))
    }

    /** Lets the shell's tint behind a page know the made-up cover's colours, as if it had read them. */
    private fun tint(url: String) {
        val (back, dot) = artColors(url)
        knowCoverColors(url, back, dot)
    }

    /** A plain two-tone square standing in for a cover, its colours from the URL. */
    private fun fakeArt(url: String): coil3.Image {
        val (back, dot) = artColors(url)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(back)
        val paint = android.graphics.Paint().apply { color = dot }
        canvas.drawCircle(40f, 26f, 18f, paint)
        return bitmap.asImage()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
