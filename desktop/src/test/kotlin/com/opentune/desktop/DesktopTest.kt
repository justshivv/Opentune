package com.opentune.desktop

import com.opentune.data.model.Song
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.asSkiaBitmap

class DesktopTest {
    @Test fun rangesAreClippedToTheStream() {
        assertEquals(0L to 999L, Ranges.parse(null, 1000))
        assertEquals(100L to 999L, Ranges.parse("bytes=100-", 1000))
        assertEquals(100L to 199L, Ranges.parse("bytes=100-199", 1000))
        assertEquals(900L to 999L, Ranges.parse("bytes=-100", 1000))
        assertEquals(500L to 999L, Ranges.parse("bytes=500-5000", 1000))
        assertNull(Ranges.parse("bytes=1000-", 1000))
    }

    @Test fun theArtistIsFoundInACardsGreyLine() {
        assertEquals("River Choir", Pages.artistIn("Song • River Choir • 2.1M plays"))
        assertEquals("River Choir", Pages.artistIn("River Choir • 1.2M views"))
        assertEquals("River Choir", Pages.artistIn("River Choir"))
    }

    @Test fun shuffleKeepsTheSongPlayingFirstAndGoesBack() {
        val songs = (0 until 12).map { Song("song${it.toString().padStart(7, '0')}", "Song $it", "Artist", null) }
        Player.cue(songs, 4)
        Player.toggleShuffle()
        assertTrue(Player.shuffled.value)
        assertEquals(0, Player.index.value)
        assertEquals(songs[4], Player.queue.value.first())
        assertEquals(songs.toSet(), Player.queue.value.toSet())
        Player.toggleShuffle()
        assertFalse(Player.shuffled.value)
        assertEquals(songs, Player.queue.value)
        assertEquals(4, Player.index.value)
        assertEquals(Player.Repeat.OFF, Player.repeat.value)
        Player.cycleRepeat()
        assertEquals(Player.Repeat.ALL, Player.repeat.value)
        Player.cycleRepeat()
        assertEquals(Player.Repeat.ONE, Player.repeat.value)
        Player.cycleRepeat()
        assertEquals(Player.Repeat.OFF, Player.repeat.value)
    }

    /** The queue as the song menu and Up next change it: the song playing always keeps playing. */
    @Test fun theQueueIsEditedAroundTheSongPlaying() {
        fun song(n: Int) = Song("q${n.toString().padStart(10, '0')}", "Song $n", "Artist", null)
        val songs = (0 until 6).map(::song)
        fun ids() = Player.queue.value.map { it.title.removePrefix("Song ") }
        Player.cue(songs, 2)
        Player.playNext(listOf(song(9)))
        assertEquals(listOf("0", "1", "2", "9", "3", "4", "5"), ids())
        Player.addToQueue(listOf(song(8)))
        assertEquals("8", ids().last())
        // Moving the song playing takes its place with it; moving another past it shifts it.
        Player.move(2, 0)
        assertEquals(0, Player.index.value)
        assertEquals("2", ids()[Player.index.value])
        Player.move(4, 0)
        assertEquals(1, Player.index.value)
        assertEquals("2", ids()[Player.index.value])
        // Removing a song before it keeps it current; the song playing can't be removed.
        Player.removeAt(0)
        assertEquals(0, Player.index.value)
        assertEquals("2", ids()[Player.index.value])
        Player.removeAt(Player.index.value)
        assertEquals("2", ids()[Player.index.value])
        Player.clearUpcoming()
        assertEquals(listOf("2"), ids())
    }

    /** A Listen together room sees the desktop's queue as the phone's: the song playing and what comes after. */
    @Test fun aRoomSeesTheQueue() {
        fun song(n: Int) = Song("r${n.toString().padStart(10, '0')}", "Song $n", "Artist", null)
        Player.cue((0 until 4).map(::song), 1)
        DesktopRoomPlayer.queue
        Thread.sleep(300)
        assertEquals("Song 1", DesktopRoomPlayer.currentSong.value?.title)
        assertEquals(listOf(2, 3), DesktopRoomPlayer.upNext.value)
        // The host's queue replaces what's after the song playing.
        DesktopRoomPlayer.replaceUpcoming(listOf(song(7), song(8)))
        assertEquals(listOf("Song 0", "Song 1", "Song 7", "Song 8"), Player.queue.value.map { it.title })
        // Not in a room, a control isn't taken away from the player.
        assertFalse(com.opentune.data.together.Together.intercept(com.opentune.data.together.Together.Control.Next))
    }

    @Test fun theSoftCoverBlendsAHardEdgeIntoAFade() {
        // Half black, half white, as a cover split hard down the middle.
        val image = androidx.compose.ui.graphics.ImageBitmap(32, 32)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        canvas.drawRect(0f, 0f, 16f, 32f, androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color.Black })
        canvas.drawRect(16f, 0f, 32f, 32f, androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color.White })
        soften(image)
        val bitmap = image.asSkiaBitmap()
        val red = { x: Int -> ((bitmap.getColor(x, 16) shr 16) and 0xFF) / 255f }
        // Grey across the middle, still dark and light at the far edges, rising steadily between.
        assertTrue("middle ${red(16)}", red(16) in 0.35f..0.65f)
        assertTrue(red(0) < 0.2f && red(31) > 0.8f)
        assertTrue((1 until 32).all { red(it) >= red(it - 1) - 0.01f })
    }

    @Test fun timesReadAsMinutesAndSeconds() {
        assertEquals("0:00", Time.format(0))
        assertEquals("3:21", Time.format(201_000))
        assertEquals("1:02:03", Time.format(3_723_000))
    }

    @Test fun likesAndRecentPlaysAreKeptBetweenRuns() {
        val file = Files.createTempDirectory("opentune").resolve("desktop.json").toFile()
        Library.useFile(file)
        val song = Song("dQw4w9WgXcQ", "Paper Boats", "River Choir", null)
        Library.setLiked(song, true)
        Library.played(song)
        Library.played(Song("aaaaaaaaaaa", "Lanterns", "River Choir", null))
        assertTrue(Library.isLiked(song.videoId))
        Library.useFile(file)
        assertTrue(Library.isLiked(song.videoId))
        assertEquals(listOf("aaaaaaaaaaa", "dQw4w9WgXcQ"), Library.recent.value.map { it.videoId })
        Library.setLiked(song, false)
        assertFalse(Library.isLiked(song.videoId))
    }

    @Test fun playlistsKeepTheirSongsInOrderBetweenRuns() {
        val file = Files.createTempDirectory("opentune").resolve("desktop.json").toFile()
        Library.useFile(file)
        val a = Song("aaaaaaaaaaa", "Lanterns", "River Choir", "https://example.com/a.jpg")
        val b = Song("bbbbbbbbbbb", "Paper Boats", "River Choir", "https://example.com/b.jpg")
        val id = Library.createPlaylist("  Evening  ")
        Library.addToPlaylist(id, a)
        Library.addToPlaylist(id, b)
        Library.addToPlaylist(id, a) // already there
        Library.useFile(file)
        val p = Library.playlists.value.single()
        assertEquals("Evening", p.name)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), p.songs.map { it.id })
        assertEquals(listOf("https://example.com/a.jpg", "https://example.com/b.jpg"), p.covers)
        Library.removeFromPlaylist(id, "aaaaaaaaaaa")
        Library.renamePlaylist(id, "")
        assertEquals("Evening", Library.playlists.value.single().name)
        assertEquals(listOf("bbbbbbbbbbb"), Library.playlists.value.single().songs.map { it.id })
        Library.deletePlaylist(id)
        assertTrue(Library.playlists.value.isEmpty())
    }

    /** The stand-in for Android's preferences, which the shared settings and Subsonic code save through. */
    @Test fun preferencesAreWrittenAndReadBack() {
        val root = Files.createTempDirectory("opentune").toFile()
        val prefs = android.content.Context(root).getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("state", "{\"a\":1}").putInt("n", 3).putBoolean("on", true).apply()
        val again = android.content.Context(root).getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        assertEquals("{\"a\":1}", again.getString("state", null))
        assertEquals(3, again.getInt("n", 0))
        assertTrue(again.getBoolean("on", false))
        assertTrue(again.contains("n"))
        again.edit().remove("n").commit()
        assertFalse(android.content.Context(root).getSharedPreferences("settings", 0).contains("n"))
    }

    @Test fun settingsSurviveARestart() {
        val root = Files.createTempDirectory("opentune").toFile()
        com.opentune.data.settings.AppSettings.init(android.content.Context(root))
        com.opentune.data.settings.AppSettings.addRecentSearch("lofi")
        com.opentune.data.settings.AppSettings.setLyricsOffset("dQw4w9WgXcQ", 250)
        com.opentune.data.settings.AppSettings.init(android.content.Context(root))
        assertEquals(listOf("lofi"), com.opentune.data.settings.AppSettings.recentSearches.value)
        assertEquals(250L, com.opentune.data.settings.AppSettings.lyrics.value.offsets["dQw4w9WgXcQ"])
    }

    @Test fun ffmpegFiltersMatchTheSoundSettings() {
        val flat = com.opentune.data.settings.EqualizerSettings()
        // Nothing to change: no filters, the song goes straight through.
        assertNull(FfmpegEngine.filterChain(1f, flat))
        assertNull(FfmpegEngine.filterChain(1f, flat.copy(enabled = true)))
        assertEquals("atempo=1.250", FfmpegEngine.filterChain(1.25f, flat))
        // A boost is taken back off the input first, as the phone does, so it can't clip.
        val bass = FfmpegEngine.filterChain(1f, flat.copy(enabled = true, bassDb = 6f))!!
        assertEquals("volume=-6.00dB,lowshelf=f=120:t=q:w=0.707:g=6.00", bass)
        // The preamp alone, and a cut that needs no headroom.
        assertEquals("volume=-3.00dB", FfmpegEngine.filterChain(1f, flat.copy(enabled = true, preampDb = -3f)))
        val cut = FfmpegEngine.filterChain(1f, flat.copy(enabled = true, bands = List(15) { if (it == 8) -4f else 0f }))!!
        assertEquals("equalizer=f=1000:t=o:w=0.667:g=-4.00", cut)
        // A headphone curve's own filters, its preamp, and the room for its biggest lift.
        val headphone = com.opentune.data.settings.HeadphoneEq(
            "HD 600", "AutoEq", -2f,
            listOf(
                com.opentune.data.settings.ParametricFilter(com.opentune.data.settings.FilterType.LOW_SHELF, 105f, 4f, 0.7f),
                com.opentune.data.settings.ParametricFilter(com.opentune.data.settings.FilterType.PEAK, 3200f, -3.5f, 2f),
            ),
        )
        assertEquals(
            "volume=-6.00dB,lowshelf=f=105:t=q:w=0.70:g=4.00,equalizer=f=3200:t=q:w=2.00:g=-3.50",
            FfmpegEngine.filterChain(1f, flat.copy(enabled = true, headphone = headphone)),
        )
        val left = FfmpegEngine.filterChain(1f, flat.copy(enabled = true, balance = -0.5f))!!
        assertEquals("pan=stereo|c0=1.000*c0|c1=0.500*c1", left)
    }

    @Test fun theOldJsonLibraryIsReadInOnce() {
        val folder = Files.createTempDirectory("opentune").toFile()
        val file = java.io.File(folder, "desktop.json")
        file.writeText(
            """{"liked":[{"id":"dQw4w9WgXcQ","title":"Paper Boats","artist":"River Choir"}],""" +
                """"recent":[{"id":"aaaaaaaaaaa","title":"Lanterns","artist":"River Choir"},{"id":"dQw4w9WgXcQ","title":"Paper Boats","artist":"River Choir"}],""" +
                """"volume":0.4,"playlists":[{"id":"pl-1","name":"Night","songs":[{"id":"aaaaaaaaaaa","title":"Lanterns","artist":"River Choir"}]}]}""",
        )
        Library.useFile(file)
        assertTrue(Library.isLiked("dQw4w9WgXcQ"))
        assertEquals(listOf("aaaaaaaaaaa", "dQw4w9WgXcQ"), Library.recent.value.map { it.videoId })
        assertEquals(0.4, Library.volume(), 0.0001)
        assertEquals(listOf("Night"), Library.playlists.value.map { it.name })
        assertEquals(listOf("Lanterns"), Library.playlists.value.single().songs.map { it.title })
        // Kept aside, not read again.
        assertFalse(file.exists())
        assertTrue(java.io.File(folder, "desktop.json.imported").exists())
        Library.useFile(file)
        assertEquals(1, Library.liked.value.size)
    }
}
