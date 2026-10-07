package com.opentune.ui.components

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Quick flicks down a long list with the app's rubber band, the way a thumb pages through it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RubberBandFlickTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyQuickFlickMovesTheList() {
        lateinit var state: LazyListState
        compose.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides RubberBandOverscrollFactory) {
                state = rememberLazyListState()
                LazyColumn(Modifier.fillMaxWidth().height(600.dp).testTag("list"), state) {
                    items(2_000) { Text("Row $it", Modifier.height(56.dp)) }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        repeat(8) { n ->
            compose.onNodeWithTag("list").performTouchInput { swipeUp(startY = bottom - 40f, endY = centerY, durationMillis = 90) }
            val released = state.firstVisibleItemIndex
            // Let the glide run out, then flick again a moment later.
            var waited = 0
            while (waited < 6_000 && (waited < 100 || state.isScrollInProgress)) {
                compose.mainClock.advanceTimeBy(16)
                waited += 16
            }
            compose.mainClock.advanceTimeBy(48)
            val now = state.firstVisibleItemIndex
            // A flick glides on well past where the finger let go.
            assertTrue("flick ${n + 1} stopped at row $now, let go at row $released", now > released + 5)
        }
    }
}
