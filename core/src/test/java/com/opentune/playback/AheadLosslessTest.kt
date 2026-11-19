package com.opentune.playback

import com.opentune.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AheadLosslessTest {
    private fun song(id: String) = Song(id, "Song $id", "Artist", null)
    private val queue = listOf("a", "b", "c", "d", "e", "f", "g").map(::song)

    // ---- What's readied ------------------------------------------------------------------

    @Test fun withPreloadOffNothingAheadIsLookedUp() {
        assertEquals(emptyList<AheadWant>(), aheadWanted(queue, preload = false, saavn = true, lossless = true, saavnAhead = 5, losslessAhead = 2))
    }

    @Test fun onlyTheNextTwoHaveTheirFlacLookedUp() {
        val wanted = aheadWanted(queue, preload = true, saavn = true, lossless = true, saavnAhead = 5, losslessAhead = 2)
        assertEquals(listOf("a", "b", "c", "d", "e"), wanted.map { it.id })
        assertEquals(listOf("a", "b"), wanted.filter { it.lossless }.map { it.id })
    }

    @Test fun withJioSaavnOffOnlyTheNextTwoAreLookedUpAtAll() {
        val wanted = aheadWanted(queue, preload = true, saavn = false, lossless = true, saavnAhead = 5, losslessAhead = 2)
        assertEquals(listOf("a", "b"), wanted.map { it.id })
        assertTrue(wanted.all { it.lossless })
    }

    @Test fun losslessOffLooksUpJioSaavnAlone() {
        val wanted = aheadWanted(queue, preload = true, saavn = true, lossless = false, saavnAhead = 5, losslessAhead = 2)
        assertEquals(5, wanted.size)
        assertFalse(wanted.any { it.lossless })
    }

    @Test fun aSongLeftOutStillCountsAsAPlaceSoNoFlacIsLookedForFurtherAhead() {
        // The next song is a file on the phone: the one after it is the second place, the third gets JioSaavn only.
        val wanted = aheadWanted(queue, preload = true, saavn = true, lossless = true, saavnAhead = 5, losslessAhead = 2) { it.videoId != "a" }
        assertEquals(listOf("b"), wanted.filter { it.lossless }.map { it.id })
        assertFalse(wanted.any { it.id == "a" })
    }

    @Test fun aSongTwiceAheadIsReadiedOnce() {
        val wanted = aheadWanted(listOf(song("a"), song("a"), song("b")), preload = true, saavn = true, lossless = true, saavnAhead = 5, losslessAhead = 2)
        assertEquals(listOf("a", "b"), wanted.map { it.id })
    }

    // ---- How the lookups run ---------------------------------------------------------------

    private class Recorder {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var running = 0
        var most = 0
        var gate: CompletableDeferred<Unit>? = null
        suspend fun look(s: Song, lossless: Boolean) {
            calls += s.videoId to lossless
            running++; most = maxOf(most, running)
            try { gate?.await() } finally { running-- }
        }
    }

    /** Lookups on [scope] that settle for 100 ms, as the player's do for 1.5 s. */
    private fun lookups(scope: CoroutineScope, r: Recorder, atOnce: Int = 2) =
        AheadLookups(scope, settleMs = 100, atOnce = atOnce) { s, l -> r.look(s, l) }

    /** Runs [block] on a scope of its own, cancelled after, so no lookup outlives a test. */
    private fun lookupTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
        try { scope.block() } finally { scope.cancel() }
    }

    private fun want(id: String, lossless: Boolean) = AheadWant(id, song(id), lossless)

    @Test fun aSongSkippedPastWithinTheSettleIsNeverLookedUp() = lookupTest {
        val r = Recorder()
        val ahead = lookups(this, r)
        ahead.update(listOf(want("a", true)), keep = "now")
        delay(50)
        // Skipped on: "a" is playing and gone from ahead, "b" is next.
        ahead.update(listOf(want("b", true)), keep = "x")
        delay(250)
        assertEquals(listOf("b" to true), r.calls)
    }

    @Test fun atMostTwoLookupsRunAtOnce() = lookupTest {
        val r = Recorder().apply { gate = CompletableDeferred() }
        val ahead = lookups(this, r)
        ahead.update(listOf(want("a", true), want("b", true), want("c", false), want("d", false)), keep = null)
        delay(250)
        assertEquals(2, r.most)
        assertEquals(2, r.calls.size)
        r.gate!!.complete(Unit)
        delay(50)
        assertEquals(4, r.calls.size)
        assertEquals(2, r.most)
    }

    @Test fun aSongStillAheadKeepsItsLookupAndIsntAskedAgain() = lookupTest {
        val r = Recorder()
        val ahead = lookups(this, r)
        ahead.update(listOf(want("a", true), want("b", true)), keep = null)
        delay(250)
        // The queue changed (radio came in): the same songs ahead aren't looked up again.
        ahead.update(listOf(want("a", true), want("b", true), want("c", false)), keep = null)
        delay(250)
        assertEquals(listOf("a" to true, "b" to true, "c" to false), r.calls)
    }

    @Test fun aSongComingNearHasItsFlacLookedUpThen() = lookupTest {
        val r = Recorder()
        val ahead = lookups(this, r)
        ahead.update(listOf(want("c", false)), keep = null)
        delay(250)
        ahead.update(listOf(want("c", true)), keep = null)
        delay(250)
        assertEquals(listOf("c" to false, "c" to true), r.calls)
    }

    @Test fun thePlayingSongKeepsItsLookup() = lookupTest {
        val r = Recorder().apply { gate = CompletableDeferred() }
        val ahead = lookups(this, r)
        ahead.update(listOf(want("a", true)), keep = null)
        delay(250)
        // "a" starts playing: its upgrade joins the search under way.
        ahead.update(emptyList(), keep = "a")
        assertTrue(ahead.looking("a"))
    }

    @Test fun turningPreloadOffCallsEveryLookupOff() = lookupTest {
        val r = Recorder().apply { gate = CompletableDeferred() }
        val ahead = lookups(this, r)
        ahead.update(listOf(want("a", true), want("b", true), want("c", false)), keep = null)
        delay(250)
        ahead.cancelAll()
        delay(50)
        assertFalse(ahead.looking("a") || ahead.looking("b") || ahead.looking("c"))
        r.gate!!.complete(Unit)
        delay(400)
        // "c" was waiting for a slot and never started.
        assertEquals(listOf("a", "b"), r.calls.map { it.first })
    }

    @Test fun aSongOpenedOnYouTubeIsSearchedAgainOnlyWhenReadiedAhead() {
        assertTrue(lookAgainForFlac(playing = false, ahead = true, losslessAllowed = true, firstForThisOpening = true, searching = false))
        assertFalse(lookAgainForFlac(playing = false, ahead = false, losslessAllowed = true, firstForThisOpening = true, searching = false))
        assertFalse(lookAgainForFlac(playing = true, ahead = true, losslessAllowed = true, firstForThisOpening = true, searching = false))
        assertFalse(lookAgainForFlac(playing = false, ahead = true, losslessAllowed = false, firstForThisOpening = true, searching = false))
        assertFalse(lookAgainForFlac(playing = false, ahead = true, losslessAllowed = true, firstForThisOpening = false, searching = false))
        assertFalse(lookAgainForFlac(playing = false, ahead = true, losslessAllowed = true, firstForThisOpening = true, searching = true))
    }

    @Test fun onlySongsAheadOpenedOnYouTubeArePointedAtTheirFlac() {
        val ahead = listOf("a" to AheadOpened.YOUTUBE, "b" to AheadOpened.YOUTUBE)
        assertEquals(listOf(1), aheadToStartOnFlac("b", ahead))
        assertEquals(emptyList<Int>(), aheadToStartOnFlac("a", listOf("a" to AheadOpened.NOT_YET)))
        assertEquals(emptyList<Int>(), aheadToStartOnFlac("a", listOf("a" to AheadOpened.EXTERNAL)))
    }
}
