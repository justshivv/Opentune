package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.opentune.data.settings.DockMotion
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the dock to build/screenshots so its look can be checked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class DockScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Composable
    private fun tabs() = listOf(
        ChromeTab("Home", ImageVector.vectorResource(AppIcons.HomeOutline), ImageVector.vectorResource(AppIcons.Home)),
        ChromeTab("Explore", ImageVector.vectorResource(AppIcons.ExploreOutline), ImageVector.vectorResource(AppIcons.Explore)),
        ChromeTab("Podcasts", ImageVector.vectorResource(AppIcons.Podcasts), ImageVector.vectorResource(AppIcons.Podcasts)),
        ChromeTab("Library", ImageVector.vectorResource(AppIcons.LibraryMusicOutline), ImageVector.vectorResource(AppIcons.LibraryMusic)),
    )

    @Test fun dock() {
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) { androidx.compose.material3.Surface(color = Color(0xFF0B0B0E)) {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF3A2A4A), Color(0xFF101014)))).padding(vertical = 24.dp)) {
                    BottomChrome(
                        inline = false,
                        tabs = tabs(),
                        selected = 1,
                        onSelect = {},
                        onExpand = {},
                        searchSelected = false,
                        onSearch = {},
                        mini = null,
                    )
                }
            }
            }
        }
        save("dock")
    }

    /** The dock in each glass style, over a bright cover-like background. */
    @Test fun glassStyles() {
        com.opentune.data.settings.AppSettings.init(compose.activity)
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) { androidx.compose.material3.Surface(color = Color(0xFF0B0B0E)) {
                Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFFE0457B), Color(0xFF3A7BD5), Color(0xFFF2C14E)))).padding(vertical = 24.dp)) {
                    BottomChrome(
                        inline = false,
                        tabs = tabs(),
                        selected = 1,
                        onSelect = {},
                        onExpand = {},
                        searchSelected = false,
                        onSearch = {},
                        mini = null,
                    )
                }
            }
            }
        }
        com.opentune.data.settings.GlassStyle.entries.forEach { g ->
            com.opentune.data.settings.AppSettings.updateUi { it.copy(glassStyle = g) }
            compose.mainClock.advanceTimeBy(1_000)
            save("glass/${g.name.lowercase()}")
        }
        com.opentune.data.settings.AppSettings.updateUi { it.copy(glassStyle = com.opentune.data.settings.GlassStyle.WEB) }
    }

    /**
     * Each dock motion part of the way through folding and unfolding, to
     * build/screenshots/motion, so the frames can be looked over.
     */
    @Test fun motions() {
        var inline by mutableStateOf(false)
        var motion by mutableStateOf(DockMotion.MINIMIZE)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) { androidx.compose.material3.Surface(color = Color(0xFF0B0B0E)) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A2A4A), Color(0xFF101014)))).padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
                    BottomChrome(
                        inline = inline,
                        tabs = tabs(),
                        selected = 1,
                        onSelect = {},
                        onExpand = {},
                        searchSelected = false,
                        onSearch = {},
                        mini = { _, m -> Box(m.clip(RoundedCornerShape(20.dp)).background(Color(0xFF6E4AA8))) },
                        motion = motion,
                    )
                }
            }
            }
        }
        val frames = listOf(0L, 60, 120, 200, 300, 420, 600, 900)
        DockMotion.entries.forEach { m ->
            motion = m
            inline = false
            compose.mainClock.advanceTimeBy(2_000)
            for ((dir, target) in listOf("fold" to true, "open" to false)) {
                inline = target
                var at = 0L
                frames.forEach { t ->
                    compose.mainClock.advanceTimeBy(t - at)
                    at = t
                    save("motion/${m.name.lowercase()}-$dir-${"%03d".format(t)}", idle = false)
                }
                compose.mainClock.advanceTimeBy(2_000)
            }
        }
    }

    /** The Poster layout's chrome: the mini player and the dock in smoked glass, over a cover-coloured page. */
    @Test fun posterChrome() {
        com.opentune.data.settings.AppSettings.init(compose.activity)
        com.opentune.data.settings.AppSettings.updateUi { it.copy(pageLayout = com.opentune.data.settings.PageLayout.POSTER, reduceBlur = true) }
        val song = com.opentune.data.model.Song("x", "Run For Roses", "NMIXX", null)
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) { androidx.compose.material3.Surface(color = Color(0xFF34404E)) {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF5B6E86), Color(0xFF2B3442)))).padding(vertical = 24.dp)) {
                    BottomChrome(
                        inline = false,
                        tabs = tabs(),
                        selected = 0,
                        onSelect = {},
                        onExpand = {},
                        searchSelected = false,
                        onSearch = {},
                        mini = { inline, m -> com.opentune.ui.player.MiniPlayer(song, isPlaying = true, isBuffering = false, hasNext = true, progress = { 0.4f },
                            onTogglePlay = {}, onNext = {}, onPrevious = {}, onClick = {}, modifier = m, inline = inline) },
                    )
                }
            } }
        }
        save("dock-poster")
    }

    /** Folded by a scroll: the dock gone, the card in its place with a round cover. The Poster layout's chrome: the mini player and the dock in smoked glass, over a cover-coloured page. */
    @Test fun posterChromeFolded() {
        com.opentune.data.settings.AppSettings.init(compose.activity)
        com.opentune.data.settings.AppSettings.updateUi { it.copy(pageLayout = com.opentune.data.settings.PageLayout.POSTER, reduceBlur = true) }
        val song = com.opentune.data.model.Song("x", "Run For Roses", "NMIXX", null)
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) { androidx.compose.material3.Surface(color = Color(0xFF34404E)) {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF5B6E86), Color(0xFF2B3442)))).padding(vertical = 24.dp)) {
                    BottomChrome(
                        inline = true,
                        tabs = tabs(),
                        selected = 0,
                        onSelect = {},
                        onExpand = {},
                        searchSelected = false,
                        onSearch = {},
                        mini = { inline, m -> com.opentune.ui.player.MiniPlayer(song, isPlaying = true, isBuffering = false, hasNext = true, progress = { 0.4f },
                            onTogglePlay = {}, onNext = {}, onPrevious = {}, onClick = {}, modifier = m, inline = inline) },
                    )
                }
            } }
        }
        save("dock-poster-folded")
    }

    /** The Poster dock's focus pill jumping from Home to Library: lift-off, flight, landing, settled. */
    @Test fun posterPillJumps() {
        com.opentune.data.settings.AppSettings.init(compose.activity)
        com.opentune.data.settings.AppSettings.updateUi { it.copy(pageLayout = com.opentune.data.settings.PageLayout.POSTER, reduceBlur = true) }
        var selected by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme(darkColorScheme()) { androidx.compose.material3.Surface(color = Color(0xFF34404E)) {
                Box(Modifier.fillMaxWidth().background(Color(0xFF3A4656)).padding(vertical = 24.dp)) {
                    BottomChrome(inline = false, tabs = tabs(), selected = selected, onSelect = {}, onExpand = {},
                        searchSelected = false, onSearch = {}, mini = null)
                }
            } }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        selected = 3
        var at = 0L
        for (t in listOf(80L, 150L, 260L, 380L, 1500L)) {
            compose.mainClock.advanceTimeBy(t - at)
            at = t
            save("dock-poster-jump-$t", idle = false)
        }
        // And back to Home: the same jump the other way.
        selected = 0
        at = 0L
        for (t in listOf(80L, 200L, 1500L)) {
            compose.mainClock.advanceTimeBy(t - at)
            at = t
            save("dock-poster-home-$t", idle = false)
        }
    }

    private fun save(name: String, idle: Boolean = true) {
        if (idle) compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
