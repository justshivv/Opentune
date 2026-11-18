package com.opentune.data.library

import com.opentune.data.history.PlayRecord
import com.opentune.data.history.widenRecord
import org.junit.Assert.*
import org.junit.Test

class CreditHealTest {
    private fun ref(id: String, artist: String) = SongRef(id, "Song $id", artist, null)

    @Test fun theFullestCreditWinsAcrossLists() {
        val lines = widestArtistLines(emptyMap(), listOf(
            "a" to "Calvin Harris", "a" to "Calvin Harris, Dua Lipa", "b" to "deadmau5"))
        assertEquals("Calvin Harris, Dua Lipa", lines["a"])
        // A single name widens nothing, so it isn't kept: the book doesn't grow by every song.
        assertNull(lines["b"])
    }

    @Test fun aNarrowerCreditNeverShrinksAKnownLine() {
        val lines = widestArtistLines(mapOf("a" to "deadmau5, Kaskade, Kx5"), listOf("a" to "deadmau5"))
        assertEquals("deadmau5, Kaskade, Kx5", lines["a"])
    }

    @Test fun aPlaceholderCreditIsHealedByARealOne() {
        val lines = widestArtistLines(mapOf("a" to "Unknown artist"), listOf("a" to "Kaskade, Kx5"))
        assertEquals("Kaskade, Kx5", lines["a"])
    }

    @Test fun refsAndRecordsWidenFromTheBook() {
        val lines = mapOf("a" to "Avicii, Nicky Romero")
        assertEquals("Avicii, Nicky Romero", widenRef(ref("a", "Avicii"), lines).artist)
        assertEquals("Avicii", widenRef(ref("a", "Avicii"), emptyMap()).artist)
        val record = PlayRecord("a", "Song a", "Avicii", null, null, null, playedAt = 1)
        assertEquals("Avicii, Nicky Romero", widenRecord(record, lines).artist)
        assertEquals("Avicii", widenRecord(record, emptyMap()).artist)
    }

    /** A change reads only what's new, yet every list still wears the fullest credit. */
    @Test fun healingOnlyReadsWhatsNewAndStillWidensEverywhere() {
        val credited = HashSet<String>()
        val first = healCreditsSince(LibraryDocument(playlists = listOf(LocalPlaylist("p", "Mix", listOf(ref("a", "Avicii"), ref("b", "Kygo"))))), credited)
        assertEquals("Avicii", first.playlists[0].songs[0].artist)
        // The same song liked with its full credit: the playlist's copy widens too.
        val liked = healCreditsSince(first.copy(liked = listOf(ref("a", "Avicii, Nicky Romero"))), credited)
        assertEquals("Avicii, Nicky Romero", liked.playlists[0].songs[0].artist)
        assertEquals("Avicii, Nicky Romero", liked.artistLines["a"])
        // Added again later with the short credit, it's widened from the book.
        val again = healCreditsSince(liked.copy(playlists = liked.playlists + LocalPlaylist("q", "Other", listOf(ref("a", "Avicii")))), credited)
        assertEquals("Avicii, Nicky Romero", again.playlists[1].songs[0].artist)
    }
}
