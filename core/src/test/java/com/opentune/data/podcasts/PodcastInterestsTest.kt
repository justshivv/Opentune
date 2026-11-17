package com.opentune.data.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the listener is into, from what they listened to: the chips under the podcast search. */
class PodcastInterestsTest {
    private val now = 100L * 86_400_000
    private val day = 86_400_000L
    private val min = 60_000L

    private fun heard(title: String, show: String, minutes: Long, daysAgo: Long, finished: Boolean = false) = Podcasts.Progress(
        PodcastEpisode("$show-$title".take(40), title, show, showBrowseId = "MPSP$show"),
        positionMs = minutes * min, durationMs = 60 * min, updatedAt = now - daysAgo * day, finished = finished,
    )

    @Test fun showsListenedToMostComeFirstNotJustTheLatest() {
        val history = listOf(
            heard("292: Může Babiš ignorovat Letnou?", "Kecy a politika", 50, daysAgo = 3),
            heard("291: Kdo bude lídrem opozice?", "Kecy a politika", 55, daysAgo = 10),
            heard("The $40 Spyware", "Darknet Diaries", 20, daysAgo = 0),
            heard("Old one", "Huberman Lab", 90, daysAgo = 300),
        )
        val shows = PodcastInterests.topShows(history, subscribed = listOf(PodcastShow("MPSPsub", "Subscribed show")), now = now)
        // Most listening first, though another show was played since; a year-old listen counts for little.
        assertEquals(listOf("Kecy a politika", "Darknet Diaries", "Subscribed show", "Huberman Lab"), shows.map { it.title })
        assertEquals(2, PodcastInterests.topShows(history, emptyList(), now = now, limit = 2).size)
    }

    @Test fun listeningPutsTopicsFirst() {
        val history = listOf(
            heard("292: Může Babiš ignorovat Letnou?", "Kecy a politika", 50, daysAgo = 3),
            heard("The $40 Spyware Loved By Hackers", "Darknet Diaries", 40, daysAgo = 1),
        )
        val scores = PodcastInterests.topicScores(history, now)
        assertTrue(scores.keys.containsAll(listOf("News", "Technology")))
        val order = PodcastInterests.topicsInOrder(emptyMap(), scores)
        assertEquals(setOf("News", "Technology"), order.take(2).toSet())
        // Topics picked from the chips still count, and the rest keep their order.
        assertEquals("Sports", PodcastInterests.topicsInOrder(mapOf("Sports" to 5), scores).first())
        assertEquals(Podcasts.TOPICS, PodcastInterests.topicsInOrder(emptyMap(), emptyMap()))
        assertEquals(listOf("Sports", "News", "Technology").toSet(), PodcastInterests.favouriteTopics(mapOf("Sports" to 1), scores).toSet())
    }

    @Test fun wordsMatchWithoutAccentsAndNotInsideOthers() {
        val history = listOf(heard("Válka na Ukrajině", "Show", 30, 0), heard("Warm weather tips", "Other", 30, 0))
        val scores = PodcastInterests.topicScores(history, now)
        // "Ukrajině" is the news; "Warm" isn't "war".
        assertEquals(PodcastInterests.weight(history[0], now), scores["News"]!!, 0.001)
    }

    @Test fun readsYouTubeMusicDurations() {
        assertEquals(25, PodcastInterests.minutes("25 min"))
        assertEquals(63, PodcastInterests.minutes("1 hr 3 min"))
        assertEquals(188, PodcastInterests.minutes("3 hr 8 min"))
        assertEquals(1, PodcastInterests.minutes("45 sec"))
        assertNull(PodcastInterests.minutes(null))
        assertNull(PodcastInterests.minutes("Nov 4"))
    }
}
