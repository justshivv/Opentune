package com.opentune.desktop

import com.opentune.data.model.Song
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Last.fm's request signing and listen rule, and Discord's local socket: the handshake and the card. */
class ConnectedTest {
    @Test
    fun lastFmSignsTheSortedParametersWithTheSecret() {
        // The same values the web player's test checks, from Python's hashlib.
        assertEquals(
            "d5e55eb18d690865ef3a94b29d378d0a",
            LastFmDesktop.signature(
                mapOf("method" to "track.scrobble", "api_key" to "abc", "sk" to "s3ss", "artist[0]" to "Daft Punk", "track[0]" to "Digital Love", "timestamp[0]" to "1700000000"),
                "sekret",
            ),
        )
        assertEquals("b5497fe66732297478ddae8099fa4b07", LastFmDesktop.signature(mapOf("method" to "track.updateNowPlaying", "api_key" to "k", "track" to "Café del Mar", "artist" to "Energy 52"), "s"))
    }

    @Test
    fun aListenIsHalfTheSongOrFourMinutes() {
        assertFalse(LastFmDesktop.isListen(89.0, 180.0))
        assertTrue(LastFmDesktop.isListen(90.0, 180.0))
        assertTrue(LastFmDesktop.isListen(240.0, 900.0))
        assertFalse(LastFmDesktop.isListen(25.0, 25.0))
        assertFalse(LastFmDesktop.isListen(120.0, 0.0))
        assertEquals("Daft Punk", LastFmDesktop.primaryArtist("Daft Punk, Pharrell Williams & Nile Rodgers"))
    }

    @Test
    fun theDiscordCardNamesTheSongAndLinksIt() {
        val song = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", albumName = "Whenever You Need Somebody")
        val card = DiscordPresence.activity(song, positionMs = 30_000, durationMs = 213_000, withType = true)
        // "Listening to <song>" in the status line, not the application's name.
        assertEquals(2, card.getInt("status_display_type"))
        assertEquals(2, card.getInt("type"))
        assertEquals("Never Gonna Give You Up", card.getString("details"))
        assertEquals("Rick Astley", card.getString("state"))
        val times = card.getJSONObject("timestamps")
        assertEquals(213_000, times.getLong("end") - times.getLong("start"))
        assertEquals("https://lh3.googleusercontent.com/abc=w512-h512", card.getJSONObject("assets").getString("large_image"))
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", card.getJSONArray("buttons").getJSONObject(0).getString("url"))
        // Discord wants at least two characters a line.
        assertEquals(2, DiscordPresence.activity(song.copy(title = "X"), 0, 0, withType = false).getString("details").length)
    }

    @Test
    fun itShakesHandsWithTheDiscordAppOnItsSocket() = runBlocking {
        val dir = Files.createTempDirectory("discord-ipc").toFile()
        DiscordPresence.useFile(java.io.File(dir, "discord.json"))
        DiscordPresence.searchRoots = listOf(dir.absolutePath)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(java.io.File(dir, "discord-ipc-0").toPath())) }
        var handshake: JSONObject? = null
        val discord = Thread {
            server.accept().use { client ->
                handshake = read(client).second
                write(client, 1, JSONObject().put("cmd", "DISPATCH").put("evt", "READY"))
                Thread.sleep(3_000)
            }
        }.apply { isDaemon = true; start() }
        DiscordPresence.setEnabled(true)
        // Not an application id: refused, and nothing kept.
        assertEquals(false, DiscordPresence.setOwnAppId("BitChord"))
        if (!DiscordPresence.buildHasApp) {
            DiscordPresence.setOwnAppId("")
            assertEquals(null, DiscordPresence.appId())
        }
        assertEquals(true, DiscordPresence.setOwnAppId(" 123456789012345678 "))
        DiscordPresence.start()
        withTimeout(10_000) { DiscordPresence.status.first { it == DiscordPresence.Status.Connected } }
        // The build's own application when it has one, else the one pasted in Settings.
        assertEquals(DiscordPresence.appId(), handshake!!.getString("client_id"))
        if (!DiscordPresence.buildHasApp) assertEquals("123456789012345678", handshake!!.getString("client_id"))
        assertEquals(1, handshake!!.getInt("v"))
        discord.interrupt()
        server.close()
    }

    private fun read(channel: SocketChannel): Pair<Int, JSONObject> {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (header.hasRemaining()) channel.read(header)
        header.flip()
        val op = header.int
        val body = ByteBuffer.allocate(header.int)
        while (body.hasRemaining()) channel.read(body)
        return op to JSONObject(String(body.array(), Charsets.UTF_8))
    }

    private fun write(channel: SocketChannel, op: Int, payload: JSONObject) {
        val body = payload.toString().toByteArray()
        channel.write(ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).put(body).flip())
    }
}
