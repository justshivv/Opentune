package com.opentune.wear

import android.graphics.Bitmap
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.connect.CTrack
import com.opentune.data.connect.Device
import com.opentune.data.connect.ConnectMsg
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.playback.SleepTimer
import com.opentune.wear.ui.LyricLines
import com.opentune.wear.ui.SleepText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The watch app's home on a round watch, and the small rules behind it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class WatchTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun homeOffersSearchLikedRecentAndPairing() {
        val player = WatchPlayer(ApplicationProvider.getApplicationContext(), connectNow = false)
        compose.setContent { WatchApp(player) }
        compose.waitForIdle()
        compose.onNodeWithText("Search").assertExists()
        compose.onNodeWithText("Liked songs").assertExists()
        compose.onNodeWithText("Recent").assertExists()
        save("wear-home")
    }

    @Test fun theCrownTurnsVolumeOneStepAtATime() {
        assertEquals(AudioManager.ADJUST_RAISE, WatchVolume.step(40f))
        assertEquals(AudioManager.ADJUST_LOWER, WatchVolume.step(-40f))
        assertNull(WatchVolume.step(3f))
    }

    @Test fun smallTurnsOfTheCrownAddUpToSteps() {
        val knob = WatchVolume.Crown(perStep = 15f)
        // Xiaomi's crown: many small events, none a step on its own.
        val steps = List(10) { knob.step(4f) }.filterNotNull()
        assertEquals(listOf(AudioManager.ADJUST_RAISE, AudioManager.ADJUST_RAISE), steps)
        // Turning back starts over, then goes down.
        assertNull(knob.step(-4f))
        assertEquals(AudioManager.ADJUST_LOWER, List(4) { knob.step(-4f) }.filterNotNull().single())
        // One large click is a step at once.
        assertEquals(AudioManager.ADJUST_RAISE, WatchVolume.Crown(perStep = 15f).step(40f))
    }

    @Test fun searchKeepsSongsInOrderOnce() {
        fun song(id: String) = Song(id, "Title $id", "Artist", null)
        val rows = listOf(SearchResult.TopTrack(song("a")), SearchResult.Track(song("b")), SearchResult.Track(song("a")))
        assertEquals(listOf("a", "b"), WatchPlayer.songsIn(rows).map { it.videoId })
    }

    private fun t(id: Char) = CTrack(id.toString().repeat(11), "Song $id", "Artist $id")

    private fun device(track: CTrack?, queue: List<CTrack> = emptyList(), playing: Boolean = true, volume: Float = 0.5f) =
        Device("p1", "Pixel 9", "phone", 0, ConnectMsg.State("p1", 0, 0, track = track, playing = playing, pos = 1_000, queue = queue, volume = volume))

    @Test fun anotherDevicePlayingIsWhatTheWatchShows() {
        val local = Song("local", "Here", "Me", null)
        assertEquals("local", WatchRemote.shown(local, false, null)?.song?.videoId)
        val far = WatchRemote.shown(local, false, device(t('a')))
        assertEquals("aaaaaaaaaaa", far?.song?.videoId)
        assertEquals("Pixel 9", far?.on)
        assertTrue(far!!.playing)
        assertNull(WatchRemote.shown(local, true, device(null)))
    }

    @Test fun theCrownMovesTheOtherDevicesVolumeWithinBounds() {
        assertEquals(0.55f, WatchRemote.volumeAfter(0.5f, AudioManager.ADJUST_RAISE), 0.0001f)
        assertEquals(0.45f, WatchRemote.volumeAfter(0.5f, AudioManager.ADJUST_LOWER), 0.0001f)
        assertEquals(1f, WatchRemote.volumeAfter(0.98f, AudioManager.ADJUST_RAISE), 0.0001f)
        assertEquals(0f, WatchRemote.volumeAfter(0.02f, AudioManager.ADJUST_LOWER), 0.0001f)
    }

    @Test fun theRemoteQueueStartsWithItsSongAndTapsReachItsUpNext() {
        val d = device(t('a'), listOf(t('b'), t('c')))
        val (rows, current) = WatchRemote.remoteQueue(d)
        assertEquals(listOf("Song a", "Song b", "Song c"), rows.map { it.title })
        assertEquals(0, current)
        assertEquals(1 to t('c'), WatchRemote.remoteEntry(d, 2))
        assertNull(WatchRemote.remoteEntry(d, 0))
    }

    @Test fun tileAndComplicationWords() {
        assertEquals(TileText("Nothing playing", "Tap to open OpenTune", false, false), TileText.of(null))
        val here = Shown(Song("x", "Title", "Artist", null), true)
        assertEquals(TileText("Title", "Artist", true, true), TileText.of(here))
        assertEquals("On Pixel 9", TileText.of(here.copy(on = "Pixel 9")).line)
        assertEquals("Title", TileText.short(here))
        assertEquals("OpenTune", TileText.short(null))
        assertEquals("Title · Artist", TileText.long(here))
    }

    @Test fun sleepTimerWordsAndTheLyricLinePlaying() {
        assertEquals("Off", SleepText.of(SleepTimer.State.Off, 0))
        assertEquals("End of the song", SleepText.of(SleepTimer.State.EndOfTrack, 0))
        assertEquals("15 min left", SleepText.of(SleepTimer.State.At(15 * 60_000L, 0), 0))
        assertEquals("1 min left", SleepText.of(SleepTimer.State.At(30_000L, 0), 0))
        val starts = listOf(0L, 1_000, 5_000)
        assertEquals(-1, LyricLines.currentIndex(listOf(500L), 0))
        assertEquals(1, LyricLines.currentIndex(starts, 4_999))
        assertEquals(2, LyricLines.currentIndex(starts, 9_000))
    }

    @Test fun lyricsNeverStepBackExceptForAJump() {
        val clock = LyricLines.Steady()
        assertEquals(5_000L, clock.next(5_000))
        // The guess ran ahead and is pulled back: the line stays.
        assertEquals(5_300L, clock.next(5_300))
        assertEquals(5_300L, clock.next(5_100))
        assertEquals(5_400L, clock.next(5_400))
        // A seek back is followed.
        assertEquals(1_000L, clock.next(1_000))
    }

    @Test fun theSongsTime() {
        assertEquals("0:00", SongTime.text(0))
        assertEquals("1:05", SongTime.text(65_400))
        assertEquals("1:02:03", SongTime.text(3_723_000))
        assertEquals(0.5f, SongTime.fraction(60_000, 120_000), 0.001f)
        assertEquals(1f, SongTime.fraction(130_000, 120_000), 0.001f)
        assertEquals(0f, SongTime.fraction(10, 0), 0.001f)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
