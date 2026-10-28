package com.opentune.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaylistSearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun searchStartsHiddenAndOutsideTapClosesAndClearsWithoutEatingTap() {
        lateinit var state: PlaylistSearch
        var outsideClicks = 0
        compose.setContent {
            MaterialTheme {
                state = remember { PlaylistSearch() }
                Column(Modifier.fillMaxSize().dismissPlaylistSearch(state)) {
                    Button(onClick = { state.open = true }) { Text("Search playlist") }
                    PlaylistSearchField(state)
                    Button(onClick = { outsideClicks++ }) { Text("Outside") }
                }
            }
        }
        compose.onNodeWithText("Search this list").assertDoesNotExist()
        compose.onNodeWithText("Search playlist").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Song")
        compose.onNodeWithText("Outside").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { assertEquals("", state.query); assertEquals(1, outsideClicks) }
    }
    @Test fun swipingResultsKeepsSearchPinnedAndQueryIntact() {
        lateinit var state: PlaylistSearch
        compose.setContent {
            MaterialTheme {
                state = remember { PlaylistSearch().apply { open = true; query = "Song" } }
                Box(Modifier.fillMaxSize().dismissPlaylistSearch(state)) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().playlistSearchResults(state)) {
                        items(40) { Text("Song $it", Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(64f))) }
                    }
                    PlaylistSearchHeader(state) { Text("Playlist", Modifier.height(androidx.compose.ui.unit.Dp(56f))) }
                }
            }
        }
        val field = compose.onNode(hasSetTextAction())
        val before = field.fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        field.assertIsDisplayed()
        assertEquals(before, field.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertTrue(state.open); assertEquals("Song", state.query) }
    }

}
