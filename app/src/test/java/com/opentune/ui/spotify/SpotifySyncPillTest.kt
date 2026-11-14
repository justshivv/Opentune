package com.opentune.ui.spotify

import com.opentune.data.spotify.SpotifySync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifySyncPillTest {
    private val idle = SpotifySync.Status()

    @Test fun saysWhatTheCheckIsDoingThenWhatItFound() {
        assertEquals("Checking Spotify for changes…", spotifySyncLine(idle.copy(running = true, checking = true), false))
        assertEquals("Updating Gym · 2 of 3", spotifySyncLine(idle.copy(running = true, current = "Gym", index = 2, count = 3), false))
        assertEquals("Updating Gym", spotifySyncLine(idle.copy(running = true, current = "Gym", index = 1, count = 1), false))
        assertEquals("Up to date with Spotify", spotifySyncLine(idle, true))
        assertEquals("1 playlist updated", spotifySyncLine(idle.copy(changed = 1), true))
        assertEquals("4 playlists updated", spotifySyncLine(idle.copy(changed = 4), true))
    }

    @Test fun aFailedCheckSaysWhyAndNothingShowsOtherwise() {
        assertNull(spotifySyncLine(idle, false))
        assertEquals("Couldn't reach Spotify", spotifySyncLine(idle.copy(error = "Couldn't reach Spotify"), false))
        assertEquals("Couldn't reach Spotify", spotifySyncLine(idle.copy(error = "Couldn't reach Spotify"), true))
    }
}
