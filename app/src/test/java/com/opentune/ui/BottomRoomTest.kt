package com.opentune.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The room under every page for the dock and mini player eases in when the first song starts.
 * Read where AppRoot made it, every frame of that easing composed the whole app again; now only
 * the pages' lists lay out again, and they still end at the new room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BottomRoomTest {
    @get:Rule val compose = createComposeRule()

    @Test fun easingTheRoomDoesNotComposeTheShellAgain() {
        val playing = mutableStateOf(false)
        var shellCompositions = 0
        lateinit var room: BottomRoom
        compose.setContent {
            val bottom = animateDpAsState(if (playing.value) 160.dp else 100.dp, tween(420), label = "room")
            room = remember(bottom) { BottomRoom(bottom) }
            SideEffect { shellCompositions++ }
            Page(room)
        }
        compose.waitForIdle()
        val before = shellCompositions
        compose.runOnIdle { playing.value = true }
        // Runs the easing to its end, frame by frame.
        compose.waitForIdle()
        // Once for the new target; none for each frame of the easing.
        assertTrue("shell composed ${shellCompositions - before} times while easing", shellCompositions - before <= 2)
        assertEquals(160.dp, room.calculateBottomPadding())
    }

    @Composable
    private fun Page(room: BottomRoom) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = room) { item { Box(Modifier) } }
        }
    }
}
