package com.opentune.ui.player

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EchoMotionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun clockFreezesWhilePausedAndInBackgroundThenResumes() {
        val running = mutableStateOf(true)
        lateinit var phase: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent { phase = rememberEchoPhase(running.value, 8_000) }
        compose.mainClock.advanceTimeBy(500)
        assertTrue(phase.value > 0f)
        running.value = false
        compose.mainClock.advanceTimeBy(32)
        val paused = phase.value
        compose.mainClock.advanceTimeBy(500)
        assertEquals(paused, phase.value, 0f)
        running.value = true
        compose.mainClock.advanceTimeBy(500)
        assertTrue(phase.value > paused)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        val background = phase.value
        compose.mainClock.advanceTimeBy(500)
        assertEquals(background, phase.value, 0f)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(500)
        assertTrue(phase.value > background)
    }
}
