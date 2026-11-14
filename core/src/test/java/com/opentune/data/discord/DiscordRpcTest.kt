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

    @Test fun theCardFollowsItsOptions() {
        val options = DiscordRpc.Options(
            activityType = DiscordRpc.ActivityType.PLAYING,
            status = DiscordRpc.OnlineStatus.DND,
            lead = DiscordRpc.Lead.SONG,
            activityName = "My music",
            button1Text = "{song_name} by {artist_name}",
            button2Text = "https://example.com/me",
        )
        val p = DiscordRpc.presence("token", now(), options)
        val a = p.getJSONArray("activities").getJSONObject(0)
        assertEquals(0, a.getInt("type"))
        assertEquals("My music", a.getString("name"))
        assertEquals(2, a.getInt("status_display_type"))
        assertEquals("dnd", p.getString("status"))
        val buttons = a.getJSONArray("buttons")
        assertEquals("Labon Ko by Pritam", buttons.getString(0))
        // A web address as the second button's text: the usual label, going to that address.
        assertEquals("Get OpenTune", buttons.getString(1))
        assertEquals("https://example.com/me", a.getJSONObject("metadata").getJSONArray("button_urls").getString(1))
    }

    @Test fun buttonsCanBeHidden() {
        val a = DiscordRpc.presence("token", now(), DiscordRpc.Options(button1Visible = false, button2Visible = false))
            .getJSONArray("activities").getJSONObject(0)
        assertTrue(!a.has("buttons"))
        // The song's own link still opens from the title.
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", a.getString("details_url"))
    }

    @Test fun optionsSurviveBeingSaved() {
        val options = DiscordRpc.Options(activityType = DiscordRpc.ActivityType.WATCHING, lead = DiscordRpc.Lead.ARTIST, button2Visible = false, activityName = "x")
        val saved = org.json.JSONObject().put("activityType", "WATCHING").put("lead", "ARTIST").put("button2Visible", false).put("activityName", "x")
        assertEquals(options, DiscordRpc.readOptions(saved))
        // Anything unknown or missing falls back to the usual card.
        assertEquals(DiscordRpc.Options(), DiscordRpc.readOptions(org.json.JSONObject().put("activityType", "DANCING")))
    }
}
