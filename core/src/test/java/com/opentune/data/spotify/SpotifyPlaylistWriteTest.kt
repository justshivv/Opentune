package com.opentune.data.spotify

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SpotifyPlaylistWriteTest {
    @Test fun onlyAcknowledgedChangesCountAsSaved() {
        assertEquals("new-version", SpotifyWebPlayer.playlistWriteSnapshot(201, """{"snapshot_id":"new-version"}"""))
        for ((code, body) in listOf(403 to "{}", 429 to "{}", 500 to "{}", 200 to "", 201 to "{}", 201 to """{"snapshot_id":""}""")) {
            assertThrows(IOException::class.java) { SpotifyWebPlayer.playlistWriteSnapshot(code, body) }
        }
    }
}
