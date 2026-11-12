package com.opentune.data.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DiscordRpcTest {
    private fun now(title: String = "Labon Ko", positionMs: Long = 60_000, rate: Float = 1f) = DiscordRpc.NowPlaying(
        videoId = "dQw4w9WgXcQ", title = title, artist = "Pritam", album = "Bhool Bhulaiyaa",
        artworkUrl = "https://lh3.googleusercontent.com/cover", durationMs = 240_000, positionMs = positionMs, rate = rate, playing = true,
    )

    @Test fun listeningCardWithTheSongAndItsPlace() {
        val before = System.currentTimeMillis()
        val p = DiscordRpc.presence("token", now())
        val a = p.getJSONArray("activities").getJSONObject(0)
        // A Listening activity: Discord heads the card "Listening to OpenTune" and draws the progress bar.
        assertEquals(2, a.getInt("type"))
        assertEquals("OpenTune", a.getString("name"))
        assertEquals(0, a.getInt("status_display_type"))
        assertEquals("Labon Ko", a.getString("details"))
        assertEquals("Pritam", a.getString("state"))
        val t = a.getJSONObject("timestamps")
        // Started a minute ago, ends three minutes from now.
        assertTrue(t.getLong("start") in before - 61_000..before - 58_000)
        assertEquals(240_000.0, (t.getLong("end") - t.getLong("start")).toDouble(), 50.0)
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", a.getJSONObject("metadata").getJSONArray("button_urls").getString(0))
    }

    @Test fun keepsWithinDiscordsLimits() {
        val a = DiscordRpc.presence("token", now(title = "x".repeat(300))).getJSONArray("activities").getJSONObject(0)
        assertTrue(a.getString("details").length <= 128)
        val buttons = a.getJSONArray("buttons")
        for (i in 0 until buttons.length()) assertTrue(buttons.getString(i).length in 2..32)
    }

    @Test fun aFasterSongEndsSooner() {
        val a = DiscordRpc.presence("token", now(positionMs = 0, rate = 2f)).getJSONArray("activities").getJSONObject(0)
        val t = a.getJSONObject("timestamps")
        assertEquals(120_000.0, (t.getLong("end") - t.getLong("start")).toDouble(), 50.0)
        assertTrue(a.getString("details").endsWith("[2.00x]"))
    }
}
