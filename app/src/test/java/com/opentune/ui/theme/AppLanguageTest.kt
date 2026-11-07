package com.opentune.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.opentune.i18n.Tr
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppLanguageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun english() = Tr.use(compose.activity, "en")

    /** The app's own Text shows [english] in [tag]'s words, read from that language's file. */
    private fun language(tag: String, expected: String, english: String = "Home") {
        Tr.use(compose.activity, tag)
        compose.setContent { Text(english) }
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
    @Test fun hindiFillsTheNumberIntoItsOwnSentence() {
        Tr.use(compose.activity, "hi")
        compose.setContent { Text(Tr.f("{0} of {1} songs downloaded", 3, 10)) }
        compose.onNodeWithText("10 में से 3 गाने डाउनलोड हुए").assertExists()
    }
    @Test fun englishAndUntranslatedWordsStayAsWritten() {
        Tr.use(compose.activity, "es")
        compose.setContent { Text("A song called Home Sweet Home") }
        compose.onNodeWithText("A song called Home Sweet Home").assertExists()
    }

    /** Every language offered in the app is one Android is told the app has, and the other way round. */
    @Test fun theListMatchesTheLocaleConfig() {
        val config = java.io.File("src/main/res/xml/locales_config.xml").readText()
        val declared = Regex("android:name=\"([^\"]+)\"").findAll(config).map { it.groupValues[1] }.toSet()
        val offered = AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.map { it.tag }.toSet()
        org.junit.Assert.assertEquals(declared, offered)
    }
}
