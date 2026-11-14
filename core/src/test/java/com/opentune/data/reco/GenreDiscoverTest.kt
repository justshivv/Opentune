package com.opentune.data.reco

import com.opentune.data.library.HiddenList
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreDiscoverTest {
    private fun s(id: String, artist: String) = Song(id, "Song $id", artist, null)
    private val genres = mapOf("Karan Aujla" to "Punjabi", "Sidhu" to "Punjabi", "AP Dhillon" to "Punjabi", "Divine" to "Hip-Hop")

    @Test fun onlyNewSongsOfThisGenreNewArtistsFirst() {
        val radio = listOf(
            s("played", "Karan Aujla"), s("k1", "Karan Aujla"), s("s1", "Sidhu"), s("d1", "Divine"),
            s("a1", "AP Dhillon"), s("u1", "Unknown Singer"), s("k2", "Karan Aujla"), s("k3", "Karan Aujla"),
        )
        val picked = GenreDiscover.pick("Punjabi", listOf(radio), played = setOf("played"), hidden = HiddenList.EMPTY,
            genreOf = { genres[it] }, knownArtists = setOf("Karan Aujla"))
        val ids = picked.map { it.videoId }
        assertFalse("played", "played" in ids)
        assertFalse("other genre", "d1" in ids)
        // Each artist at most twice.
        assertEquals(2, picked.count { it.artist == "Karan Aujla" })
        // New artists of the genre lead, a known artist's new song after every two, the unknown genre last.
        assertEquals(listOf("s1", "a1", "k1", "k2", "u1"), ids)
    }

    @Test fun hiddenArtistsStayOut() {
        val hidden = HiddenList(artists = listOf(com.opentune.data.library.HiddenArtist("Sidhu")))
        val picked = GenreDiscover.pick("Punjabi", listOf(listOf(s("s1", "Sidhu"), s("a1", "AP Dhillon"))), emptySet(), hidden, { genres[it] }, emptySet())
        assertTrue(picked.none { it.artist == "Sidhu" })
    }
}

class MixStartsTest {
    @Test fun eachMixGetsItsStartInTurn() {
        fun mix(p: String, n: Int) = (1..n).map { Song("$p$it", "t", "a", null) }
        val out = com.opentune.data.download.mixStarts(listOf(mix("a", 20), mix("b", 3)))
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3", "b3", "a4"), out.take(7).map { it.videoId })
        // Only the first fifteen of a long mix.
        assertEquals(15 + 3, out.size)
    }
}
