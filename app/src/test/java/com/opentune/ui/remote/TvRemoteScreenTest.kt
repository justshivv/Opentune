package com.opentune.ui.remote

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.remote.RemoteKey
import com.opentune.data.remote.RemoteMessage
import com.opentune.data.settings.AppSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The phone's remote on a phone: real touches on the trackpad become messages, the buttons send keys, the two modes swap. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TvRemoteScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val sent = ArrayList<RemoteMessage>()
    private val now = RemoteMessage.Now("Echoes", "Pink Floyd", null, playing = true, positionMs = 90_000, durationMs = 1_400_000, volume = 35)

    @Before fun setUp() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { it.copy(remoteStartOnTrackpad = true, remoteNaturalScroll = true, remoteTapToClick = true, remoteVolumeKeys = true) }
    }

    private fun show() {
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Surface { RemotePad("OpenTune on Bravia", now, { sent += it }, PaddingValues(), onLeave = {}) }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File("build/screenshots/remote/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun opensOnTheTrackpadAndShowsTheCursorOnTheTv() {
        show()
        compose.onNodeWithText("Echoes").assertExists()
        compose.onNodeWithText("OpenTune on Bravia").assertExists()
        compose.onNodeWithContentDescription("Trackpad").assertExists()
        assertTrue(sent.contains(RemoteMessage.Cursor(true)))
        shot("trackpad")
    }

    @Test fun slidingAFingerMovesTheCursorAndATapClicks() {
        show()
        sent.clear()
        compose.onNodeWithContentDescription("Trackpad").performTouchInput { swipeRight(startX = left + 40f, endX = right - 40f, durationMillis = 300) }
        compose.waitForIdle()
        val moved = sent.filterIsInstance<RemoteMessage.Move>().sumOf { it.dx.toDouble() }
        assertTrue("moved right: $moved", moved > 50)
        assertEquals(0, sent.count { it is RemoteMessage.Click })
        sent.clear()
        compose.onNodeWithContentDescription("Trackpad").performClick()
        compose.waitForIdle()
        assertEquals(listOf<RemoteMessage>(RemoteMessage.Click()), sent)
    }

    @Test fun twoFingersScrollAndATwoFingerTapIsBack() {
        show()
        sent.clear()
        compose.onNodeWithContentDescription("Trackpad").performTouchInput {
            down(0, Offset(300f, 500f)); down(1, Offset(420f, 500f))
            repeat(10) { moveBy(0, Offset(0f, 30f)); moveBy(1, Offset(0f, 30f)) }
            up(0); up(1)
        }
        compose.waitForIdle()
        assertTrue("scrolled: ${sent.filterIsInstance<RemoteMessage.Scroll>().sumOf { it.dy.toDouble() }}", sent.filterIsInstance<RemoteMessage.Scroll>().sumOf { it.dy.toDouble() } > 20)
        assertEquals(0, sent.count { it is RemoteMessage.Move })
        sent.clear()
        compose.onNodeWithContentDescription("Trackpad").performTouchInput {
            down(0, Offset(300f, 500f)); down(1, Offset(420f, 500f)); up(0); up(1)
        }
        compose.waitForIdle()
        assertEquals(listOf<RemoteMessage>(RemoteMessage.Key(RemoteKey.BACK)), sent)
    }

    @Test fun theButtonsModeHasTheArrowsAndTheCursorGoesAway() {
        show()
        sent.clear()
        compose.onNodeWithContentDescription("Buttons mode").performClick()
        compose.waitForIdle()
        assertTrue(sent.contains(RemoteMessage.Cursor(false)))
        compose.onNodeWithContentDescription("OK").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Left").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertTrue(sent.contains(RemoteMessage.Key(RemoteKey.OK)))
        assertTrue(sent.contains(RemoteMessage.Key(RemoteKey.NEXT)))
        assertTrue(sent.contains(RemoteMessage.Key(RemoteKey.LEFT)))
        shot("buttons")
        // And back to the trackpad: the cursor returns.
        sent.clear()
        compose.onNodeWithContentDescription("Trackpad mode").performClick()
        compose.waitForIdle()
        assertTrue(sent.contains(RemoteMessage.Cursor(true)))
    }

    @Test fun theBarAlwaysHasBackHomePlayVolumeAndType() {
        show()
        sent.clear()
        compose.onNodeWithContentDescription("TV back").performClick()
        compose.onNodeWithContentDescription("TV home").performClick()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Mute").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf<RemoteMessage>(RemoteMessage.Key(RemoteKey.BACK), RemoteMessage.Key(RemoteKey.HOME), RemoteMessage.Key(RemoteKey.PLAY_PAUSE), RemoteMessage.Key(RemoteKey.MUTE)),
            sent,
        )
    }

    @Test fun theKeyboardOpensWhenTheTvsSearchFieldIsFocusedAndTypesIntoIt() {
        val keyboard = RemoteKeyboard { sent += it }
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                // Paused, so the progress bar's ticking doesn't keep the test from ever being idle.
                Surface { RemotePad("OpenTune on Bravia", now.copy(playing = false), { sent += it }, PaddingValues(), onLeave = {}, keyboard = keyboard) }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        // The TV's search field gets the focus, with "pink" in it.
        compose.runOnIdle { keyboard.onField(RemoteMessage.Field(true, "pink", "Search songs, albums, artists")) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists().assertTextContains("pink")
        sent.clear()
        compose.onNode(hasSetTextAction()).performTextInput(" floyd")
        compose.waitForIdle()
        assertEquals(RemoteMessage.Edit("pink floyd"), sent.filterIsInstance<RemoteMessage.Edit>().last())
        shot("typing")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertEquals(RemoteMessage.Edit("pink floyd", submit = true), sent.filterIsInstance<RemoteMessage.Edit>().last())
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        // Focused again, then the TV leaves Search: it opens, then closes by itself.
        compose.runOnIdle { keyboard.onField(RemoteMessage.Field(false)); keyboard.onField(RemoteMessage.Field(true, "")) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle { keyboard.onField(RemoteMessage.Field(false)) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun closedByHandTheTypeButtonOpensItAgainWhileTheFieldHasTheFocus() {
        val keyboard = RemoteKeyboard { sent += it }
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Surface { RemotePad("OpenTune on Bravia", now.copy(playing = false), { sent += it }, PaddingValues(), onLeave = {}, keyboard = keyboard) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { keyboard.onField(RemoteMessage.Field(true, "abba")) }
        compose.onNodeWithContentDescription("Close the keyboard").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Type").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists().assertTextContains("abba")
        compose.onNodeWithText("Search").performClick()
        compose.waitForIdle()
        assertEquals(RemoteMessage.Edit("abba", submit = true), sent.filterIsInstance<RemoteMessage.Edit>().single())
        assertTrue(sent.none { it is RemoteMessage.Text })
    }

    @Test fun holdingAnArrowRepeatsUntilLetGo() {
        show()
        compose.onNodeWithContentDescription("Buttons mode").performClick()
        compose.waitForIdle()
        sent.clear()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Right").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Right").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        val keys = sent.filterIsInstance<RemoteMessage.Key>().filter { it.key == RemoteKey.RIGHT }
        assertTrue("first press then repeats: ${keys.size}", keys.size >= 4)
        assertEquals(false, keys.first().repeat)
        assertTrue(keys.drop(1).all { it.repeat })
        val after = sent.size
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("nothing after the finger lifted", after, sent.size)
    }
}
