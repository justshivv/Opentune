package com.opentune.lyrics

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.settings.AppSettings
import com.opentune.widget.WidgetLyrics
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The line throttle, and the follower waking only for line changes, at most once a second for widgets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LyricsOutsideTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        AppSettings.init(context)
    }

    @Test fun throttleSendsTheFirstLineAndNeverTheSameOneTwice() {
        val t = LineThrottle(1_000)
        assertEquals(LineThrottle.Decision.Push, t.offer("a", 10_000))
        assertEquals(LineThrottle.Decision.Same, t.offer("a", 10_100))
        assertEquals(LineThrottle.Decision.Same, t.offer("a", 20_000))
    }

    @Test fun throttleHoldsAQuickChangeUntilTheSecondIsUp() {
        val t = LineThrottle(1_000)
        t.offer("a", 10_000)
        assertEquals(LineThrottle.Decision.Wait(700), t.offer("b", 10_300))
        assertEquals(LineThrottle.Decision.Wait(100), t.offer("c", 10_900))
        assertEquals(LineThrottle.Decision.Push, t.offer("c", 11_000))
        assertEquals(LineThrottle.Decision.Same, t.offer("c", 11_001))
    }

    @Test fun throttleAfterAResetSendsAgain() {
        val t = LineThrottle(1_000)
        t.offer("a", 10_000)
        t.reset()
        assertEquals(LineThrottle.Decision.Push, t.offer("a", 15_000))
    }

    @Test fun widgetsTakeAtMostOneLineASecond() {
        assertTrue(WidgetLyrics.minIntervalMs >= 1_000)
    }

    /** Lines every 300 ms: the widget gets one about each second, the latest line each time. */
    @Test fun fastLinesReachAWidgetAtMostOnceASecond() {
        val lines = (0 until 40).map { line(it * 300L, it * 300L + 300, "line $it") }
        NowPlayingLyrics.set("song", Lyrics.Synced(lines, "test"))
        val outlet = Recorder(1_000)
        val player = FakePlayer()
        LyricsFollower(context, listOf(outlet)).onPlayer(player)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(6_000))
        val gaps = outlet.at.zipWithNext { a, b -> b - a }
        assertTrue("pushed ${outlet.at.size} times", outlet.at.size in 5..8)
        assertTrue("gaps $gaps", gaps.all { it >= 1_000 })
        // Each one is the line being sung when it was sent.
        val last = outlet.shown.last()!!
        val position = player.currentPosition
        assertTrue(last.current!!.startMs <= position && position - last.current.startMs < 1_400)
    }

    /** Slow lines: one push per line, at the line, and nothing in between. */
    @Test fun slowLinesArePushedOnlyWhenTheyChange() {
        val lines = (0 until 5).map { line(1_000 + it * 3_000L, 4_000 + it * 3_000L, "line $it") }
        NowPlayingLyrics.set("song", Lyrics.Synced(lines, "test"))
        val outlet = Recorder(1_000)
        val player = FakePlayer()
        val start = SystemClock.elapsedRealtime()
        LyricsFollower(context, listOf(outlet)).onPlayer(player)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(13_500))
        // Before the first line (only the next one), then each of the five.
        assertEquals(listOf(null, "line 0", "line 1", "line 2", "line 3", "line 4"), outlet.shown.map { it?.current?.text })
        val sentAt = outlet.at.map { it - start }
        sentAt.drop(1).forEachIndexed { i, ms -> assertTrue("line $i at $ms", ms - (1_000 + i * 3_000L) in 0..150) }
    }

    @Test fun pausedNothingMoreIsSentOrScheduled() {
        val lines = (0 until 20).map { line(it * 2_000L, it * 2_000L + 2_000, "line $it") }
        NowPlayingLyrics.set("song", Lyrics.Synced(lines, "test"))
        val outlet = Recorder(1_000)
        val player = FakePlayer()
        LyricsFollower(context, listOf(outlet)).onPlayer(player)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_100))
        val before = outlet.at.size
        player.hold()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20_000))
        assertEquals(before, outlet.at.size)
        // Nothing waiting to wake it.
        assertEquals(Duration.ZERO, shadowOf(Looper.getMainLooper()).nextScheduledTaskTime)
    }

    @Test fun withTheScreenOffItSleeps() {
        val lines = (0 until 20).map { line(it * 2_000L, it * 2_000L + 2_000, "line $it") }
        NowPlayingLyrics.set("song", Lyrics.Synced(lines, "test"))
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val outlet = Recorder(1_000)
        LyricsFollower(context, listOf(outlet)).onPlayer(FakePlayer())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_000))
        assertEquals(1, outlet.at.size)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    }

    @Test fun anotherSongsLyricsAreNotShown() {
        NowPlayingLyrics.set("another", Lyrics.Synced(listOf(line(0, 5_000, "not this one")), "test"))
        val outlet = Recorder(1_000)
        LyricsFollower(context, listOf(outlet)).onPlayer(FakePlayer())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_000))
        assertEquals(1, outlet.shown.size)
        assertNull(outlet.shown.single())
    }

    @Test fun turnedOffItIsClearedOnce() {
        NowPlayingLyrics.set("song", Lyrics.Synced(listOf(line(0, 60_000, "one")), "test"))
        val outlet = Recorder(1_000)
        val follower = LyricsFollower(context, listOf(outlet))
        follower.onPlayer(FakePlayer())
        assertEquals("one", outlet.shown.single()?.current?.text)
        outlet.on = false
        follower.poke()
        follower.poke()
        assertEquals(2, outlet.shown.size)
        assertNull(outlet.shown.last())
    }

    private fun line(start: Long, end: Long, text: String) =
        LyricLine(start, end, text, listOf(LyricWord(text, start, end)), wordSynced = true)

    private class Recorder(override val minIntervalMs: Long) : LyricsOutlet {
        var on = true
        val shown = mutableListOf<LyricsNow?>()
        val at = mutableListOf<Long>()
        override fun wanted(context: Context, playing: Boolean) = on
        override fun show(context: Context, now: LyricsNow?) {
            shown += now
            at += SystemClock.elapsedRealtime()
        }
    }

    /** A song called "song" playing from 0 at normal speed, on the test's clock. */
    @OptIn(UnstableApi::class)
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var playing = true
        private var startedAt = SystemClock.elapsedRealtime()
        private var heldAt = 0L

        fun hold() {
            heldAt = currentPosition
            playing = false
            invalidateState()
        }

        override fun getState(): State {
            val item = MediaItemData.Builder("song").setMediaItem(MediaItem.Builder().setMediaId("song").build()).build()
            return State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlaylist(listOf(item))
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs(PositionSupplier { if (playing) SystemClock.elapsedRealtime() - startedAt else heldAt })
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    }
}
