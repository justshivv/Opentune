package com.opentune.data.lossless

import com.opentune.data.lossless.TrackMatcher.Candidate
import com.opentune.data.lossless.TrackMatcher.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The add-on matcher, on the shapes that kept add-on FLACs from playing in OpenTune and not in BitChord. */
class TrackMatcherTest {
    private fun row(title: String, artist: String, seconds: Int, album: String? = null) = Candidate.of(title, artist, album, seconds * 1000L)

    @Test fun filmPackagingAndAPartialCreditStillMatch() {
        val target = Target("Paniyon Sa (From \"Satyamev Jayate\")", "Atif Aslam", 245)
        assertNotNull(TrackMatcher.score(row("Paniyon Sa", "Atif Aslam, Tulsi Kumar", 246), target))
    }

    /** The Lil Wayne remix of Believer, 3:39 on NetEase, played for the 3:36 music video: his verse from 1:25. */
    @Test fun anotherVersionWithAFeaturedArtistIsNotTheSong() {
        val remix = row("Believer", "Imagine Dragons, Lil Wayne", 219)
        assertNull(TrackMatcher.score(remix, Target("Believer", "Imagine Dragons", 216, isVideo = true)))
        assertNull(TrackMatcher.score(remix, Target("Believer", "Imagine Dragons", 204)))
    }

    @Test fun aFeaturedArtistTheSongNamesIsNoStranger() {
        assertNotNull(TrackMatcher.score(row("Believer", "Imagine Dragons, Lil Wayne", 219), Target("Believer (feat. Lil Wayne)", "Imagine Dragons", 219)))
        assertNotNull(TrackMatcher.score(row("Believer", "Imagine Dragons, Lil Wayne", 219), Target("Believer", "Imagine Dragons & Lil Wayne", 216)))
    }

    @Test fun featCreditsRemasterTagsAndOfficialAudioAreNotPartOfTheTitle() {
        assertNotNull(TrackMatcher.score(row("Blinding Lights", "The Weeknd", 200), Target("Blinding Lights (feat. Someone)", "The Weeknd", 200)))
        assertNotNull(TrackMatcher.score(row("Bohemian Rhapsody", "Queen", 355), Target("Bohemian Rhapsody - Remastered 2011", "Queen", 355)))
        assertNotNull(TrackMatcher.score(row("Tum Hi Ho", "Arijit Singh", 262), Target("Tum Hi Ho (Official Audio)", "Arijit Singh", 262)))
    }

    @Test fun aMasterAFewSecondsLongerThanYouTubesStillMatchesButTheSameLengthRanksFirst() {
        val target = Target("Wo Noor", "AP Dhillon", 180)
        val longer = row("Wo Noor", "AP Dhillon", 188, album = "Compilation")
        val same = row("Wo Noor", "AP Dhillon", 181)
        assertNotNull(TrackMatcher.score(longer, target))
        assertEquals(listOf(same, longer), TrackMatcher.ranked(listOf(longer, same), target))
        // The same artist further out is allowed, as BitChord allows it: YouTube's "song" may be the
        // video, intro and outro and all. Someone else that far out is another recording.
        assertNotNull(TrackMatcher.score(row("Wo Noor", "AP Dhillon", 230), target))
        assertNull(TrackMatcher.score(row("Wo Noor", "Someone Else", 230), target))
        assertNull(TrackMatcher.score(row("Wo Noor", "AP Dhillon", 300), target))
    }

    @Test fun otherTakesAndOtherSongsAreRefused() {
        val target = Target("Tum Hi Ho", "Arijit Singh", 262)
        assertNull(TrackMatcher.score(row("Tum Hi Ho (Remix)", "Arijit Singh", 262), target))
        assertNull(TrackMatcher.score(row("Tum Hi Ho (Instrumental)", "Arijit Singh", 262), target))
        assertNull(TrackMatcher.score(row("Tum Hi Ho", "Arijit Singh", 262, album = "Aashiqui 2 (Instrumental Edition)"), target))
        assertNull(TrackMatcher.score(row("Different Song", "Arijit Singh", 262), target))
        // A cover: right title, someone else, and not the same length to the second.
        assertNull(TrackMatcher.score(row("Tum Hi Ho", "Some Cover Band", 250), target))
    }

    @Test fun aComposerCreditPassesOnlyWhenTheLengthAgreesToTheSecond() {
        // YouTube Music files a film song under its composer; the store under its singer.
        val target = Target("Jhak Maar Ke", "Pritam", 233)
        assertNotNull(TrackMatcher.score(row("Jhak Maar Ke", "Neeraj Shridhar", 233), target))
        assertNull(TrackMatcher.score(row("Jhak Maar Ke", "Neeraj Shridhar", 241), target))
    }

    @Test fun aHyphenatedActSharesItsMembersFullNames() {
        // YouTube bills the act; the store bills its members. A master a few
        // seconds longer than YouTube's copy is refused without the shared
        // credit: the credits-disagree fallback only covers two seconds.
        val target = Target("Tainu Khabar Nahi", "Sachin-Jigar", 188)
        assertNotNull(TrackMatcher.score(row("Tainu Khabar Nahi", "Sachin Sanghvi, Jigar Saraiya, Arijit Singh", 191), target))
        assertNull(TrackMatcher.score(row("Tainu Khabar Nahi", "Vishal Dadlani, Shekhar Ravjiani", 196), target))
    }

    @Test fun queriesDropThePackagingAndTryTheTitleAlone() {
        val queries = TrackMatcher.queries(Target("Paniyon Sa (From \"Satyamev Jayate\")", "Atif Aslam, Tulsi Kumar"))
        assertEquals(listOf("paniyon sa atif aslam", "paniyon sa"), queries)
        assertTrue(TrackMatcher.queries(Target("Song (Live)", "Artist")).first().contains("live"))
    }

    /** A hyphen in a name is not a list of artists: one piece of it is never enough. */
    @Test fun aHyphenatedNameIsNotMatchedByOnePieceOfIt() {
        assertNull(TrackMatcher.score(row("Holy Grail", "Jay Rock", 320), Target("Holy Grail", "JAY-Z", 338)))
        assertNull(TrackMatcher.score(row("So Sick", "Yo Gotti", 210), Target("So Sick", "Ne-Yo", 217)))
        assertNull(TrackMatcher.score(row("Ciao Adios", "Lisa Marie Presley", 190), Target("Ciao Adios", "Anne-Marie", 200)))
        // The act itself, as one name, still matches.
        assertNotNull(TrackMatcher.score(row("Holy Grail", "JAY-Z, Justin Timberlake", 340), Target("Holy Grail", "JAY-Z", 338)))
    }
}
