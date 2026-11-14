package com.opentune.data.releases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistSyncTest {
    private fun plan(remote: Set<String>, local: Set<String>, agreed: Set<String>?, pending: Set<String> = emptySet()) =
        ArtistSync.plan(remote, local, agreed, pending)

    @Test
    fun firstSyncFollowsEverySubscriptionAndTouchesNothingElse() {
        val p = plan(remote = setOf("UCa", "UCb"), local = setOf("UCb", "UCmine"), agreed = null)
        assertEquals(listOf("UCa"), p.followHere)
        // Followed here before the sync knew anything: left alone, not pushed, not taken away.
        assertTrue(p.unfollowHere.isEmpty())
        assertTrue(p.subscribeThere.isEmpty())
        assertTrue(p.unsubscribeThere.isEmpty())
    }

    @Test
    fun aNewSubscriptionThereIsFollowedHere() {
        val p = plan(remote = setOf("UCa", "UCnew"), local = setOf("UCa"), agreed = setOf("UCa"))
        assertEquals(listOf("UCnew"), p.followHere)
        assertTrue(p.unsubscribeThere.isEmpty())
    }

    @Test
    fun unsubscribingThereStopsTheFollowHere() {
        val p = plan(remote = setOf("UCa"), local = setOf("UCa", "UCgone"), agreed = setOf("UCa", "UCgone"))
        assertEquals(listOf("UCgone"), p.unfollowHere)
        assertTrue(p.followHere.isEmpty())
    }

    @Test
    fun unfollowingHereUnsubscribesThere() {
        val p = plan(remote = setOf("UCa", "UCb"), local = setOf("UCa"), agreed = setOf("UCa", "UCb"))
        assertEquals(listOf("UCb"), p.unsubscribeThere)
        // Not followed back here: the person stopped following it.
        assertTrue(p.followHere.isEmpty())
    }

    @Test
    fun aStarThatFailedToSubscribeIsTriedAgainAndNotTakenAway() {
        val p = plan(remote = setOf("UCa"), local = setOf("UCa", "UCstar"), agreed = setOf("UCa", "UCstar"), pending = setOf("UCstar"))
        assertEquals(listOf("UCstar"), p.subscribeThere)
        assertTrue(p.unfollowHere.isEmpty())
    }

    @Test
    fun anEmptyAnswerNeverUnfollowsEveryone() {
        val p = plan(remote = emptySet(), local = setOf("UCa", "UCb"), agreed = setOf("UCa", "UCb"))
        assertTrue(p.unfollowHere.isEmpty())
    }

    @Test
    fun followsMadeHereWithoutTheAccountStayLocal() {
        val p = plan(remote = setOf("UCa"), local = setOf("UCa", "UCpicked"), agreed = setOf("UCa"))
        assertTrue(p.subscribeThere.isEmpty())
        assertTrue(p.unfollowHere.isEmpty())
    }
}
