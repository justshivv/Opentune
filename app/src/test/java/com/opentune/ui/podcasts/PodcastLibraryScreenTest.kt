package com.opentune.ui.podcasts

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.opentune.data.podcasts.PodcastShow
import com.opentune.data.podcasts.Podcasts
import com.opentune.ui.browse.SongActions
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi", application = android.app.Application::class)
class PodcastLibraryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val actions = SongActions(null, false, { _, _, _, _ -> }, {}, {})
    private val show = PodcastShow("rss:aHR0cHM6Ly9mZWVkcy5leGFtcGxlLm9yZy90aW55LnJzcw", "Tiny Show", "Two Friends")

    @After fun clear() = Podcasts.setSubscribed(show, false)

    @Test fun emptyLibraryExplainsHowToAddAShow() {
        compose.setContent { PodcastLibraryScreen(PaddingValues(), actions, onBack = {}, onOpenShow = {}) }
        compose.onNodeWithText("No shows yet").assertIsDisplayed()
        compose.onNodeWithText("Add a show by its feed").assertIsDisplayed()
    }

    @Test fun subscribedShowsAreListedAndOpen() {
        Podcasts.setSubscribed(show, true)
        var opened: String? = null
        compose.setContent { PodcastLibraryScreen(PaddingValues(), actions, onBack = {}, onOpenShow = { opened = it }) }
        compose.onNodeWithText("Tiny Show").assertIsDisplayed().performClick()
        assertTrue(opened == show.browseId)
        compose.onNodeWithText("Shows · 1").assertIsDisplayed()
    }

    @Test fun anAddressThatIsntALinkIsExplained() {
        compose.setContent { PodcastLibraryScreen(PaddingValues(), actions, onBack = {}, onOpenShow = {}) }
        compose.onNodeWithText("https://…/feed.rss").performTextInput("my favourite show")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Enter the feed's full address, starting with https://").assertIsDisplayed()
    }

    @Test fun nothingInProgressOrPlayedYet() {
        compose.setContent { PodcastLibraryScreen(PaddingValues(), actions, onBack = {}, onOpenShow = {}) }
        compose.onNodeWithText("In progress").performClick()
        compose.onNodeWithText("Nothing in progress").assertIsDisplayed()
        compose.onNodeWithText("Played").performClick()
        compose.onNodeWithText("Nothing played yet").assertIsDisplayed()
    }
}
