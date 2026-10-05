package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.ExploreFeed
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import com.opentune.data.podcasts.PodcastEpisode
import com.opentune.data.podcasts.PodcastShow
import com.opentune.ui.browse.SongActions
import com.opentune.ui.explore.ExploreBoard
import com.opentune.ui.podcasts.EpisodeRow
import com.opentune.ui.podcasts.ShowRow
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws Explore's board and podcast rows to build/screenshots with made-up data (no network, so no covers). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ExploreScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val canvas = ExploreFeed.Canvas(
        artists = listOf("Taylor Swift", "The Weeknd", "Arijit Singh", "Billie Eilish", "Drake").mapIndexed { i, n -> ExploreFeed.Artist("UC$i", n, null) },
        tiles = ExploreFeed.weave(
            listOf(
                listOf("Chill", "Workout", "Focus", "Party", "Sleep", "Romance").map { ExploreFeed.Tile.Mood(MoodGenre(it, "FEmusic_moods_and_genres_category", it), ExploreFeed.Kind.MOOD) },
                listOf("Midnights", "Hurry Up Tomorrow", "GNX", "Short n' Sweet").map { ExploreFeed.Tile.Item(ShelfItem(it, "Album • 2025", null, null, "MPRE$it"), "New albums", ExploreFeed.Kind.NEW) },
                listOf("Top 100 Global", "Trending India").map { ExploreFeed.Tile.Item(ShelfItem(it, "Chart", null, null, "VL$it"), "Charts", ExploreFeed.Kind.CHART) },
            ),
        ),
    )

    @Test fun board() {
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) { androidx.compose.material3.Surface(color = Color(0xFF0B0B0E)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) {
                    ExploreBoard(canvas, PaddingValues(), null, {}, {}, {})
                }
            }
            }
        }
        save("explore")
    }

    @Test fun podcastRows() {
        val actions = SongActions(null, false, { _, _, _, _ -> }, {}, {})
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) { androidx.compose.material3.Surface(color = Color(0xFF0B0B0E)) {
                Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) {
                    ShowRow(PodcastShow("MPSP1", "The Joe Rogan Experience", "PowerfulJRE")) {}
                    listOf(
                        PodcastEpisode("a1", "Unc, Ocho & Iso react to Lions-Panthers, Cowboys beat Texans", "Nightcap", "MPSP2", published = "11h ago", durationText = "3 hr 8 min", description = "Shannon Sharpe, Chad Johnson and Iso Joe Johnson react to Week 4 of the NFL season."),
                        PodcastEpisode("a2", "Using Your Nervous System to Enhance Your Immune System", "Huberman Lab", "MPSP3", published = "Sep 25, 2025", durationText = "38 min"),
                    ).forEach { EpisodeRow(it, actions, showShow = true, onOpenShow = {}) {} }
                }
            }
            }
        }
        save("podcast-rows")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
