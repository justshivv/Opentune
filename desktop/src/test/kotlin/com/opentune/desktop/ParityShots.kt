package com.opentune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import com.opentune.data.together.Track
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Pictures of what the desktop gained to match the phone: songs picked in a list with the
 * selection bar over it, the queue's own bar, and Listen together not in a room, as the host
 * (with people knocking and a chat) and as a guest. Off unless asked for:
 * ./gradlew :desktop:test --tests '*ParityShots*' -Dopentune.shots=/some/folder
 */
class ParityShots {
    private val folder = System.getProperty("opentune.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun shoot(name: String, width: Int, height: Int, waitMs: Long = 2_500, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) { content() }
        val start = System.nanoTime()
        var at = 0L
        while (at < waitMs) {
            scene.render(System.nanoTime() - start)
            Thread.sleep(50)
            at = (System.nanoTime() - start) / 1_000_000
        }
        val image = scene.render(System.nanoTime() - start)
        folder!!.mkdirs()
        File(folder, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        DesktopTheme {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalPageTop provides TOP_BAR_HEIGHT) { content() }
            }
        }
    }

    private val ids = listOf("4NRXx6U8ABQ", "XXYlFuWEuKI", "JGwWNGJdvx8", "lp-EO5I60KA", "OPf0YbXqDm0", "fHI8X4OXluQ", "kJQP7kiw5Fk", "60ItHLz5WEA")
    private val names = listOf(
        "Blinding Lights" to "The Weeknd", "Save Your Tears" to "The Weeknd", "Shape of You" to "Ed Sheeran", "Perfect" to "Ed Sheeran",
        "Uptown Funk" to "Mark Ronson, Bruno Mars", "Starboy" to "The Weeknd, Daft Punk", "Despacito" to "Luis Fonsi", "Faded" to "Alan Walker",
    )

    private fun songs(n: Int) = (0 until n).map { i ->
        val k = i % ids.size
        Song(
            if (i < ids.size) ids[k] else ids[k].dropLast(1) + ('a' + i % 26), names[k].first + if (i >= ids.size) " (Live)" else "", names[k].second,
            "https://i.ytimg.com/vi/${ids[k]}/hqdefault.jpg", "%d:%02d".format(3 + i % 2, (17 * i + 9) % 60),
        )
    }

    @Before fun setUp() {
        assumeTrue(folder != null)
        AppSettings.init(appContext)
        AppSettings.updateTheme { it.copy(mode = com.opentune.data.settings.ThemeMode.DARK) }
    }

    @Test fun selection() {
        Library.useFile(java.nio.file.Files.createTempDirectory("parityshots").resolve("desktop.json").toFile())
        val list = songs(12)
        val id = Library.createPlaylist("Late night drive")
        list.forEach { Library.addToPlaylist(id, it) }
        Player.cue(list, 0)
        val shownSongs = Library.playlists.value.first { it.id == id }.songs.map { it.toSong() }
        PageSelection.clear()
        listOf(1, 2, 3, 6).forEach { PageSelection.picked[shownSongs[it].videoId] = shownSongs[it] }
        for ((name, width) in listOf("parity-selection" to 1500, "parity-selection-narrow" to 820)) {
            shoot(name, width, 1000, 4_000) {
                Page {
                    Box(Modifier.fillMaxSize()) {
                        PlaylistPage(id) {}
                        SelectionBar(PageSelection, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        PageSelection.clear()
    }

    /** A song's comments, read from YouTube: it needs the network. */
    @Test fun comments() {
        shoot("parity-comments", 1100, 900, 12_000) { Page { CommentsDialog(songs(1).first()) {} } }
    }

    private val code = RoomCode.parse("ABCDEFGHJKLM") ?: RoomCode.generate()
    private val track = Track("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg", "3:20")
    private val people = listOf(Member("me", "Asha", host = true), Member("r", "Ravi"), Member("m", "Meera"))

    private fun chat(t0: Long) = listOf(
        Together.ChatLine("", "Room open. Share the code so friends can join.", system = true, at = t0),
        Together.ChatLine("", "Ravi joined", system = true, at = t0 + 60_000),
        Together.ChatLine("Ravi", "this one!! turn it up", at = t0 + 90_000, fromId = "r"),
        Together.ChatLine("Ravi", "can we do Starboy next?", at = t0 + 95_000, fromId = "r"),
        Together.ChatLine("Asha", "added it, it's up after this", mine = true, at = t0 + 120_000),
        Together.ChatLine("", "Meera joined", system = true, at = t0 + 180_000),
        Together.ChatLine("Meera", "hi all 👋", at = t0 + 200_000, fromId = "m"),
    )

    @Test fun listenTogether() {
        val list = songs(8)
        Player.cue(list, 0)
        val t0 = System.currentTimeMillis() - 400_000
        shoot("parity-together-start", 1500, 900) { Page { ListenTogetherContent(null) } }
        shoot("parity-together-start-narrow", 820, 1000) { Page { ListenTogetherContent(null) } }
        val host = Together.Room(
            code, hosting = true, myName = "Asha", phase = Together.Phase.Live, relays = 6, members = people.take(1), hostName = "Asha",
            track = track, myId = "me", askToJoin = true,
            chat = chat(t0).take(1),
        )
        shoot("parity-together-host", 1500, 1000) { Page { ListenTogetherContent(host) } }
        val busy = host.copy(members = people, open = true, chat = chat(t0), requests = listOf(Member("k", "Kabir"), Member("z", "Zoya")))
        shoot("parity-together-host-busy", 1500, 1000) { Page { ListenTogetherContent(busy) } }
        val guest = Together.Room(
            code, hosting = false, myName = "Ravi", phase = Together.Phase.Live, relays = 5, members = people, hostName = "Asha",
            track = track, myId = "r", open = false,
            chat = chat(t0).map { if (it.fromId == "r") it.copy(mine = true) else if (it.mine) it.copy(mine = false, fromId = "me") else it },
        )
        shoot("parity-together-guest", 1500, 1000) { Page { ListenTogetherContent(guest) } }
        shoot("parity-together-guest-narrow", 820, 1300) { Page { ListenTogetherContent(guest.copy(holding = true)) } }
    }
}
