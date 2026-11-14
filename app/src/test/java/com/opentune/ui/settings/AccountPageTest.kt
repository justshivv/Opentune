package com.opentune.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.addons.AddonManifest
import com.opentune.data.addons.AddonStore
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Account & integrations: every service sits in one card with its own Connect button, and
 * Spotify's own options only show once Spotify is connected. Audio sources: a tapped source
 * offers its actions, and moving it changes the order sources are tried in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class AccountPageTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun servicesShareOneCardAndSpotifyOptionsWaitForSpotify() {
        AppSettings.init(context)
        LibraryStore.init(context)
        compose.setContent {
            OpenTuneTheme(ThemeSettings().copy(mode = ThemeMode.DARK, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(PaddingValues(), onBack = {}, onOpenEqualizer = {}, onOpenReplay = {})
                }
            }
        }
        compose.onNodeWithText("Account & integrations").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sign in").assertExists()
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("Connected services", ignoreCase = true))
        list.performScrollToNode(hasText("Music server"))
        // The old separate Disconnect rows are gone; disconnecting is on each service's own row.
        compose.onAllNodesWithText("Disconnect Discord").assertCountEquals(0)
        list.performScrollToNode(hasText("Link a device"))
        compose.onAllNodesWithText("Spotify Canvas").assertCountEquals(0)
        compose.onAllNodesWithText("Connect Spotify in Account first").assertCountEquals(0)
    }

    @Test fun movingASourceChangesTheOrder() {
        AppSettings.init(context)
        AddonStore.init(context)
        AddonStore.add("https://first.example.com", AddonManifest("a", "First Source", "1"))
        AddonStore.add("https://second.example.com", AddonManifest("b", "Second Source", "1"))
        compose.setContent {
            OpenTuneTheme(ThemeSettings().copy(mode = ThemeMode.DARK, dynamicColor = false)) {
                AudioAddonsPage(onDismiss = {}, onAdd = {}, notice = null, onNotice = {})
            }
        }
        compose.onNodeWithText("First Source").performClick()
        compose.onAllNodesWithText("Move up").assertCountEquals(0)
        compose.onNodeWithText("Move down").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Second Source", "First Source"), AddonStore.addons.value.map { it.manifest.name })
    }

    @Test fun aTallerRowCanStillBeDraggedToTheTop() {
        // The second row is taller than the first (a divider, a wrapped status): dragged all
        // the way up it must land first, not stop second.
        assertEquals(0, sourceDropIndex(listOf(200, 260, 200), from = 1, top = 0f))
        assertEquals(0, sourceDropIndex(listOf(200, 202, 200), from = 1, top = 0f))
        // And to the bottom from the top.
        assertEquals(2, sourceDropIndex(listOf(260, 200, 200), from = 0, top = 400f))
        // Barely moved: stays put.
        assertEquals(1, sourceDropIndex(listOf(200, 200, 200), from = 1, top = 150f))
    }

    @Test fun audioSourcesIsOnTheMainSettingsPage() {
        AppSettings.init(context)
        LibraryStore.init(context)
        compose.setContent {
            OpenTuneTheme(ThemeSettings().copy(mode = ThemeMode.DARK, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(PaddingValues(), onBack = {}, onOpenEqualizer = {}, onOpenReplay = {})
                }
            }
        }
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("Audio sources"))
        compose.onAllNodesWithText("Audio sources").assertCountEquals(1)
        // Not on Sound and quality any more.
        compose.onNodeWithText("Sound and quality").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Audio sources").assertCountEquals(0)
        compose.onAllNodesWithText("Audio add-ons").assertCountEquals(0)
    }
}
