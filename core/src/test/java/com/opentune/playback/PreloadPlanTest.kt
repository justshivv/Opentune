package com.opentune.playback

import com.opentune.data.history.PlayRecord
import com.opentune.data.lossless.SourceTier
import com.opentune.data.model.QueueTier
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadPlanTest {
    private val fresh = SongHistory()
    private fun song(id: String) = Song(id, "Title $id", "Artist", null, "3:00")

    @Test
    fun theNextSongIsLikelierThanOnesFurtherOnAndRadioLessThanTheListenersOwn() {
        val next = PlayChance.inQueue(0, QueueTier.CONTEXT, fresh)
        val third = PlayChance.inQueue(2, QueueTier.CONTEXT, fresh)
        assertTrue(next > third)
        assertTrue(PlayChance.inQueue(0, QueueTier.USER, fresh) > PlayChance.inQueue(0, QueueTier.AUTOPLAY, fresh))
        assertTrue(next >= PlayChance.LIKELY)
    }

    @Test
    fun aSongAlwaysSkippedCountsForHalfAndOneHeardThroughKeepsItsChance() {
        val skipped = SongHistory(plays = 2, skips = 2)
        val heard = SongHistory(plays = 4, skips = 1)
        assertEquals(PlayChance.inQueue(1, QueueTier.CONTEXT, fresh) / 2, PlayChance.inQueue(1, QueueTier.CONTEXT, skipped), 1e-9)
        assertEquals(PlayChance.inQueue(1, QueueTier.CONTEXT, fresh), PlayChance.inQueue(1, QueueTier.CONTEXT, heard), 1e-9)
    }

    @Test
    fun aSongOftenPlayedFromTheScreenIsLikelierThanOneNeverPlayed() {
        assertTrue(PlayChance.onScreen(0, SongHistory(plays = 5)) > PlayChance.onScreen(0, fresh))
        assertTrue(PlayChance.onScreen(0, fresh) > PlayChance.onScreen(3, fresh))
    }

    @Test
    fun theNextSongIsAlwaysReadiedWithItsLyricsAndAtLeastTenSecondsEvenWhenUsuallySkipped() {
        val plan = PreloadSteps.forQueue(listOf(0.2, 0.1), unmetered = false, boost = false)
        assertEquals(setOf(0), plan.sources[SourceTier.HIGH_BITRATE])
        assertEquals(setOf(0), plan.sources[SourceTier.LOSSLESS])
        assertEquals(setOf(0), plan.lyrics)
        assertEquals(setOf(0), plan.warm)
        assertEquals(PreloadSteps.BUFFER_US, plan.nextBufferUs)
    }

    @Test
    fun theSecondSongGetsItsFlacAndLyricsOnlyWhenLikelyAndNothingPastTheFifthIsLookedUp() {
        val likely = PreloadSteps.forQueue(listOf(0.9, 0.7, 0.6, 0.5, 0.4, 0.35), unmetered = false, boost = false)
        assertEquals(setOf(0, 1), likely.sources[SourceTier.LOSSLESS])
        assertEquals(setOf(0, 1), likely.lyrics)
        assertEquals(setOf(0, 1, 2, 3, 4), likely.sources[SourceTier.HIGH_BITRATE])
        assertEquals(setOf(0, 1, 2), likely.warm)
        val unlikely = PreloadSteps.forQueue(listOf(0.9, 0.4, 0.2), unmetered = false, boost = false)
        assertEquals(setOf(0), unlikely.sources[SourceTier.LOSSLESS])
        assertEquals(setOf(0, 1), unlikely.sources[SourceTier.HIGH_BITRATE])
    }

    @Test
    fun theLongerBufferIsForALikelyNextSongOnWiFiOrWithTheBoost() {
        assertEquals(PreloadSteps.BUFFER_LIKELY_US, PreloadSteps.forQueue(listOf(0.8), unmetered = true, boost = false).nextBufferUs)
        assertEquals(PreloadSteps.BUFFER_US, PreloadSteps.forQueue(listOf(0.8), unmetered = false, boost = false).nextBufferUs)
        assertEquals(PreloadSteps.BUFFER_US, PreloadSteps.forQueue(listOf(0.4), unmetered = true, boost = false).nextBufferUs)
        assertEquals(PreloadSteps.BUFFER_LIKELY_US, PreloadSteps.forQueue(listOf(0.4), unmetered = true, boost = true).nextBufferUs)
    }

    @Test
    fun offTheQueueTheThreeLikeliestAreReadiedAndOnlyOnWiFiWithTheirAudio() {
        val shown = listOf(song("a") to 0.5, song("b") to 0.2, song("c") to 0.45, song("d") to 0.4, song("e") to 0.35, song("a") to 0.9)
        val data = PreloadSteps.offQueue(shown, unmetered = false)
        assertEquals(listOf("a", "c", "d"), data.map { it.song.videoId })
        assertTrue(data.none { it.audio })
        val wifi = PreloadSteps.offQueue(shown, unmetered = true)
        assertTrue(wifi.all { it.audio })
    }

    @Test
    fun theSongPlayingComesFirstWhileItIsChosenBuffersOrHasTooLittleLoaded() {
        assertTrue(PreloadGate.holds(choosing = true, buffering = false, playing = true, aheadMs = 60_000, remainingMs = 120_000, slow = false))
        assertTrue(PreloadGate.holds(choosing = false, buffering = true, playing = false, aheadMs = 0, remainingMs = 120_000, slow = false))
        assertTrue(PreloadGate.holds(choosing = false, buffering = false, playing = true, aheadMs = 10_000, remainingMs = 120_000, slow = false))
        assertFalse(PreloadGate.holds(choosing = false, buffering = false, playing = true, aheadMs = 20_000, remainingMs = 120_000, slow = false))
        assertTrue(PreloadGate.holds(choosing = false, buffering = false, playing = true, aheadMs = 20_000, remainingMs = 120_000, slow = true))
        // Its last seconds are all loaded: nothing more to wait for.
        assertFalse(PreloadGate.holds(choosing = false, buffering = false, playing = true, aheadMs = 8_000, remainingMs = 8_000, slow = false))
        assertFalse(PreloadGate.holds(choosing = false, buffering = false, playing = false, aheadMs = 0, remainingMs = 120_000, slow = false))
    }

    @Test
    fun noMoreThanTwentyMegabytesOfAudioAnHour() {
        var t = 0L
        val budget = AudioBudget(now = { t })
        budget.spend(15L * 1024 * 1024)
        assertEquals(5L * 1024 * 1024, budget.left())
        t += 59 * 60_000L
        assertEquals(5L * 1024 * 1024, budget.left())
        t += 60_000L
        assertEquals(20L * 1024 * 1024, budget.left())
    }

    @Test
    fun aSongReadiedIsNotReadiedAgainSoonAndOneThatFailedWaitsFiveMinutes() {
        var t = 0L
        val songs = ReadiedSongs(now = { t })
        assertTrue(songs.due("a"))
        songs.readied("a")
        songs.failed("b")
        t += 4 * 60_000L
        assertFalse(songs.due("a"))
        assertFalse(songs.due("b"))
        t += 60_000L
        assertTrue(songs.due("b"))
        assertFalse(songs.due("a"))
        t += 10 * 60_000L
        assertTrue(songs.due("a"))
    }

    @Test
    fun theQueueReadiesThePlacesThePlanPicksWithTheFlacOnlyWhereItSays() {
        val upcoming = listOf(song("a"), song("b"), song("c"), song("a"))
        val at = mapOf(SourceTier.HIGH_BITRATE to setOf(0, 2, 3), SourceTier.LOSSLESS to setOf(0))
        val wanted = aheadPlanned(upcoming, preload = true, allowed = setOf(SourceTier.LOSSLESS, SourceTier.HIGH_BITRATE), at = at)
        assertEquals(listOf("a" to true, "c" to false), wanted.map { it.id to it.lossless })
        assertEquals(setOf(SourceTier.LOSSLESS, SourceTier.HIGH_BITRATE), wanted[0].tiers)
        assertEquals(setOf(SourceTier.HIGH_BITRATE), wanted[1].tiers)
        assertTrue(aheadPlanned(upcoming, preload = false, allowed = setOf(SourceTier.LOSSLESS, SourceTier.HIGH_BITRATE), at = at).isEmpty())
        // The high-bitrate source not allowed: only the FLAC places.
        assertEquals(listOf("a"), aheadPlanned(upcoming, preload = true, allowed = setOf(SourceTier.LOSSLESS), at = at).map { it.id })
    }

    @Test
    fun theHistoryIsGatheredAgainOnlyForANewList() {
        val records = listOf(PlayRecord("a", "A", "Band", playedAt = 1))
        val liked = emptyList<String>()
        val first = PreloadHistory.of(records, liked) { emptySet() }
        assertTrue(first === PreloadHistory.of(records, liked) { emptySet() })
        assertFalse(first === PreloadHistory.of(records.toList(), liked) { emptySet() })
    }

    @Test
    fun theHistoryCountsPlaysSkipsAndHowMuchOfAnArtistIsHeard() {
        val records = listOf(
            PlayRecord("a", "A", "Band", durationText = "3:00", playedAt = 1, listenedMs = 180_000),
            PlayRecord("a", "A", "Band", durationText = "3:00", playedAt = 2, listenedMs = 5_000),
            PlayRecord("b", "B", "Band, Guest", durationText = "2:00", playedAt = 3, listenedMs = 120_000),
        )
        val history = PreloadHistory(records, liked = setOf("b"))
        val a = history.of("a", "Band")
        assertEquals(2, a.plays)
        assertEquals(1, a.skips)
        assertFalse(a.liked)
        assertTrue(history.of("b", "Band").liked)
        assertEquals((1.0 + 5.0 / 180 + 1.0) / 3, a.artistFinished!!, 1e-9)
        assertEquals(2L, history.record("a")!!.playedAt)
    }

    @Test
    fun theScreensTheListenerPlaysFromCountForMoreWithinLimits() {
        val stats = OriginStats()
        assertEquals(1.0, stats.factor("search"), 1e-9)
        repeat(20) { stats.shown("speed-dial"); stats.started("speed-dial") }
        repeat(20) { stats.shown("search") }
        assertTrue(stats.factor("speed-dial") > 1.0)
        assertTrue(stats.factor("search") < 1.0)
        assertTrue(stats.factor("search") >= OriginStats.MIN)
        assertTrue(stats.factor("speed-dial") <= OriginStats.MAX)
        // Even the least played-from screen still readies its first song.
        assertTrue(PlayChance.onScreen(0, fresh) * OriginStats.MIN >= PlayChance.WORTH)
        assertEquals("collection", OriginStats.origin("collection:VL123"))
    }

    @Test
    fun aScreensRecordIsKeptAndFadesOnceLong() {
        val stats = OriginStats()
        repeat(OriginStats.FADE_AT + 1) { stats.shown("search") }
        assertTrue(stats.counts().getValue("search").first <= OriginStats.FADE_AT / 2 + 1)
        val again = OriginStats().apply { load(stats.counts()) }
        assertEquals(stats.factor("search"), again.factor("search"), 1e-9)
    }
}
