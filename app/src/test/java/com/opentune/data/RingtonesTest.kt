package com.opentune.data

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class RingtonesTest {
    @Test fun namesTheCopyArtistDashTitle() {
        assertEquals("The Weeknd - Blinding Lights.m4a", Ringtones.fileName(Song("id", "Blinding Lights", "The Weeknd", null), "m4a"))
    }

    @Test fun takesOutCharactersFileSystemsRefuse() {
        assertEquals("AC_DC - What_ Why_.webm", Ringtones.fileName(Song("id", "What? Why?", "AC/DC", null), "webm"))
        assertEquals("abc123.webm", Ringtones.fileName(Song("abc123", "", "", null), "webm"))
    }
}
