package com.opentune.data.listenbrainz

import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzTest {
    private val youTube = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null, albumName = "Whenever You Need Somebody")
    private val local = Song("local:12", "Demo", "Me", null)

    private fun first(body: JsonObject) = body["payload"]!!.jsonArray.first().jsonObject

    @Test
    fun aListenCarriesItsTimeAndWhereItWasPlayed() {
        val body = ListenBrainz.payload("single", listOf(ListenBrainz.listenOf(youTube, 1_700_000_000, 213_000)))
        assertEquals("single", body["listen_type"]!!.jsonPrimitive.content)
        val listen = first(body)
        assertEquals(1_700_000_000, listen["listened_at"]!!.jsonPrimitive.content.toLong())
        val meta = listen["track_metadata"]!!.jsonObject
        assertEquals("Rick Astley", meta["artist_name"]!!.jsonPrimitive.content)
        assertEquals("Never Gonna Give You Up", meta["track_name"]!!.jsonPrimitive.content)
        assertEquals("Whenever You Need Somebody", meta["release_name"]!!.jsonPrimitive.content)
        val info = meta["additional_info"]!!.jsonObject
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", info["origin_url"]!!.jsonPrimitive.content)
        assertEquals(213_000, info["duration_ms"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun playingNowHasNoTime() {
        val listen = first(ListenBrainz.payload("playing_now", listOf(ListenBrainz.listenOf(youTube, 0, 0))))
        assertFalse(listen.containsKey("listened_at"))
        assertNull(listen["track_metadata"]!!.jsonObject["additional_info"]!!.jsonObject["duration_ms"])
    }

    @Test
    fun aLocalFileHasNoYouTubeLink() {
        val info = first(ListenBrainz.payload("single", listOf(ListenBrainz.listenOf(local, 1, 0))))["track_metadata"]!!
            .jsonObject["additional_info"]!!.jsonObject
        assertFalse(info.containsKey("origin_url"))
        assertTrue(info.containsKey("media_player"))
    }

    @Test
    fun picksAreNamedFromTheMetadataEndpoint() {
        // Shaped like the example in the ListenBrainz metadata API docs.
        val entry = Json.parseToJsonElement(
            """{"recording":{"name":"Glory Box"},"artist":{"name":"Portishead","artist_credit_id":65},
               "release":{"name":"Dummy","caa_release_mbid":"76df3287-6cda-33eb-8e9a-044b5e15ffdd"}}""",
        ).jsonObject
        assertEquals(ListenBrainz.Pick("Glory Box", "Portishead", "Dummy", "76df3287-6cda-33eb-8e9a-044b5e15ffdd"), ListenBrainz.pick(entry))
        assertNull(ListenBrainz.pick(Json.parseToJsonElement("""{"artist":{"name":"X"}}""").jsonObject))
    }
}
