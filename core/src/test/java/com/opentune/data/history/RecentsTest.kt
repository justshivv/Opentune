package com.opentune.data.history

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsTest {
    private fun play(id: String, at: Long) = PlayRecord(id, "Song $id", "Artist", playedAt = at)

    @Test fun theNewestSongsEachOnceInTheOrderLastPlayed() {
        val history = listOf(play("a", 9), play("b", 8), play("a", 7), play("c", 6), play("b", 5), play("d", 4))
        assertEquals(listOf("a", "b", "c"), History.recents(history, 3).map { it.videoId })
        assertEquals(listOf("a", "b", "c", "d"), History.recents(history, 10).map { it.videoId })
        assertEquals(emptyList<String>(), History.recents(emptyList(), 5).map { it.videoId })
    }
}
