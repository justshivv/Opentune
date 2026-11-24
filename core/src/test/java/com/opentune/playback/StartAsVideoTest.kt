package com.opentune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** A video card's request to start its song as a video: taken once, by that song, soon after. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartAsVideoTest {
    @Test fun onlyTheSongAskedTakesItAndOnlyOnce() {
        PlaybackRequests.startAsVideo("a")
        assertNull(PlaybackRequests.takeStartAsVideo("b"))
        assertEquals("a", PlaybackRequests.takeStartAsVideo("a"))
        assertNull(PlaybackRequests.takeStartAsVideo("a"))
    }

    @Test fun anythingElsePlayedWithdrawsIt() {
        PlaybackRequests.startAsVideo("a")
        PlaybackRequests.startAsVideo(null)
        assertNull(PlaybackRequests.takeStartAsVideo("a"))
    }

    @Test fun anOldRequestIsForgotten() {
        PlaybackRequests.startAsVideo("a")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        assertNull(PlaybackRequests.takeStartAsVideo("a"))
    }
}
