package com.opentune.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.components.PlayerMorph
import com.opentune.ui.components.PlayerSheet
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The open player card keeps the mini player's face over it, invisible, for the
 * close. Its play and next buttons sat in the middle of the right edge at full
 * size and took the taps meant for the player there (issue #77: the "…" menu).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp")
class SheetFaceTapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun clear() { PlayerMorph.miniBar = null }

    @Test fun theOpenPlayerGetsTapsUnderTheHiddenMiniPlayerFace() {
        AppSettings.init(ApplicationProvider.getApplicationContext<Application>())
        PlayerMorph.miniBar = Rect(16f, 1300f, 784f, 1430f)
        var mores = 0
        var plays = 0
        var nexts = 0
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerSheet(
                    open = true,
                    onSettled = {},
                    face = {
                        MiniPlayer(
                            Song("s", "Track", "Artist", null), isPlaying = true, isBuffering = false, hasNext = true,
                            progress = { 0.3f }, onTogglePlay = { plays++ }, onNext = { nexts++ }, onPrevious = {}, onClick = {},
                            modifier = Modifier.fillMaxSize(), replica = true,
                        )
                    },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // Where the player's "…" sits on a tall screen: the middle of the right edge.
                        Box(Modifier.align(Alignment.CenterEnd).size(46.dp).testTag("more").clickable { mores++ })
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more").performClick()
        compose.runOnIdle {
            assertEquals(1, mores)
            assertEquals(0, plays + nexts)
        }
    }
}
