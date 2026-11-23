package com.opentune.ui.welcome

import android.Manifest
import android.app.LocaleManager
import android.content.Intent
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import com.opentune.data.ExploreFeed
import com.opentune.data.innertube.Innertube
import com.opentune.data.releases.NewReleases
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.WelcomeState
import com.opentune.i18n.Tr
import com.opentune.ui.components.Opening
import com.opentune.ui.components.Support
import com.opentune.ui.theme.AppLanguage
import com.opentune.ui.theme.AppLanguages
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The first-run welcome on a small phone: its order, its back button, its language and its links. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xxhdpi", application = android.app.Application::class)
class WelcomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val original = Locale.getDefault()
    private val artists = listOf(ExploreFeed.Artist("UC_welcome_a", "Artist A", null), ExploreFeed.Artist("UC_welcome_b", "Artist B", null))
    /** The language Innertube would have asked YouTube in, as the popular artists loaded. */
    private var loadedIn: String? = null
    private var done = 0

    @Before fun setUp() {
        val app = compose.activity.application
        // Already answered, so neither question opens Android's permission page over the test.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        AppSettings.init(compose.activity)
        AppSettings.updateUi { it.copy(reduceAnimation = true, openingAnimation = false, welcome = WelcomeState.DUE) }
        Opening.played = true
        Locale.setDefault(Locale.US)
        Tr.use(compose.activity, "en")
    }

    @After fun tearDown() {
        artists.forEach { NewReleases.unfollow(compose.activity, it.browseId) }
        compose.activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        compose.activity.getSharedPreferences("app_language", 0).edit().clear().commit()
        Tr.use(compose.activity, "en")
        Locale.setDefault(original)
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                WelcomeScreen(
                    onDone = { done++ },
                    loadPopular = { loadedIn = Innertube.currentLanguage; artists },
                    loadSimilar = { emptyList() },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun showing(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    @Test fun languageComesFirstAndAChoiceAppliesAtOnceAndIsKept() {
        show()
        showing("Choose your language")
        // The phone's own language starts chosen.
        compose.onNodeWithText("Phone language").assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Español"))
        compose.onNodeWithText("Español").performClick()
        compose.waitForIdle()
        // Tried out at once: the welcome's words, and the language YouTube is asked in.
        compose.onNodeWithText("Español").assertIsSelected()
        showing("Continuar")
        assertEquals("es", Innertube.currentLanguage)
        // Nothing kept until the listener moves on.
        assertEquals(AppLanguage.SYSTEM, AppLanguages.selected(compose.activity))
        tap("Continuar")
        // Kept as Settings keeps it, and the artists were fetched in it.
        assertEquals(AppLanguage.SPANISH, AppLanguages.selected(compose.activity))
        assertEquals("es", compose.activity.getSharedPreferences("app_language", 0).getString("tag", null))
        assertEquals("es", Tr.language)
        showing("Elige artistas que te gusten")
        compose.onNodeWithText("Artist A").assertExists()
        assertEquals("es", loadedIn)
    }

    @Test fun keepingThePhonesLanguageChangesNothing() {
        show()
        tap("Skip")
        assertEquals(AppLanguage.SYSTEM, AppLanguages.selected(compose.activity))
        assertNull(compose.activity.getSharedPreferences("app_language", 0).getString("tag", null))
        showing("Pick artists you like")
        assertEquals("en", loadedIn)
    }

    @Test fun stepsComeInOrderAndBackGoesAStepBack() {
        show()
        tap("Continue")
        showing("Pick artists you like")
        back()
        showing("Choose your language")
        tap("Continue")
        tap("Artist A")
        tap("Done · 1 artist")
        showing("Make it yours")
        assertTrue(NewReleases.isFollowed("UC_welcome_a"))
        back()
        showing("Pick artists you like")
        // Unpicked after going back: unfollowed as the step is left again.
        tap("Artist A")
        tap("Artist B")
        tap("Done · 1 artist")
        assertFalse(NewReleases.isFollowed("UC_welcome_a"))
        assertTrue(NewReleases.isFollowed("UC_welcome_b"))
        tap("Continue")
        showing("Sign in to YouTube Music")
        tap("Not now")
        showing("Made together")
        back()
        showing("Sign in to YouTube Music")
        tap("Not now")
        assertEquals(0, done)
        assertEquals(WelcomeState.DUE, AppSettings.ui.value.welcome)
        tap("Start listening")
        // Finishing marks the welcome seen, for good.
        assertEquals(1, done)
        assertEquals(WelcomeState.SEEN, AppSettings.ui.value.welcome)
    }

    @Test fun skippingTheLastStepFinishesToo() {
        show()
        tap("Skip")
        tap("Skip")
        tap("Continue")
        tap("Not now")
        tap("Skip")
        assertEquals(1, done)
        assertEquals(WelcomeState.SEEN, AppSettings.ui.value.welcome)
    }

    @Test fun communityLinksOpenTheConfiguredPages() {
        show()
        tap("Skip")
        tap("Skip")
        tap("Continue")
        tap("Not now")
        showing("Made together")
        val shadow = shadowOf(compose.activity)
        while (shadow.nextStartedActivity != null) Unit

        tap("Star NepTune on GitHub")
        val github = shadow.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, github.action)
        assertEquals(Support.REPO, github.dataString)
        assertTrue(github.hasCategory(Intent.CATEGORY_BROWSABLE))

        tap("Join the Discord")
        val discord = shadow.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, discord.action)
        assertEquals(Support.DISCORD, discord.dataString)
        // Opening a link is all it does: the welcome stays until it's done.
        assertEquals(0, done)
        showing("Made together")
    }
}
