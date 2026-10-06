package com.opentune.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Song rows inside Home's four-row Recents grid, which scrolls sideways. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SongRowScrollTest {
    @get:Rule val compose = createComposeRule()

    private val songs = List(24) { Song("id$it", "Song $it", "Artist", null, "3:00") }

    @Before fun setUp() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
    }

    /** Swipes left across the first row; returns the grid's state afterwards and how many songs were queued. */
    private fun swipe(swipeToQueue: Boolean): Pair<LazyGridState, Int> {
        lateinit var state: LazyGridState
        var queued = 0
        compose.setContent {
            state = rememberLazyGridState()
            LazyHorizontalGrid(GridCells.Fixed(4), Modifier.width(360.dp).height(64.dp * 4).testTag("grid"), state) {
                items(songs.size) { i ->
                    SongListItem(
                        songs[i],
                        onClick = {},
                        modifier = Modifier.width(340.dp),
                        onPlayNext = { queued++ },
                        onAddToQueue = { queued++ },
                        swipeToQueue = swipeToQueue,
                    )
                }
            }
        }
        compose.onNodeWithTag("grid").performTouchInput { swipeLeft(startX = right - 20f, endX = left + 20f) }
        compose.waitForIdle()
        return state to queued
    }

    @Test fun gridScrollsWhenRowsDontSwipe() {
        val (state, queued) = swipe(swipeToQueue = false)
        assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0)
        assertEquals(0, queued)
    }

    @Test fun swipeableRowsHoldTheGridStill() {
        // Why Recents turns the swipe off: with it on, the row takes the drag.
        val (state, _) = swipe(swipeToQueue = true)
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }
}
