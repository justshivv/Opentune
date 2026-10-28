package com.opentune.ui.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.*
import com.opentune.ui.LyricsState
import com.opentune.ui.player.*
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = android.app.Application::class)
class TvPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var plays = 0
    private var seeks = 0L
    private var lyrics = 0
    private var queue = 0

    @Before fun showPlayer() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        val song = Song("tv", "A song on television", "Artist", null, "3:00")
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                Surface(Modifier.fillMaxSize()) {
                    LandscapePlayer(
                        PlayerUiState(song, false, false, true, false, 0, 180_000,
                            LyricsState.NotFound, listOf(song), 0, emptyList(), SoundSettings(), ThemeSettings(),
                            InterfaceSettings(reduceAnimation = true, lyricsPreview = false)),
                        position = { 30_000 }, lyricsPosition = { 30_000 },
                        actions = PlayerActions({ plays++ }, {}, {}, {}, {}, { seeks = it }, {}, {}, {}),
                        liked = false, device = "Living room TV", onLike = {}, onMore = {}, onSignal = {},
                        onLyrics = { lyrics++ }, onQueue = { queue++ }, showLines = false,
                    )
                }
            }
        }
    }

    @Test fun playerStartsFocusedAndOkPlays() {
        compose.onNodeWithContentDescription("Play").assertIsFocused().assertIsDisplayed()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, plays) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("Next").assertIsFocused()
    }

    @Test fun seekWorksWithArrowsAndLyricsAndQueueDoNotNeedPreviewOrGestures() {
        val seek = compose.onNodeWithContentDescription("Playback position. Drag away from the bar for fine scrubbing.")
        seek.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(40_000L, seeks) }
        for (label in listOf("Lyrics", "Queue")) {
            compose.onNodeWithContentDescription(label).assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        }
        compose.runOnIdle { assertEquals(1, lyrics); assertEquals(1, queue) }
        compose.onNodeWithContentDescription("Media volume").assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
    }

    @Test fun mediaKeysDoNotStealDpadOrBack() {
        assertEquals(TvMediaAction.NEXT, tvMediaAction(android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(TvMediaAction.TOGGLE, tvMediaAction(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertNull(tvMediaAction(android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(tvMediaAction(android.view.KeyEvent.KEYCODE_BACK))
    }
}
