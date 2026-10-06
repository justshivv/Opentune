package com.opentune.data.together

import com.opentune.data.model.Song
import com.opentune.data.radio.Radio
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RoomTest {
    private val song = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "https://i.ytimg.com/x.jpg", "3:33")

    @Test fun messagesRoundTrip() {
        val state = Msg.State(
            seq = 7, host = "Asha", track = Track.of(song), playing = true, pos = 61_000, at = 1_700_000_000_000,
            next = listOfNotNull(Track.of(song.copy(videoId = "abcdefghijk", title = "Next"))),
            members = listOf(Member("aa", "Asha", host = true), Member("bb", "Ravi")), open = true,
        )
        listOf(state, Msg.Hello("Ravi"), Msg.Here("Ravi"), Msg.Bye, Msg.End, Msg.Ping(5), Msg.Pong(5, 9, "bb"), Msg.Ask("seek", 30_000), Msg.Chat("hi"))
            .forEach { assertEquals(it, Msg.decode(Msg.encode(it))) }
        assertTrue(Msg.encode(Msg.Bye).contains("\"t\":\"bye\""))
        assertNull(Msg.decode("""{"t":"something-new"}"""))
        assertEquals(Msg.Chat("hi"), Msg.decode("""{"t":"chat","text":"hi","extra":1}"""))
    }

    @Test fun onlySongsOtherPhonesCanPlayAreShared() {
        assertEquals("dQw4w9WgXcQ", Track.of(song)?.id)
        assertNull(Track.of(song.copy(videoId = "local:42")))
        val station = Radio.addCustom("My FM 93.5", "https://stream.example.com/live")
        val t = Track.of(Song(station.id, station.name, "Live radio", null))
        assertEquals("https://stream.example.com/live", t?.station?.streamUrl)
        Radio.setFavourite(station, false)
    }

    @Test fun guestsAimForWhereTheHostIsNow() {
        val playing = Msg.State(seq = 1, host = "h", playing = true, pos = 10_000, at = 1_000_000)
        // 2.5 s after the host's sample on its clock; our clock runs 500 ms behind it.
        assertEquals(12_500, targetPosition(playing, localNow = 1_002_000, offsetMs = 500))
        assertEquals(10_000, targetPosition(playing.copy(playing = false), 1_002_000, 500))
        assertEquals(0, targetPosition(playing.copy(pos = 0, at = 2_000_000), 1_000_000, 0))
    }

    @Test fun clockOffsetAssumesTheReplyCameHalfwayThrough() {
        // Sent at 1000, answered at host time 5150, back at 1300: host is 4000 ahead.
        assertEquals(4_000, clockOffset(1_000, 5_150, 1_300))
        assertEquals(-200, clockOffset(10_000, 9_900, 10_200))
    }

    /** Over real relays; run with OPENTUNE_LIVE_RELAYS=1. */
    @Test fun aSealedMessageCrossesLiveRelays() = runBlocking {
        assumeTrue(System.getenv("OPENTUNE_LIVE_RELAYS") == "1")
        val code = RoomCode.generate()
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(Together.KIND))))
            put("#t", JsonArray(listOf(JsonPrimitive(code.tag))))
            put("since", System.currentTimeMillis() / 1000 - 10)
        }
        val a = RelayPool(Together.RELAYS, filter).also { it.start() }
        val b = RelayPool(Together.RELAYS, filter).also { it.start() }
        try {
            withTimeout(15_000) { a.connected.first { it >= 2 }; b.connected.first { it >= 2 } }
            println("relays connected: a=${a.connected.value} b=${b.connected.value}")
            val key = Schnorr.newPrivateKey()
            val sent = NostrEvent.signed(key, Together.KIND, listOf(listOf("t", code.tag)), code.seal(Msg.encode(Msg.Chat("hello from a"))))
            val t0 = System.currentTimeMillis()
            assertTrue(a.publish(sent))
            val got = withTimeout(10_000) { b.events.first { it.id == sent.id } }
            println("delivered in ${System.currentTimeMillis() - t0} ms")
            assertEquals(Msg.Chat("hello from a"), code.open(got.content)?.let(Msg::decode))
        } finally {
            a.close(); b.close()
        }
    }
}
