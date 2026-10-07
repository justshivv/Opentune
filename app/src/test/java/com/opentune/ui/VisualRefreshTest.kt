package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.model.BrowseType
import com.opentune.data.model.Song
import com.opentune.data.settings.AppearancePreset
import com.opentune.data.settings.MotionProfile
import com.opentune.ui.settings.AppearancePresets
import com.opentune.ui.settings.MotionProfiles
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.components.ItemCard
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.home.RecentListeningCard
import com.opentune.ui.player.MiniPlayer
import com.opentune.ui.player.PlayerControls
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native component previews and dock/mini-player interaction checks. No music API calls. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class VisualRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val song = Song("preview", "After Hours", "The Midnight", "https://preview.invalid/art", "4:12")
    private val tabs = listOf(
        ChromeTab("Home", Icons.Outlined.Home, Icons.Rounded.Home),
        ChromeTab("Explore", Icons.Outlined.Explore, Icons.Rounded.Explore),
        ChromeTab("Podcasts", Icons.Outlined.Podcasts, Icons.Rounded.Podcasts),
        ChromeTab("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    )

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before fun setUp() {
        AppSettings.init(compose.activity)
        AppSettings.updateUi { it.copy(reduceAnimation = true, reduceBlur = true, liquidGlass = false) }
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(37, 25, 77))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.rgb(240, 112, 101)
            drawCircle(128f, 106f, 75f, paint)
            paint.color = android.graphics.Color.rgb(120, 92, 185)
            drawRect(0f, 152f, 256f, 256f, paint)
            paint.color = android.graphics.Color.rgb(52, 38, 88)
            for (y in 166..250 step 14) drawRect(0f, y.toFloat(), 256f, y + 4f, paint)
        }
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ it is String && it.startsWith("https://preview.invalid/") }, android.graphics.drawable.BitmapDrawable(compose.activity.resources, bitmap).asImage())
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.coroutineContext(Dispatchers.Unconfined).build())
    }

    @Composable
    private fun Preview(dark: Boolean, fontScale: Float = 1f) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            OpenTuneTheme(ThemeSettings(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, colorFromArtwork = false)) {
                Surface {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 184.dp)) {
                            PageHeader("Your soundtrack", subtitle = "Good evening")
                            RecentListeningCard(song, {}, {})
                            SectionHeader("Made for your mood", subtitle = "A little discovery")
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                                item { ItemCard("Night drive", "A little after dark", song.thumbnailUrl, BrowseType.PLAYLIST, {}, width = 160.dp) }
                                item { ItemCard("Slow mornings", "Start on a softer note", song.thumbnailUrl, BrowseType.PLAYLIST, {}, width = 160.dp) }
                            }
                            PlayerControls(true, false, true, {}, {}, {}, animate = false)
                        }
                        BottomChrome(false, tabs, 0, {}, {}, false, {}, mini = { folded, modifier ->
                            MiniPlayer(song, true, false, true, { 0.42f }, {}, {}, {}, {}, modifier, folded)
                        }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), reduceMotion = true)
                    }
                }
            }
        }
    }

    @Test fun darkPreview() {
        compose.setContent { Preview(dark = true) }
        compose.onNodeWithTag("dock:Search").assertIsDisplayed()
        save("dark")
    }

    @Test fun lightPreview() {
        compose.setContent { Preview(dark = false) }
        save("light")
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp-xhdpi")
    fun compactPreviewWithLargeText() {
        compose.setContent { Preview(dark = true, fontScale = 1.4f) }
        tabs.forEach { compose.onNodeWithTag("dock:${it.label}").assertIsDisplayed() }
        compose.onNodeWithTag("dock:Search").assertIsDisplayed()
        save("compact-large-text")
    }

    @Test fun searchAndTabsShareOneIndicatorInRtlWithReducedMotion() {
        var selected by mutableIntStateOf(0)
        var search by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    BottomChrome(false, tabs, if (search) null else selected, { selected = it; search = false }, {}, search, { search = true }, null, reduceMotion = true)
                }
            }
        }
        listOf("Search", "Library", "Home").forEach { label ->
            compose.onNodeWithTag("dock:$label").performClick()
            compose.onNodeWithTag("dock:$label").assertIsSelected()
            val target = compose.onNodeWithTag("dock:$label").fetchSemanticsNode().boundsInRoot
            val indicator = compose.onNodeWithTag("dockIndicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(target.center.x, indicator.center.x, 1f)
            assertEquals(target.width, indicator.width, 1f)
        }
    }

    @Test fun rapidTabChangesSettleWithinTheSelectedSlot() {
        var selected by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { BottomChrome(false, tabs, selected, { selected = it }, {}, false, {}, null) }
        }
        listOf(3, 1, 2, 0, 3).forEach { index ->
            compose.runOnIdle { selected = index }
            compose.mainClock.advanceTimeBy(32)
            val indicator = compose.onNodeWithTag("dockIndicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(indicator.width > 0f)
        }
        compose.mainClock.advanceTimeBy(2_000)
        val target = compose.onNodeWithTag("dock:Library").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("dockIndicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(target.center.x, indicator.center.x, 1f)
    }

    @Test fun miniPlayerSwipeUsesCurrentCallbacksAndHonorsEndOfQueue() {
        var hasNext by mutableStateOf(false)
        var generation by mutableIntStateOf(1)
        val calls = mutableListOf<Int>()
        compose.setContent {
            val callbackVersion = generation
            MaterialTheme {
                MiniPlayer(song, false, false, hasNext, { 0.3f }, {}, { calls += callbackVersion }, {}, {}, Modifier.width(360.dp).height(CHROME_MINI_HEIGHT).testTag("mini"))
            }
        }
        fun swipe() = compose.onNodeWithTag("mini").performTouchInput { swipeLeft() }
        swipe()
        assertTrue(calls.isEmpty())
        compose.runOnIdle { hasNext = true }
        swipe()
        compose.runOnIdle { generation = 2 }
        swipe()
        assertEquals(listOf(1, 2), calls)
    }

    @Test fun customizationPreviewAndPresetPersistence() {
        AppSettings.applyAppearance(AppearancePreset.MIDNIGHT)
        AppSettings.applyMotion(MotionProfile.FLUID)
        compose.setContent {
            val state by AppSettings.state.collectAsState()
            OpenTuneTheme(state.theme) {
                Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        PageHeader("Make it yours")
                        AppearancePresets(state.theme, state.ui, AppSettings::applyAppearance)
                        MotionProfiles(state.ui, AppSettings::applyMotion)
                    }
                }
            }
        }
        compose.onNodeWithText("Calm").performClick()
        compose.onNodeWithText("Fold").performClick()
        compose.onNodeWithText("Expand").performClick()
        compose.onNodeWithTag("dock:Explore").performClick().assertIsSelected()
        compose.runOnIdle {
            assertTrue(AppSettings.ui.value.reduceAnimation)
            assertTrue(MotionProfile.CALM.matches(AppSettings.ui.value))
            val saved = AppSettings.state.value
            AppSettings.init(compose.activity)
            assertEquals(saved, AppSettings.state.value)
        }
        save("customization")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/reports/visual-refresh/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
