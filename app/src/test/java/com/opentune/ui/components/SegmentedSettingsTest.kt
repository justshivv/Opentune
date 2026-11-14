package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.SeekbarThumb
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class SegmentedSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowSettingsKeepFullLabelsAtLargeFontSizesAndExposeSelection() {
        AppSettings.init(compose.activity)
        val selected = mutableStateOf(SeekbarThumb.LINE)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                    PillSegmented(SeekbarThumb.entries, selected.value, { it.label },
                        { selected.value = it }, Modifier.width(280.dp))
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("While dragging", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse("Label layout: ${layouts.single().size}, constraints ${layouts.single().layoutInput.constraints}, lines ${layouts.single().lineCount}", layouts.single().hasVisualOverflow)
        compose.onNodeWithText("Line").assertIsSelected()
        compose.onNodeWithText("While dragging").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(SeekbarThumb.TOUCH, selected.value) }
    }
}
