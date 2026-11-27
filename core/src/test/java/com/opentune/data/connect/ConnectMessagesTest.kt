package com.opentune.data.connect

import com.opentune.data.connect.ConnectMsg.Ack
import com.opentune.data.connect.ConnectMsg.Command
import com.opentune.data.connect.ConnectMsg.Leave
import com.opentune.data.connect.ConnectMsg.Ping
import com.opentune.data.connect.ConnectMsg.Pong
import com.opentune.data.connect.ConnectMsg.Presence
import com.opentune.data.connect.ConnectMsg.State
import com.opentune.data.connect.ConnectMsg.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectMessagesTest {
    private val song = CTrack("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "https://i.ytimg.com/x.jpg", "3:33", "Whenever")

    @Test fun everyKindSurvivesTheWire() {
        val all = listOf(
            Presence("a", 1, 2, "POCO X7 Pro", "phone", active = true),
            State("a", 2, 3, song, playing = true, pos = 1234, queue = listOf(song), volume = 0.5f, shuffle = true, repeat = "all"),
            Command("b", 3, 4, to = "a", id = "c1", action = "seek", pos = 5000),
            Command("b", 4, 5, to = "a", id = "c2", action = "addToQueue", tracks = listOf(song)),
            Ack("a", 5, 6, to = "b", id = "c1", ok = false, reason = "unplayable"),
            Transfer("a", 6, 7, to = "b", id = "t1", queue = listOf(song, song), index = 1, pos = 9000, playing = false),
            Leave("a", 7, 8),
            Ping("b", 8, 9, to = "a", c = 100),
            Pong("a", 9, 10, to = "b", c = 100, h = 140),
        )
        for (m in all) assertEquals(m, ConnectMsg.decode(ConnectMsg.encode(m)))
    }

    /** The web player writes these by hand; this pins the format both sides use. */
    @Test fun theWireFormatIsPinned() {
        assertEquals(
            Command("a", 1, 2, to = "b", id = "c", action = "seek", pos = 5000),
            ConnectMsg.decode("""{"type":"command","from":"a","seq":1,"at":2,"to":"b","id":"c","action":"seek","pos":5000}"""),
        )
        assertEquals(
            State("a", 1, 2, CTrack("dQw4w9WgXcQ", "T"), playing = true, pos = 7),
            ConnectMsg.decode("""{"type":"state","from":"a","seq":1,"at":2,"track":{"id":"dQw4w9WgXcQ","title":"T"},"playing":true,"pos":7,"extra":1}"""),
        )
        assertEquals(
            """{"type":"ack","from":"a","seq":1,"at":2,"to":"b","id":"c"}""",
            ConnectMsg.encode(Ack("a", 1, 2, to = "b", id = "c")),
        )
    }

    @Test fun strangersAreDropped() {
        assertNull(ConnectMsg.decode("""{"type":"dance","from":"a","seq":1,"at":2}"""))
        assertNull(ConnectMsg.decode("not json"))
    }

    @Test fun aSongWithoutALengthGetsThePlayersOne() {
        val bare = CTrack("dQw4w9WgXcQ", "Song")
        assertEquals("3:45", bare.withLength(225_400).length)
        assertEquals(225_000L, bare.withLength(225_400).lengthMs)
        assertEquals("1:02:03", bare.withLength(3_723_000).length)
        // Not known yet: left as it is.
        assertNull(bare.withLength(0).length)
        // One the list said stays.
        assertEquals("4:00", bare.copy(length = "4:00").withLength(225_400).length)
    }

    @Test fun aLongQueueIsCutToFit() {
        val group = ConnectGroup.generate()
        val long = CTrack("dQw4w9WgXcQ", "x".repeat(300), "y".repeat(300))
        val state = State("a", 1, 2, song, playing = true, queue = List(1000) { long })
        val sealed = ConnectMsg.fit(state, group)
        assertTrue(sealed.length <= ConnectMsg.MAX_SEALED)
        val back = ConnectMsg.decode(group.open(sealed)!!) as State
        assertTrue(back.queue.size in 1..ConnectMsg.MAX_QUEUE)
        assertEquals(song, back.track)

        val transfer = Transfer("a", 1, 2, to = "b", id = "t", queue = List(500) { long } + song, index = 500)
        val moved = ConnectMsg.decode(group.open(ConnectMsg.fit(transfer, group))!!) as Transfer
        assertEquals(song, moved.queue[moved.index])
    }
}
