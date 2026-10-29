package com.opentune.ui.tv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.MainActivity
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.WelcomeState
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The whole app on a TV, driven as a remote drives it: keys go to the window in front
 * (a dialog's when one is open), and the app starts out of touch mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvAppTest {
    // Before the activity starts: no player service to connect to here, and the welcome seen.
    @get:Rule(order = 0) val setup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            Shadows.shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, com.opentune.playback.PlaybackService::class.java))
            AppSettings.updateUi { it.copy(reduceBlur = true, openingAnimation = false, welcome = WelcomeState.SEEN) }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun asOnATelevision() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.runOnUiThread {
            // A TV is never in touch mode, and its first key gives the app's view the focus.
            val root = composeIn(compose.activity.window.decorView)!!
            (root.javaClass.methods.first { it.name == "getInputModeManager" }.invoke(root) as InputModeManager)
                .requestInputMode(InputMode.Keyboard)
            root.requestFocus()
        }
        compose.waitForIdle()
    }

    @Test fun theRailAndThePageAreReachedWithTheArrows() {
        assertFocused("Search")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocused("Home")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right didn't leave the rail", focused() !in listOf("Home", "Search", "Explore"))
    }

    @Test fun theSearchBarLetsTheArrowsGo() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Songs, albums, artists")
        // Down leaves the bar for the sources under it, which stay while focused.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down stayed in the search bar", focused() in listOf("YouTube", "Library", "Add-ons"))
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up didn't come back to the bar", focused().startsWith("Songs, albums, artists"))
    }

    @Test fun backFromASettingsPageReturnsToItsRow() {
        repeat(5) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertFocused("Settings")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        var steps = 0
        while (!focused().startsWith("Look and feel")) {
            assertTrue("Never reached Look and feel", ++steps < 12)
            press(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back left the focus at ${focused()}", focused().startsWith("Look and feel"))
    }

    @Test fun backFromAPageKeepsTheFocusOnThePageItCameBackTo() {
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertFocused("Library")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focused().startsWith("Liked"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back sent the focus to ${focused()}", focused() !in listOf("Search", "Home", "Explore", "Podcasts", "Library", "Settings", ""))
    }

    private fun press(code: Int) {
        compose.runOnUiThread {
            val top = topWindow()
            val target: View = if (top == null || top === compose.activity.window.decorView) compose.activity.window.decorView else top
            // A window that just opened takes the focus, as the system gives it.
            if (target !== compose.activity.window.decorView && target.findFocus() == null) composeIn(target)?.requestFocus()
            if (target === compose.activity.window.decorView) {
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            } else {
                target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    /** What the focused item says: its text, or its description. */
    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.let { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
    }.orEmpty()

    private fun assertFocused(label: String) = assertTrue("Focus is on \"${focused()}\", not $label", focused().startsWith(label))

    private fun topWindow(): View? = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(global.getMethod("getInstance").invoke(null)) as List<*>
        views.filterIsInstance<View>().lastOrNull { it.isAttachedToWindow && it.visibility == View.VISIBLE }
    }.getOrNull()

    private fun composeIn(v: View): View? = if (v is ViewRootForTest) v
        else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { composeIn(g.getChildAt(it)) } }
}
