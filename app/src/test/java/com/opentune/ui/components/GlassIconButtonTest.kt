package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.opentune.ui.theme.AppIcons
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GlassIconButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** With the account's photo drawn in it, the button still has its name for a screen reader. */
    @Test fun aButtonShowingAPictureIsStillNamed() {
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                GlassIconButton(ImageVector.vectorResource(AppIcons.Person), "Account and settings", {}) {
                    Box(Modifier.size(24.dp).background(Color.Red))
                }
            }
        }
        compose.onNodeWithContentDescription("Account and settings").assertHasClickAction()
    }

    @Test fun anAccountButtonInAPillIsNamed() {
        compose.setContent { MaterialTheme(darkColorScheme()) { AccountButton(onClick = {}, plain = true) } }
        compose.onNodeWithContentDescription("Settings").assertHasClickAction()
    }
}
