package com.opentune.ui.together

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keyboard slides in over many frames; the island rides up on it, and the
 * room page around it isn't composed again on each of those frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class RoomKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun still() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { it.copy(reduceBlur = true, reduceAnimation = true) }
    }

    /** A keyboard whose height the test sets, frame by frame. */
    private class Keyboard : WindowInsets {
        val px = mutableIntStateOf(0)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = px.intValue
    }

    @Test fun keyboardMovesTheIslandWithoutComposingThePageAgain() {
        val keyboard = Keyboard()
        val room = Together.Room(
            code = RoomCode.parse("K7QX-M2PA-9DTE")!!, hosting = true, myName = "Asha", phase = Together.Phase.Live,
            members = listOf(Member("a", "Asha", host = true), Member("b", "Ravi")), hostName = "Asha", myId = "a",
        )
        compose.setContent { MaterialTheme { RoomView(room, onBack = {}, keyboard = keyboard) } }
        compose.waitForIdle()
        val island = compose.onNodeWithText("Tap to chat")
        val rest = island.getUnclippedBoundsInRoot().bottom
        val recomposer = Recomposer.runningRecomposers.value.single()
        val changes = recomposer.changeCount
        // The keyboard rises over a few frames.
        for (px in listOf(200, 500, 800)) {
            keyboard.px.intValue = px
            compose.waitForIdle()
        }
        val up = island.getUnclippedBoundsInRoot().bottom
        val risen = with(compose.density) { 800.toDp() }
        // It sits 8dp above the keyboard, as it did before (it was 8dp off the bottom at rest).
        assertEquals((rest - risen).value, up.value, 1f)
        assertEquals("the room page was composed again as the keyboard moved", changes, recomposer.changeCount)
    }
}
