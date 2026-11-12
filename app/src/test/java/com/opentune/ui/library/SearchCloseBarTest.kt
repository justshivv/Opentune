package com.opentune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.ui.browse.SongActions
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Closing search on a playlist scrolled past its cover: the bar stays solid, never flashing its glass pill. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SearchCloseBarTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var id: String

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        AppSettings.updateUi { it.copy(reduceBlur = true) }
        LibraryStore.init(context)
        id = LibraryStore.createPlaylist("Late nights")
        LibraryStore.addAllToPlaylist(id, (1..40).map { Song("song%07d".format(it), "Song $it", "Artist $it", null, "3:00") })
    }

    /** The bar's own title, shown only while the bar is solid (the cover's title sits far lower). */
    private fun barTitleShown(): Boolean = compose.onAllNodesWithText("Late nights").fetchSemanticsNodes()
        .any { it.boundsInRoot.top < 200f && it.boundsInRoot.height > 0f }

    @Test fun barStaysSolidAsSearchCloses() {
        compose.setContent {
            com.opentune.ui.theme.OpenTuneTheme(com.opentune.data.settings.ThemeSettings()) {
                LocalPlaylistScreen(id, PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {})
            }
        }
        compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitForIdle()
        assertTrue("solid past the cover", barTitleShown())
        compose.onNodeWithContentDescription("Search this list").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Search this list").performClick()
        repeat(8) { frame ->
            compose.mainClock.advanceTimeByFrame()
            assertTrue("bar went to its glass pill at frame $frame", barTitleShown())
        }
        compose.mainClock.autoAdvance = true
    }
}
