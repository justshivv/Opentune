package com.opentune.data.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastsTest {

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
