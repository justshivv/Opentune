package com.opentune.data.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastsTest {
    @Test
    fun randomTopicsOnlyWhenSuggestionsDontFillThePage() {
        val show = PodcastShow("MPSPx", "X")
        val ep = PodcastEpisode("e", "E", "S")
        val two = Podcasts.ForYou(listOf("A" to listOf(show), "B" to listOf(show)), emptyList())
        val oneAndEpisodes = Podcasts.ForYou(listOf("A" to listOf(show)), listOf(ep, ep.copy(videoId = "f"), ep.copy(videoId = "g")))
        val thin = Podcasts.ForYou(listOf("A" to listOf(show)), listOf(ep))
        assertTrue(Podcasts.enoughFor(two))
        assertTrue(Podcasts.enoughFor(oneAndEpisodes))
        assertFalse(Podcasts.enoughFor(thin))
        // Enough: no random topics; a favourite one stays.
        assertEquals(emptyList<String>(), Podcasts.topicsToShow(1, nothingElse = false, enough = true, favourites = emptyList()))
        assertEquals(listOf("Science"), Podcasts.topicsToShow(1, nothingElse = false, enough = true, favourites = listOf("Science", "News")))
        // Not enough: two, a favourite first. Nothing else at all: four.
        assertEquals("Science", Podcasts.topicsToShow(1, nothingElse = false, enough = false, favourites = listOf("Science")).first())
        assertEquals(2, Podcasts.topicsToShow(1, nothingElse = false, enough = false, favourites = emptyList()).size)
        assertEquals(4, Podcasts.topicsToShow(1, nothingElse = true, enough = false, favourites = emptyList()).toSet().size)
    }

    @Test
    fun topicsPickedMostComeFirst() {
        val order = Podcasts.topicsInOrder(mapOf("Science" to 3, "Comedy" to 1))
        assertEquals(listOf("Science", "Comedy", "News", "True crime"), order.take(4))
        assertEquals(Podcasts.TOPICS.toSet(), order.toSet())
        assertEquals(Podcasts.TOPICS, Podcasts.topicsInOrder(emptyMap()))
    }

    private fun related(): Pair<List<PodcastEpisode>, List<PodcastShow>> {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.classLoader!!.getResource("podcasts/related.json")!!.readText())
        return PodcastParser.episodesIn(root) to PodcastParser.showsIn(root)
    }

    @Test
    fun relatedTabGivesEpisodesAndShows() {
        // Saved from YouTube Music beside an episode of Kecy a politika, signed out.
        val (episodes, shows) = related()
        assertEquals(4, episodes.size)
        assertEquals(4, shows.size)
        assertTrue(shows.all { it.browseId.startsWith("MPSP") && it.title.isNotBlank() })
    }

    @Test
    fun suggestionsLeaveOutWhatsKnownAndRepeatNothing() {
        val r = related()
        val (episodes, shows) = r
        val seed = PodcastEpisode("seed1234567", "Kecy a politika 292", "Kecy a politika", showBrowseId = "MPSPseedshow")
        val other = PodcastEpisode("seed7654321", "Another", "Other show", showBrowseId = "MPSPothershow")
        val s = Podcasts.suggestions(
            seeds = listOf(seed, other),
            related = listOf(r, r),
            subscribed = setOf(shows[0].browseId),
            heard = setOf(episodes[0].videoId),
        )
        // One shelf: the second seed brought only shows already placed under the first.
        assertEquals(1, s.shows.size)
        assertEquals("Kecy a politika", s.shows[0].first)
        assertEquals(shows.drop(1).map { it.browseId }, s.shows[0].second.map { it.browseId })
        assertEquals(episodes.drop(1).map { it.videoId }, s.episodes.map { it.videoId })
        // Nothing listened to yet, nothing loaded: no suggestions, no failure.
        assertTrue(Podcasts.suggestions(listOf(seed), listOf(null), emptySet(), emptySet()).let { it.shows.isEmpty() && it.episodes.isEmpty() })
    }

    @Test
    fun seedsAreTheLatestListenedOneAShow() {
        fun p(id: String, show: String, at: Long) = Podcasts.Progress(PodcastEpisode(id, id, show, showBrowseId = "MPSP$show"), 60_000, 600_000, at)
        val all = listOf(p("a", "x", 1), p("b", "x", 5), p("c", "y", 3), p("d", "z", 4), p("e", "w", 2)).associateBy { it.episode.videoId }
        assertEquals(listOf("b", "d", "c"), Podcasts.seeds(all).map { it.videoId })
    }

    @Test
    fun frontPageTopicsDifferAndChangeDaily() {
        // Four when YouTube Music sends no podcasts of its own, all different.
        val four = Podcasts.topicPicks(day = 20_000, count = 4)
        assertEquals(4, four.toSet().size)
        assertTrue(four.all { it in Podcasts.TOPICS })
        assertTrue(Podcasts.topicPicks(20_001, 2) != Podcasts.topicPicks(20_000, 2))
        // Every topic comes up over the days.
        assertEquals(Podcasts.TOPICS.toSet(), (0 until Podcasts.TOPICS.size).map { Podcasts.topicPicks(it, 1).single() }.toSet())
    }


    private val sampleEpisode = PodcastEpisode(
        videoId = "ep_test_01",
        title = "Episode 1: The Beginning",
        showTitle = "Test Podcast",
        durationText = "45:00",
        published = "Yesterday",
        showBrowseId = "MPSP_test_show"
    )

    private val sampleShow = PodcastShow(
        browseId = "MPSP_test_show",
        title = "Test Podcast",
        author = "Test Creator",
        description = "A podcast about testing"
    )

    @Test
    fun progressFractionCalculatesCorrectly() {
        // Zero duration should yield 0f fraction
        val zeroDuration = Podcasts.Progress(sampleEpisode, positionMs = 5_000L, durationMs = 0L, updatedAt = 1000L)
        assertEquals(0f, zeroDuration.fraction, 0.0001f)

        // Normal half-way progress
        val halfProgress = Podcasts.Progress(sampleEpisode, positionMs = 30_000L, durationMs = 60_000L, updatedAt = 1000L)
        assertEquals(0.5f, halfProgress.fraction, 0.0001f)

        // Progress exceeding duration is clamped to 1f
        val overProgress = Podcasts.Progress(sampleEpisode, positionMs = 90_000L, durationMs = 60_000L, updatedAt = 1000L)
        assertEquals(1f, overProgress.fraction, 0.0001f)

        // Negative progress is clamped to 0f
        val negativeProgress = Podcasts.Progress(sampleEpisode, positionMs = -10_000L, durationMs = 60_000L, updatedAt = 1000L)
        assertEquals(0f, negativeProgress.fraction, 0.0001f)
    }

    @Test
    fun resumeAtRespectsThresholds() {
        val durationMs = 120_000L // 2 minutes

        // 1. Not in progress -> null
        assertNull(Podcasts.resumeAt("non_existent_video_id"))

        // 2. Started but under 30s threshold -> null (start from top)
        Podcasts.starting(sampleEpisode)
        Podcasts.savePosition(sampleEpisode.videoId, positionMs = 15_000L, durationMs = durationMs)
        assertNull(Podcasts.resumeAt(sampleEpisode.videoId))

        // 3. Past 30s and well before end -> returns position
        Podcasts.savePosition(sampleEpisode.videoId, positionMs = 45_000L, durationMs = durationMs)
        assertEquals(45_000L, Podcasts.resumeAt(sampleEpisode.videoId))

        // 4. Within the last 30s of duration -> null (considered almost done)
        Podcasts.savePosition(sampleEpisode.videoId, positionMs = 105_000L, durationMs = durationMs)
        assertNull(Podcasts.resumeAt(sampleEpisode.videoId))

        // 5. Explicitly marked finished -> null
        Podcasts.savePosition(sampleEpisode.videoId, positionMs = 50_000L, durationMs = durationMs)
        Podcasts.markPlayed(sampleEpisode.videoId, played = true)
        assertNull(Podcasts.resumeAt(sampleEpisode.videoId))
    }

    @Test
    fun startingDoesNotOverwriteExistingProgress() {
        val uniqueEp = sampleEpisode.copy(videoId = "unique_ep_start")
        Podcasts.starting(uniqueEp)
        assertTrue(Podcasts.isEpisode(uniqueEp.videoId))

        // Advance position
        Podcasts.savePosition(uniqueEp.videoId, positionMs = 50_000L, durationMs = 100_000L)
        assertEquals(50_000L, Podcasts.resumeAt(uniqueEp.videoId))

        // Calling starting again must retain existing position
        Podcasts.starting(uniqueEp)
        assertEquals(50_000L, Podcasts.resumeAt(uniqueEp.videoId))
    }

    @Test
    fun markPlayedTogglesFinishedAndResetsPositionWhenUnmarked() {
        val uniqueEp = sampleEpisode.copy(videoId = "unique_ep_played")
        Podcasts.starting(uniqueEp)
        Podcasts.savePosition(uniqueEp.videoId, positionMs = 40_000L, durationMs = 100_000L)

        // Mark as played
        Podcasts.markPlayed(uniqueEp.videoId, played = true)
        val progressMap = Podcasts.progress.value
        val progress = progressMap[uniqueEp.videoId]
        assertNotNull(progress)
        assertTrue(progress!!.finished)

        // Mark as unplayed should reset position to 0 and finished to false
        Podcasts.markPlayed(uniqueEp.videoId, played = false)
        val resetProgress = Podcasts.progress.value[uniqueEp.videoId]
        assertNotNull(resetProgress)
        assertFalse(resetProgress!!.finished)
        assertEquals(0L, resetProgress.positionMs)
    }

    @Test
    fun inProgressFiltersOutFinishedAndUnderThresholdEpisodes() {
        val ep1 = sampleEpisode.copy(videoId = "ep_in_progress_1")
        val ep2 = sampleEpisode.copy(videoId = "ep_in_progress_2")
        val ep3 = sampleEpisode.copy(videoId = "ep_in_progress_3")

        val now = System.currentTimeMillis()
        val map = mapOf(
            ep1.videoId to Podcasts.Progress(ep1, positionMs = 40_000L, durationMs = 100_000L, updatedAt = now, finished = false),
            ep2.videoId to Podcasts.Progress(ep2, positionMs = 10_000L, durationMs = 100_000L, updatedAt = now + 100, finished = false), // under 30s
            ep3.videoId to Podcasts.Progress(ep3, positionMs = 50_000L, durationMs = 100_000L, updatedAt = now + 200, finished = true),  // finished
        )

        val inProgressList = Podcasts.inProgress(map)
        assertEquals(1, inProgressList.size)
        assertEquals(ep1.videoId, inProgressList.first().episode.videoId)
    }

    @Test
    fun inProgressSortsByMostRecentlyUpdatedFirst() {
        val epA = sampleEpisode.copy(videoId = "ep_sort_a")
        val epB = sampleEpisode.copy(videoId = "ep_sort_b")

        val now = System.currentTimeMillis()
        val map = mapOf(
            epA.videoId to Podcasts.Progress(epA, positionMs = 40_000L, durationMs = 100_000L, updatedAt = now - 5_000L),
            epB.videoId to Podcasts.Progress(epB, positionMs = 45_000L, durationMs = 100_000L, updatedAt = now),
        )

        val list = Podcasts.inProgress(map)
        assertEquals(2, list.size)
        assertEquals(epB.videoId, list[0].episode.videoId)
        assertEquals(epA.videoId, list[1].episode.videoId)
    }

    @Test
    fun subscriptionsAddAndRemoveCorrectly() {
        assertFalse(Podcasts.isSubscribed(sampleShow.browseId))

        // Subscribe
        Podcasts.setSubscribed(sampleShow, on = true)
        assertTrue(Podcasts.isSubscribed(sampleShow.browseId))

        // Description should be stripped in stored show to save memory
        val saved = Podcasts.subscriptions.value.firstOrNull { it.browseId == sampleShow.browseId }
        assertNotNull(saved)
        assertNull(saved!!.description)

        // Duplicate subscribe should not add twice
        Podcasts.setSubscribed(sampleShow, on = true)
        assertEquals(1, Podcasts.subscriptions.value.count { it.browseId == sampleShow.browseId })

        // Unsubscribe
        Podcasts.setSubscribed(sampleShow, on = false)
        assertFalse(Podcasts.isSubscribed(sampleShow.browseId))
    }
}
