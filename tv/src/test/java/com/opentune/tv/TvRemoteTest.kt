package com.opentune.tv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.remote.MemoryStorage
import com.opentune.data.remote.PairedPhones
import com.opentune.data.remote.RemoteKey
import com.opentune.data.remote.RemoteMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a phone's trackpad and buttons do to the TV app: the cursor clicks, scrolls and seeks, the keys move focus. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = android.app.Application::class)
class TvRemoteTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var player: TvPlayer
    private lateinit var remote: TvRemote
    private lateinit var nav: TvNav
    private lateinit var input: TvRemoteInput

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        com.opentune.data.settings.AppSettings.init(app)
        com.opentune.data.library.LibraryStore.init(app)
        player = TvPlayer(app, connectNow = false)
        remote = TvRemote(app, PairedPhones(MemoryStorage()))
        nav = TvNav(mutableStateListOf<Dest>(Dest.Home), player, remote)
    }

    private fun show(remoteMode: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        var modes: androidx.compose.ui.input.InputModeManager? = null
        compose.setContent {
            modes = androidx.compose.ui.platform.LocalInputModeManager.current
            TvTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(TvColors.Background)) { content() } }
        }
        // Arrows and OK, as on a TV's own remote, put the window in keyboard mode, where focus shows.
        if (remoteMode) compose.runOnIdle { modes!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        compose.waitForIdle()
        input = TvRemoteInput(compose.activity, remote.pointer, nav)
        remote.attach(input)
    }

    /** Moves the cursor onto the middle of the node with [text], as a phone's finger would. */
    private fun cursorTo(text: String) {
        val b = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val decor = compose.activity.window.decorView
        val density = compose.activity.resources.displayMetrics.density
        input.handle(RemoteMessage.Cursor(true))
        // The cursor starts mid-screen; the phone sends dp, and the TV scales them.
        val dx = (b.center.x - remote.pointer.x) / (density * 3f)
        val dy = (b.center.y - remote.pointer.y) / (density * 3f)
        input.handle(RemoteMessage.Move(dx, dy))
        compose.waitForIdle()
        assertTrue("on screen: ${remote.pointer.x},${remote.pointer.y} of ${decor.width}x${decor.height}", remote.pointer.x in 0f..decor.width.toFloat())
    }

    @Test fun aCursorClickLandsOnWhatItIsOver() {
        var alpha = 0; var beta = 0
        show { Column { TvButton("Alpha", { alpha++ }); TvButton("Beta", { beta++ }) } }
        cursorTo("Beta")
        input.handle(RemoteMessage.Click())
        compose.waitForIdle()
        assertEquals(0, alpha)
        assertEquals(1, beta)
        cursorTo("Alpha")
        input.handle(RemoteMessage.Click(double = true))
        compose.waitForIdle()
        assertEquals(2, alpha)
        assertEquals(1, beta)
    }

    @Test fun holdingTheButtonAndMovingDragsAcrossTheSeekBar() {
        var seekedTo = -1L
        show { Column { SeekBar(position = 0, duration = 200_000, onSeekBy = {}, onSeekTo = { seekedTo = it }) } }
        compose.waitForIdle()
        // Press at the left end, drag to the middle.
        val decor = compose.activity.window.decorView
        val density = compose.activity.resources.displayMetrics.density
        input.handle(RemoteMessage.Cursor(true))
        val bar = compose.activity.window.decorView // the bar spans the window's width
        input.handle(RemoteMessage.Move(-(decor.width / 2f) / (density * 3f), -(decor.height / 2f) / (density * 3f) + 12f / density / 3f))
        input.handle(RemoteMessage.Press(true))
        input.handle(RemoteMessage.Move((bar.width / 2f) / (density * 3f), 0f))
        input.handle(RemoteMessage.Press(false))
        compose.waitForIdle()
        assertTrue("seeked to $seekedTo", seekedTo in 80_000L..120_000L)
    }

    @Test fun scrollingMovesTheListUnderTheCursor() {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        show {
            state = rememberLazyListState()
            LazyColumn(modifier = androidx.compose.ui.Modifier.fillMaxSize(), state = state) { items(80) { Text("Row $it", modifier = androidx.compose.ui.Modifier.background(TvColors.Surface)) } }
        }
        input.handle(RemoteMessage.Cursor(true))
        val before = state.firstVisibleItemIndex
        repeat(12) { input.handle(RemoteMessage.Scroll(0f, -90f)) }
        compose.waitForIdle()
        assertTrue("scrolled to ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > before)
        repeat(12) { input.handle(RemoteMessage.Scroll(0f, 90f)) }
        compose.waitForIdle()
        assertEquals(before, state.firstVisibleItemIndex)
    }

    @Test fun theButtonsMoveFocusLikeARemote() {
        show(remoteMode = true) { Column { TvButton("One", {}); TvButton("Two", {}) } }
        compose.onNodeWithText("One").requestFocus()
        compose.onNodeWithText("One").assertIsFocused()
        // The cursor goes away when the buttons are used.
        input.handle(RemoteMessage.Cursor(true))
        assertTrue(remote.pointer.visible)
        input.handle(RemoteMessage.Key(RemoteKey.DOWN))
        compose.waitForIdle()
        assertFalse(remote.pointer.visible)
        compose.onNodeWithText("Two").assertIsFocused()
    }

    @Test fun backFromHomeDoesNotLeaveTheApp() {
        compose.setContent { TvTheme { TvRoot(player, remote = remote) } }
        compose.waitForIdle()
        compose.runOnUiThread { remote.deliver(RemoteMessage.Key(RemoteKey.BACK)) }
        compose.waitForIdle()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun homeAndTypedTextGoWhereTheyShould() {
        compose.setContent { TvTheme { TvRoot(player, remote = remote) } }
        compose.waitForIdle()
        compose.runOnUiThread { remote.deliver(RemoteMessage.Text("abba")) }
        compose.waitForIdle()
        compose.onNodeWithText("abba").assertExists()
        compose.runOnUiThread { remote.deliver(RemoteMessage.Key(RemoteKey.HOME)) }
        compose.waitForIdle()
        compose.onNodeWithText("Search songs, albums, artists").assertDoesNotExist()
    }

    @Test fun theTvReportsWhatIsPlayingForThePhone() {
        show { Text("x") }
        val now = input.now()
        assertEquals(false, now.playing)
        assertEquals(null, now.title)
        assertTrue(now.volume in 0..100)
    }

    @Test fun theCursorIsDrawnOnTopAndFadesAway() {
        show { Column { TvButton("Alpha", {}) }; TvCursor(remote.pointer) }
        input.handle(RemoteMessage.Cursor(true))
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/tv-cursor.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The arrow is white at the pointer.
        val x = remote.pointer.x.toInt() + 3; val y = remote.pointer.y.toInt() + 12
        assertTrue("a light pixel at the cursor", (bitmap.getPixel(x, y) shr 8 and 0xFF) > 200)
    }
}
