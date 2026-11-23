package com.opentune.ui.explore

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opentune.data.ExploreFeed
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explore's chips narrow the board, and its tiles open what they show. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ExploreBoardTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val canvas = ExploreFeed.Canvas(
        artists = emptyList(),
        tiles = listOf(
            ExploreFeed.Tile.Mood(MoodGenre("Chill", "FEmusic_moods_and_genres_category", "a"), ExploreFeed.Kind.MOOD),
            ExploreFeed.Tile.Mood(MoodGenre("Haryanvi", "FEmusic_moods_and_genres_category", "b"), ExploreFeed.Kind.GENRE),
            ExploreFeed.Tile.Item(ShelfItem("GNX", "Album", null, null, "MPRE1"), "New albums", ExploreFeed.Kind.NEW),
        ),
    )

    @Test fun chipsNarrowTheBoardAndForYouBringsItAllBack() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                var filter by remember { mutableStateOf<String?>(null) }
                ExploreBoard(canvas, PaddingValues(), filter, { filter = it }, { opened += it.title }, { opened += it.title }, header = null)
            }
        }
        compose.onNodeWithText("Moods").performClick()
        compose.onNodeWithText("Chill").assertExists()
        compose.onNodeWithText("Haryanvi").assertDoesNotExist()
        compose.onNodeWithText("GNX").assertDoesNotExist()

        compose.onNodeWithText("For you").performClick()
        compose.onNodeWithText("Haryanvi").assertExists()
        compose.onNodeWithText("GNX").assertExists()

        compose.onNodeWithText("Haryanvi").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Haryanvi"), opened)
    }
}
