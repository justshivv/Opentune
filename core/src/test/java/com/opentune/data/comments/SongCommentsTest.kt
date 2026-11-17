package com.opentune.data.comments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube's answers for a song's comments, read: real ones, saved from youtube.com. */
class SongCommentsTest {
    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResource("comments/$name")!!.readText()) as JsonObject

    @Test fun findsWhereTheCommentsStart() {
        val t = SongComments.section(load("watch.json"), "JGwWNGJdvx8", fromVideo = false)
        assertNotNull(t.first)
        assertTrue(!t.turnedOff)
    }

    @Test fun commentsTurnedOffHaveNoStart() {
        val t = SongComments.section(load("watch-off.json"), "kJQP7kiw5Fk", fromVideo = false)
        assertNull(t.first)
        assertTrue(t.turnedOff)
    }

    @Test fun readsAPageInOrderWithTheNextToken() {
        val p = SongComments.parsePage(load("page.json"))
        assertEquals(5, p.comments.size)
        val first = p.comments.first()
        assertEquals("@jhaber..2026", first.author)
        assertTrue(first.text.startsWith("“06 November 2026"))
        assertEquals("429K", first.likes)
        assertEquals("976", first.replies)
        assertTrue(first.avatarUrl!!.startsWith("https://"))
        assertNotNull(p.next)
        // Not signed in: no comment box.
        assertNull(p.createParams)
    }

    @Test fun signedOutCommentsOfferRepliesButNoButtons() {
        val p = SongComments.parsePage(load("threads.json"))
        assertEquals(2, p.comments.size)
        val first = p.comments.first()
        assertEquals("976", first.replies)
        assertNotNull("its replies can be opened", first.repliesToken)
        assertFalse(first.isReply)
        // Signed out YouTube's buttons only ask to sign in: none to offer.
        assertNull(first.likeAction)
        assertNull(first.replyParams)
        assertNull(first.deleteAction)
        assertFalse(first.liked)
    }

    @Test fun readsAPageOfRepliesWithTheTokenForMore() {
        val p = SongComments.parsePage(load("replies.json"))
        assertEquals(listOf("Ugw-KcFS4sCynHAR1P94AaABAg.9ioaZB_oKCm9iobt4I2q10", "Ugw-KcFS4sCynHAR1P94AaABAg.9ioaZB_oKCm9iofpUBYOMh"), p.comments.map { it.id })
        assertTrue(p.comments.all { it.isReply })
        assertTrue(p.comments.all { it.repliesToken == null })
        assertNotNull("Show more replies", p.next)
    }

    @Test fun signedInCommentsCarryTheirButtons() {
        val p = SongComments.parsePage(load("threads-signed-in.json"))
        val mine = p.comments.first()
        assertTrue(mine.liked)
        assertEquals("LIKE-ACTION", mine.likeAction)
        assertEquals("UNLIKE-ACTION", mine.unlikeAction)
        assertEquals("REPLY-PARAMS", mine.replyParams)
        // Delete, not Edit, from the menu; behind its "are you sure".
        assertEquals("DELETE-ACTION", mine.deleteAction)
        // Someone else's: no Delete.
        assertNull(p.comments[1].deleteAction)
    }

    @Test fun likeCountFollowsTheReadersLike() {
        val c = SongComments.Comment("x", "a", null, "t", null, likes = "4", replies = null, byCreator = false, mine = false, likesWithMine = "5")
        assertEquals("4", c.likesShown(false))
        assertEquals("5", c.likesShown(true))
        assertEquals(null, c.copy(likes = null, likesWithMine = null).likesShown(false))
    }

    @Test fun findsTheMusicVideoPairedWithASong() {
        val root = Json.parseToJsonElement("""
            {"contents":{"x":[{"playlistPanelVideoWrapperRenderer":{
              "primaryRenderer":{"playlistPanelVideoRenderer":{"videoId":"SONG1"}},
              "counterpart":[{"counterpartRenderer":{"playlistPanelVideoRenderer":{"videoId":"VIDEO1"}}}]}}]}}
        """) as JsonObject
        assertEquals("VIDEO1", SongComments.counterpart(root, "SONG1"))
        assertNull(SongComments.counterpart(root, "OTHER"))
    }
}
