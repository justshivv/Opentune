package com.opentune.data.reco

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityTest {
    private fun song(id: String, title: String, artist: String, length: String? = "3:30", video: Boolean = false) =
        Song(id, title, artist, null, durationText = length, isVideo = video)

    @Test fun theVideoAndTheTrackAreOneSong() {
        val track = song("a", "Tum Hi Ho", "Arijit Singh")
        assertTrue(SongIdentity.sameSong(track, song("b", "Tum Hi Ho (Official Video)", "Arijit Singh", "4:22", video = true)))
        assertTrue(SongIdentity.sameSong(track, song("c", "Arijit Singh - Tum Hi Ho | Lyrical Video", "T-Series", "4:21", video = true)))
        assertTrue(SongIdentity.sameSong(track, song("d", "Tum Hi Ho (Lyrics)", "Arijit Singh", "4:10")))
        assertTrue(SongIdentity.sameSong(track, song("e", "Tum Hi Ho feat. Someone", "Arijit Singh", "4:10")))
    }

    @Test fun versionsAndCoversAreTheSameSong() {
        val track = song("a", "Kesariya", "Arijit Singh")
        assertTrue(SongIdentity.sameSong(track, song("b", "Kesariya (Slowed + Reverb)", "Lofi Guy", "5:40")))
        assertTrue(SongIdentity.sameSong(track, song("c", "Kesariya - Live", "Arijit Singh", "6:00")))
        assertTrue(SongIdentity.sameSong(track, song("d", "Kesariya (Cover)", "Someone Else", "3:00")))
    }

    @Test fun differentSongsWithTheSameNameStay() {
        val adele = song("a", "Hello", "Adele", "4:55")
        assertFalse(SongIdentity.sameSong(adele, song("b", "Hello", "Lionel Richie", "4:11")))
        // A version word that's part of the name doesn't make it a version.
        assertFalse(SongIdentity.isVersion(song("c", "Live Forever", "Oasis")))
        assertFalse(SongIdentity.sameSong(song("c", "Live Forever", "Oasis", "4:36"), song("d", "Forever", "Chris Brown", "4:38")))
    }

    @Test fun distinctKeepsTheTrackOverItsVideoAndSkipsWhatsQueued() {
        val queued = listOf(song("q", "Tum Hi Ho", "Arijit Singh"))
        val radio = listOf(
            song("v", "Kesariya (Official Video)", "Arijit Singh", "4:40", video = true),
            song("t", "Kesariya", "Arijit Singh", "4:28"),
            song("x", "Tum Hi Ho (Lyrics)", "Arijit Singh", "4:20"),
            song("y", "Raataan Lambiyan", "Jubin Nautiyal"),
        )
        assertEquals(listOf("t", "y"), SongIdentity.distinct(radio, queued).map { it.videoId })
    }
}
