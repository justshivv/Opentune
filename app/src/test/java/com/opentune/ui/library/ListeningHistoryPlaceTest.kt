package com.opentune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.history.History
import com.opentune.ui.browse.SongActions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A song played while Listening history is open goes in at the top; the rows being read stay put. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ListeningHistoryPlaceTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val now = System.currentTimeMillis()

    private fun listens(count: Int, newest: Int = 0) = JsonArray((0 until count).map { i ->
        buildJsonObject {
            put("videoId", "v${newest + i}"); put("title", "Song ${newest + i}"); put("artist", "Artist")
            put("playedAt", now - (i + newest) * 60_000L); put("listenedMs", 200_000L)
        }
    })

    @Test fun aNewListenDoesNotMoveTheRowsInView() {
        History.init(ApplicationProvider.getApplicationContext())
        History.importJson(listens(40, newest = 1))
        compose.setContent { ListeningHistoryScreen(PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {}) }
        compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitForIdle()
        val before = compose.onNodeWithText("Song 20").fetchSemanticsNode().boundsInRoot.top

        // "Song 0" is played now: one more row at the top of the list.
        compose.runOnIdle { History.importJson(listens(41)) }
        compose.waitForIdle()

        assertEquals(before, compose.onNodeWithText("Song 20").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
}
