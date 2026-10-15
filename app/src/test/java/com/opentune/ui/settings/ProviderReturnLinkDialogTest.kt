package com.opentune.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi", application = android.app.Application::class)
class ProviderReturnLinkDialogTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    // A layout loop should fail promptly, rather than stalling each interaction for a minute.
    @Before fun limitIdleWait() { androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(10, java.util.concurrent.TimeUnit.SECONDS) }
    @After fun restoreIdleWait() { androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(60, java.util.concurrent.TimeUnit.SECONDS) }

    @Test fun emptyClipboardExplainsTheProblemInTheOpenDialog() {
        var submissions = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize())
                ProviderReturnLinkDialog("Waiting for the browser result", true, false,
                    { null }, { submissions++; false }, {}, {})
            }
        }
        compose.onNodeWithText("Paste").assertIsDisplayed().performClick()
        compose.onNodeWithText("Your clipboard has no text.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("provider-return-link").assertIsDisplayed()
        assertEquals(0, submissions)
    }

    @Test fun pastedLinkIsVisibleAndOnlySubmittedAfterVerify() {
        val callback = "opentune://provider-session?state=test&grant=example"
        val status = mutableStateOf("Waiting for the browser result")
        val submitted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize())
                ProviderReturnLinkDialog(status.value, true, false, { callback }, {
                    submitted += it; status.value = "Connected"; true
                }, {}, {})
            }
        }
        compose.onNodeWithText("Paste").performClick()
        compose.onNodeWithTag("provider-return-link").assertTextContains(callback)
        compose.onNodeWithText("Link pasted. Tap Verify link to continue.").assertIsDisplayed()
        assertEquals(0, submitted.size)
        compose.onNodeWithText("Verify link").performClick()
        assertEquals(listOf(callback), submitted)
        compose.onNodeWithText("Amazon Music connected").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test fun clipboardFailureStillAllowsManualEntryAndShowsRejection() {
        val submitted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize())
                ProviderReturnLinkDialog("Waiting for the browser result", true, false,
                    { throw SecurityException("clipboard unavailable") }, { submitted += it; false }, {}, {})
            }
        }
        compose.onNodeWithText("Paste").performClick()
        compose.onNodeWithText("Clipboard unavailable.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("provider-return-link").performTextInput("https://example.com/incomplete")
        compose.onNodeWithText("Verify link").performClick()
        assertEquals(listOf("https://example.com/incomplete"), submitted)
        compose.onNodeWithText("This link is incomplete", substring = true).assertIsDisplayed()
    }

    @Test fun expiredAttemptExplainsWhyVerificationIsDisabled() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize())
                ProviderReturnLinkDialog("Verification expired. Connect again.", false, false,
                    { null }, { false }, {}, {})
            }
        }
        compose.onNodeWithText("This verification has expired", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Verify link").assertIsNotEnabled()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
