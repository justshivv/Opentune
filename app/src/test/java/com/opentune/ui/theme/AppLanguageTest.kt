package com.opentune.ui.theme

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppLanguageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun language(tag: String, expected: String) {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val context = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) { Text(tr("Home")) }
        }
        compose.onNodeWithText(expected).assertExists()
    }
    @Test fun hindiChromeUsesAndroidResources() = language("hi", "होम")
    @Test fun punjabiChromeUsesAndroidResources() = language("pa", "ਮੁੱਖ ਪੰਨਾ")
}
