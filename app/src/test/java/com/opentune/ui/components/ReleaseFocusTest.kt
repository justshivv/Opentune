package com.opentune.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A text field focused when the app is left doesn't take focus (and the keyboard) back on return. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReleaseFocusTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun leavingTheAppLetsGoOfTheFieldUntilItIsTapped() {
        val field = FocusRequester()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        compose.setContent {
            var text by remember { mutableStateOf("") }
            Box(rememberLeaveFocusGuard()) {
                BasicTextField(text, { text = it }, Modifier.testTag("field").focusRequester(field))
            }
        }
        compose.runOnIdle { field.requestFocus() }
        compose.onNodeWithTag("field").assertIsFocused()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("field").assertIsNotFocused()
        // Still not, once the moment after coming back has passed.
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("field").assertIsNotFocused()
        // A tap focuses it as ever.
        compose.onNodeWithTag("field").performClick()
        compose.onNodeWithTag("field").assertIsFocused()
    }
}
