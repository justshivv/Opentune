package com.opentune.data.lyrics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsPickTest {
    private val results = Json.parseToJsonElement(
        """
        [
          {"id":1,"duration":207.0,"syncedLyrics":"[00:05.00]music video cut","plainLyrics":"x"},
          {"id":2,"duration":200.5,"syncedLyrics":"[00:01.00]album cut","plainLyrics":"x"},
          {"id":3,"duration":200.0,"syncedLyrics":null,"plainLyrics":"plain only"},
          {"id":4,"duration":260.0,"syncedLyrics":"[00:01.00]extended mix","plainLyrics":"x"}
        ]
        """,
    )

    private fun id(o: kotlinx.serialization.json.JsonObject?) = o?.get("id")?.toString()

    @Test fun takesTheClosestSyncedRecordingNotTheFirstListed() {
        assertEquals("2", id(LyricsRepository.pick(results, 200.0)))
    }

    @Test fun aLongerCutStillMatchesItsOwnLyrics() {
        assertEquals("1", id(LyricsRepository.pick(results, 207.3)))
    }

    @Test fun nothingSyncedCloseEnoughFallsBackToPlain() {
        val plainOnly = Json.parseToJsonElement("""[{"id":3,"duration":200.0,"plainLyrics":"plain only"},{"id":4,"duration":260.0,"syncedLyrics":"[00:01.00]x"}]""")
        assertEquals("3", id(LyricsRepository.pick(plainOnly, 200.0)))
    }

    @Test fun titleOnlyHitsMustBeTheSameArtist() {
        val hits = Json.parseToJsonElement("""[{"id":1,"artistName":"Beyonce","duration":200.0},{"id":2,"artistName":"A Cover Band","duration":200.0},{"id":3,"artistName":"Beyoncé feat. JAY-Z","duration":200.0}]""")
        val kept = LyricsRepository.sameArtist(hits, "Beyoncé").map { id(it as kotlinx.serialization.json.JsonObject) }
        assertEquals(listOf("1", "3"), kept)
        assertEquals(0, LyricsRepository.sameArtist(hits, "").size)
    }
}
