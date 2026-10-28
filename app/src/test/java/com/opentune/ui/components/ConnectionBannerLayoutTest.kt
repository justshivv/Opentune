package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConnectionBannerLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun noticePushesHeaderDownWithoutCoveringItAndKeepsBottomControlsOnScreen() {
        val visible = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val top = WindowInsets(top = 24.dp)
                ConnectionNoticeLayout(true, visible.value, modifier = Modifier.fillMaxSize(), statusInsets = top) {
                    Column(Modifier.align(Alignment.TopStart).windowInsetsPadding(top)) {
                        Text("Page heading", Modifier.testTag("heading"))
                    }
                    Text("Bottom controls", Modifier.align(Alignment.BottomCenter).testTag("bottom"))
                }
            }
        }
        val initial = compose.onNodeWithTag("heading").fetchSemanticsNode().boundsInRoot.top
        val bottom = compose.onNodeWithTag("bottom").fetchSemanticsNode().boundsInRoot.bottom
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible.value = true }
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        val moving = compose.onNodeWithTag("heading").fetchSemanticsNode().boundsInRoot.top
        assertTrue(moving > initial)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val header = compose.onNodeWithTag("heading").fetchSemanticsNode().boundsInRoot
        val banner = compose.onNodeWithText("You're back online.").fetchSemanticsNode().boundsInRoot
        assertTrue(header.top > moving)
        assertTrue("The banner must end before the page heading", banner.bottom <= header.top)
        assertEquals(bottom, compose.onNodeWithTag("bottom").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("You're back online.").assertDoesNotExist()
        assertEquals(initial, compose.onNodeWithTag("heading").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun reducedMotionStillReservesSpaceForOfflineNotice() {
        compose.setContent {
            MaterialTheme {
                ConnectionNoticeLayout(false, true, reduceMotion = true, modifier = Modifier.fillMaxSize(), statusInsets = WindowInsets(0)) {
                    Text("Page heading", Modifier.testTag("heading"))
                }
            }
        }
        val banner = compose.onNodeWithText("You're offline. Check your internet connection.").fetchSemanticsNode().boundsInRoot
        assertTrue(banner.bottom <= compose.onNodeWithTag("heading").fetchSemanticsNode().boundsInRoot.top)
    }
}
