package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccountMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun signedOutItOffersSignInAndSettings() {
        var went = ""
        val nav = AccountMenuNav(addAccount = { went = "login" }, manageAccounts = { went = "manage" }, settings = { went = "settings" })
        compose.setContent { MaterialTheme(darkColorScheme()) { AccountMenu(nav, onDismiss = {}) } }
        compose.onNodeWithText("Account").assertExists()
        compose.onNodeWithText("Manage accounts").assertDoesNotExist()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals("login", went)
    }

    @Test fun settingsLeadsToSettings() {
        var went = ""
        val nav = AccountMenuNav(addAccount = { went = "login" }, manageAccounts = { went = "manage" }, settings = { went = "settings" })
        compose.setContent { MaterialTheme(darkColorScheme()) { AccountMenu(nav, onDismiss = {}) } }
        compose.onNodeWithText("Settings").performClick()
        assertEquals("settings", went)
    }
}
