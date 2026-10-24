package com.opentune.data.covers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMotionArtTest {
    private fun album(video: String) = Json.parseToJsonElement(
        """{"data":[{"id":"1499385848","type":"albums","attributes":{"name":"After Hours","editorialVideo":$video}}]}""",
    ).jsonObject

    // The shape Apple answered for After Hours on 2026-10-24, with shortened paths.
    private val afterHours = """{
        "motionDetailSquare":{"video":"https://mvod.itunes.apple.com/itunes-assets/HLSMusic126/square.m3u8"},
        "motionDetailTall":{"video":"https://mvod.itunes.apple.com/itunes-assets/HLSMusic126/tall.m3u8"},
        "motionSquareVideo1x1":{"video":"https://mvod.itunes.apple.com/itunes-assets/HLSMusic126/square1x1.m3u8"},
        "motionTallVideo3x4":{"video":"https://mvod.itunes.apple.com/itunes-assets/HLSMusic126/tall.m3u8"}}"""

    @Test
    fun theSquareClipComesFirstWithTheTallOneAsTheRetry() {
        val motion = AppleMotionArt.motionOf(album(afterHours))!!
        assertEquals("https://mvod.itunes.apple.com/itunes-assets/HLSMusic126/square.m3u8", motion.url)
        assertEquals("https://mvod.itunes.apple.com/itunes-assets/HLSMusic126/tall.m3u8", motion.fallbackUrl)
    }

    @Test
    fun aTallClipAloneIsStillTaken() {
        val motion = AppleMotionArt.motionOf(album("""{"motionTallVideo3x4":{"video":"https://mvod.itunes.apple.com/t.m3u8"}}"""))!!
        assertEquals("https://mvod.itunes.apple.com/t.m3u8", motion.url)
        assertNull(motion.fallbackUrl)
    }

    @Test
    fun noClipOrAClipFromElsewhereIsNone() {
        assertNull(AppleMotionArt.motionOf(Json.parseToJsonElement("""{"data":[{"attributes":{"name":"x"}}]}""").jsonObject))
        assertNull(AppleMotionArt.motionOf(album("""{"motionDetailSquare":{"video":"http://mvod.itunes.apple.com/s.m3u8"}}""")))
        assertNull(AppleMotionArt.motionOf(album("""{"motionDetailSquare":{"video":"https://apple.com.evil.example/s.m3u8"}}""")))
        assertNull(AppleMotionArt.motionOf(Json.parseToJsonElement("""{"errors":[]}""").jsonObject))
    }
}
