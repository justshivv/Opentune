package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
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

    /** Over a busy light page, to build/screenshots/account-menu-light.png: the card must be solid. */
    @Test fun lightThemeCardIsSolid() {
        val nav = AccountMenuNav({}, {}, {})
        // A see-through surface colour, as the app's glass themes have, must still give a solid card.
        val scheme = androidx.compose.material3.lightColorScheme(
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0x66F3DDE4),
            background = androidx.compose.ui.graphics.Color(0xFFFFF4F6),
        )
        compose.setContent {
            MaterialTheme(scheme) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF7D9E2))) {
                    Column { repeat(20) { androidx.compose.material3.Text("Busy page line $it with words behind", style = MaterialTheme.typography.headlineSmall) } }
                }
                AccountMenu(nav, onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // The page under it, then the dialog's window over it, as the screen shows them.
        val page = compose.activity.window.decorView
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val image = android.graphics.Bitmap.createBitmap(page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        page.draw(canvas)
        dialog.draw(canvas)
        java.io.File("build/screenshots/account-menu-light.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Settings").assertExists()
    }
}
