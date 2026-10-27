package com.opentune.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchLeadingIconTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchingIconsKeepsFieldBoundsStableThroughoutTheFade() {
        val back = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                TextField("", {}, singleLine = true, modifier = Modifier.testTag("field"),
                    leadingIcon = { SearchLeadingIcon(back.value, onBack = {}, onSearch = {}) })
            }
        }
        compose.mainClock.advanceTimeBy(200)
        val field = compose.onNodeWithTag("field")
        val before = field.fetchSemanticsNode().boundsInRoot
        for (value in listOf(true, false)) {
            compose.runOnIdle { back.value = value }
            for (frame in 1..12) {
                compose.mainClock.advanceTimeByFrame()
                assertEquals(before, field.fetchSemanticsNode().boundsInRoot)
            }
        }
    }
}
