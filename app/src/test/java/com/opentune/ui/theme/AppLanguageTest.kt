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
    private fun language(tag: String, expected: String, english: String = "Home") {
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val context = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) { Text(tr(english)) }
        }
        compose.onNodeWithText(expected).assertExists()
    }
    @Test fun arabicChromeUsesAndroidResources() = language("ar", "الرئيسية")
    @Test fun spanishChromeUsesAndroidResources() = language("es", "Inicio")
    @Test fun hindiChromeUsesAndroidResources() = language("hi", "होम")
    @Test fun punjabiChromeUsesAndroidResources() = language("pa", "ਮੁੱਖ ਪੰਨਾ")
    @Test fun teluguChromeUsesAndroidResources() = language("te", "హోమ్")
    @Test fun japaneseChromeUsesAndroidResources() = language("ja", "ホーム")
    @Test fun simplifiedChineseChromeUsesAndroidResources() = language("zh-CN", "首页")
    @Test fun urduChromeUsesAndroidResources() = language("ur", "ہوم")
    @Test fun traditionalChineseIsNotSimplified() = language("zh-TW", "首頁")
    @Test fun ukrainianChromeUsesAndroidResources() = language("uk", "Головна")
    @Test fun portugueseNoLongerFallsBackToEnglish() = language("pt", "Streaming sem perdas", english = "Lossless streaming")

    /** Every language offered in the app is one Android is told the app has, and the other way round. */
    @Test fun theListMatchesTheLocaleConfig() {
        val config = java.io.File("src/main/res/xml/locales_config.xml").readText()
        val declared = Regex("android:name=\"([^\"]+)\"").findAll(config).map { it.groupValues[1] }.toSet()
        val offered = AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.map { it.tag }.toSet()
        org.junit.Assert.assertEquals(declared, offered)
    }
}
