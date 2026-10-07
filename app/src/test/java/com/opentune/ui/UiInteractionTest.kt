package com.opentune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.pressable
import com.opentune.ui.home.RecentListeningCard
import com.opentune.ui.library.NameDialog
import com.opentune.ui.search.Typing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks the new actions without a music service, network, or playback session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UiInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { it.copy(reduceAnimation = true, reduceBlur = true) }
    }

    @After fun tearDown() {
        AppSettings.updateUi { it.copy(reduceAnimation = false, reduceBlur = false) }
    }

    @Test fun recentListeningOffersSeparatePlayAndShuffleActions() {
        var plays = 0
        var shuffles = 0
        compose.setContent {
            MaterialTheme {
                RecentListeningCard(Song("track", "Blue Train", "John Coltrane", null, "10:43"), { plays++ }, { shuffles++ })
            }
        }
        compose.onNodeWithText("Play again").performClick()
        assertEquals(1, plays)
        assertEquals(0, shuffles)
        compose.onNodeWithText("Shuffle recents").performClick()
        assertEquals(1, shuffles)
    }

    @Test fun aQueryCanBeSubmittedBeforeSuggestionsArrive() {
        var submitted: String? = null
        compose.setContent {
            MaterialTheme { Typing("blue train", emptyList(), emptyList(), PaddingValues(), { submitted = it }, {}) }
        }
        compose.onNodeWithContentDescription("Search now").performClick()
        assertEquals("blue train", submitted)
    }

    @Test fun duplicateSuggestionsStayUniqueAndFillDoesNotSubmit() {
        var submitted: String? = null
        var filled: String? = null
        compose.setContent {
            MaterialTheme {
                Typing("blue", listOf("blue train", "blue train"), emptyList(), PaddingValues(), { submitted = it }, { filled = it })
            }
        }
        compose.onAllNodesWithText("blue train").assertCountEquals(1)
        compose.onNodeWithContentDescription("Use this").performClick()
        assertEquals("blue train", filled)
        assertEquals(null, submitted)
        compose.onNodeWithText("blue train").performClick()
        assertEquals("blue train", submitted)
    }

    @Test fun aPlaylistNeedsANameAndSavesWithoutSurroundingSpaces() {
        var saved: String? = null
        compose.setContent {
            MaterialTheme { NameDialog("New playlist", "   ", {}, { saved = it }) }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("  Night drive  ")
        compose.onNodeWithText("Save").performClick()
        assertEquals("Night drive", saved)
    }

    @Test fun aDisabledPressableControlDoesNotInvokeItsAction() {
        var clicks = 0
        compose.setContent {
            Box(Modifier.size(64.dp).testTag("control").pressable({ clicks++ }, enabled = false))
        }
        compose.onNodeWithTag("control").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test fun reducedAnimationKeepsPressedCardsAtTheirFullSize() {
        compose.setContent {
            Box(Modifier.size(64.dp).testTag("card").pressable({}, pressedScale = 0.8f))
        }
        val card = compose.onNodeWithTag("card")
        val before = card.fetchSemanticsNode().boundsInRoot.width
        card.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(before, card.fetchSemanticsNode().boundsInRoot.width, 0.01f)
        card.performTouchInput { up() }
    }
}
