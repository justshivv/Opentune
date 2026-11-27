package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pictures of the full player (the queue, the lyrics, a narrow window, and a
 * frame of it sliding up), drawn offscreen. Off unless asked for:
 * ./gradlew :desktop:test --tests '*PlayerShots*' -Dopentune.shots=/some/folder
 */
class PlayerShots {
    private val folder = System.getProperty("opentune.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun save(name: String, image: org.jetbrains.skia.Image) {
        folder!!.mkdirs()
        File(folder, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private fun shoot(name: String, width: Int, height: Int, waitMs: Long = 7_000, density: Float = 1.5f, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(density)) { content() }
        val start = System.nanoTime()
        var at = 0L
        while (at < waitMs) {
            scene.render(System.nanoTime() - start)
            Thread.sleep(50)
            at = (System.nanoTime() - start) / 1_000_000
        }
        save(name, scene.render(System.nanoTime() - start))
        scene.close()
    }

    /** Invented placeholder lines; the fifth is being sung at position 0, its words half lit. */
    private val sample: Lyrics.Synced = run {
        val texts = listOf(
            "First line of the song", "Second line here", "Lights over the river", "We kept on walking home",
            "Counting every streetlight", "Somewhere the night is waiting", "Hold the quiet for a while",
            "Another verse begins", "Paper boats and summer rain", "Over and over again", "The last line fades away",
        )
        val lines = texts.mapIndexed { i, t ->
            val start = (i - 4) * 4_000L - 1_500L
            val parts = t.split(" ")
            val step = 4_000L / parts.size
            val words = parts.mapIndexed { k, w -> LyricWord(if (k < parts.lastIndex) "$w " else w, start + k * step, start + (k + 1) * step) }
            LyricLine(start, start + 4_000L, t, words, wordSynced = true)
        }
        Lyrics.Synced(lines, source = "LRCLIB")
    }

    @Test fun player() {
        assumeTrue(folder != null)
        AppSettings.init(appContext)
        shotVault()
        AppSettings.updateTheme { it.copy(mode = ThemeMode.DARK) }
        Library.useFile(java.nio.file.Files.createTempDirectory("player").resolve("desktop.json").toFile())
        val songs = listOf(
            "4NRXx6U8ABQ" to "Blinding Lights", "XXYlFuWEuKI" to "Save Your Tears", "JGwWNGJdvx8" to "Shape of You",
            "60ItHLz5WEA" to "Faded", "YQHsXMglC9A" to "Hello", "kJQP7kiw5Fk" to "Despacito", "fRh_vgS2dFE" to "Sorry",
        ).map { (id, t) -> com.opentune.data.model.Song(id, t, "Some Artist", "https://i.ytimg.com/vi/$id/hqdefault.jpg", "3:20", albumName = "Sample Album") }
        Player.cue(songs, 0)
        val found = LyricsState.Found(sample)
        shoot("player-queue-wide", 1700, 1000) { DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = PlayerPanel.QUEUE) } }
        shoot("player-lyrics-wide", 1700, 1000) {
            DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = PlayerPanel.LYRICS, lyricsOverride = found) }
        }
        shoot("player-big-queue", 1920, 1080, density = 1f) { DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = PlayerPanel.QUEUE) } }
        shoot("player-big-lyrics", 1920, 1080, density = 1f) {
            DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = PlayerPanel.LYRICS, lyricsOverride = found) }
        }
        shoot("player-no-panel", 1700, 1000) { DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = null) } }
        shoot("player-no-lyrics", 1700, 1000) {
            DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = PlayerPanel.LYRICS, lyricsOverride = LyricsState.NotFound) }
        }
        shoot("player-narrow", 640, 1000) { DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = null) } }
        shoot("player-narrow-queue", 640, 1000) { DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = PlayerPanel.QUEUE) } }
        shoot("player-narrow-lyrics", 640, 1000) {
            DesktopTheme { NowPlaying(songs[0], docked = false, onClose = {}, startPanel = PlayerPanel.LYRICS, lyricsOverride = found) }
        }
        slideUp(songs)
    }

    /**
     * The sheet rising: opened once and closed so the covers are loaded, then
     * opened again on a clock driven by hand, with frames taken along the way.
     */
    private fun slideUp(songs: List<com.opentune.data.model.Song>) {
        val open = mutableStateOf(true)
        val scene = ImageComposeScene(1700, 1000, Density(1.5f)) {
            DesktopTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF202024))) {
                    Text("The page under the player", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    PlayerOverlay(open.value, songs[0].thumbnailUrl, onClose = { open.value = false }) {
                        NowPlaying(songs[0], docked = false, onClose = { open.value = false }, startPanel = PlayerPanel.QUEUE)
                    }
                }
            }
        }
        var t = 0L
        fun frames(ms: Long, sleep: Boolean = false) {
            val end = t + ms * 1_000_000
            while (t < end) {
                t += 16_000_000
                scene.render(t)
                if (sleep) Thread.sleep(16)
            }
        }
        frames(6_000, sleep = true)
        open.value = false
        frames(600)
        open.value = true
        scene.render(t)
        frames(130)
        save("player-slide-130ms", scene.render(t))
        frames(110)
        save("player-slide-240ms", scene.render(t))
        frames(400)
        save("player-slide-done", scene.render(t))
        scene.close()
    }
}
