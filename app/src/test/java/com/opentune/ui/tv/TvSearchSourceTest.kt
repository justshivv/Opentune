package com.opentune.ui.tv

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.ui.search.SearchSource
import com.opentune.ui.search.SourceBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = android.app.Application::class)
class TvSearchSourceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun everySearchSourceCanBeChosenWithOk() {
        var chosen = SearchSource.entries.first()
        compose.setContent {
            MaterialTheme {
                val input = LocalInputModeManager.current
                LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                CompositionLocalProvider(LocalIsTv provides true) {
                    SourceBar(chosen, { chosen = it })
                }
            }
        }
        SearchSource.entries.forEach { source ->
            compose.onNodeWithContentDescription(source.label).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(source, chosen) }
        }
    }
}
