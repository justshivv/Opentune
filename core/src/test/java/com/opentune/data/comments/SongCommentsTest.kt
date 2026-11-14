package com.opentune.data.comments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
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
