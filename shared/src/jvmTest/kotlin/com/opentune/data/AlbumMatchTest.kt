package com.opentune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMatchTest {
    @Test fun aVideoTitleMatchesTheAlbumTrack() {
        assertTrue(AlbumMatch.sameTitle("Roj Roj Aankhon Tale (From \"Jeeva\")", "Roj Roj Aankhon Tale"))
        assertTrue(AlbumMatch.sameTitle("Blinding Lights (Official Video)", "Blinding Lights"))
        assertTrue(AlbumMatch.sameTitle("Tum Hi Ho - Full Song | Aashiqui 2", "Tum Hi Ho"))
        assertFalse(AlbumMatch.sameTitle("Tum Hi Ho", "Tum Se Hi"))
    }

    @Test fun theSearchUsesTheBareTitle() {
        assertEquals("Roj Roj Aankhon Tale", AlbumMatch.bareTitle("Roj Roj Aankhon Tale (From \"Jeeva\")"))
        // A title that is all brackets is kept as it is.
        assertEquals("(Intro)", AlbumMatch.bareTitle("(Intro)"))
    }

    @Test fun theFilmIsReadFromTheTitle() {
        assertEquals("Jeeva", AlbumMatch.fromTitle("Roj Roj Aankhon Tale (From \"Jeeva\")"))
        assertEquals("Dil Se", AlbumMatch.fromTitle("Chaiyya Chaiyya (From Dil Se)"))
        assertEquals("Jeeva", AlbumMatch.fromTitle("Song [From the film “Jeeva”]"))
        assertNull(AlbumMatch.fromTitle("Blinding Lights (Official Video)"))
    }

    @Test fun artistsAreMatchedBySurname() {
        assertTrue(AlbumMatch.sharesArtist("Asha Bhosle & R. D. Burman", "Asha Bhosle, Rahul Dev Burman"))
        assertTrue(AlbumMatch.sharesArtist("Album • R.D. Burman • 1986", "Rahul Dev Burman"))
        assertFalse(AlbumMatch.sharesArtist("Album • Kishore Kumar • 1980", "Asha Bhosle, Rahul Dev Burman"))
    }
}
