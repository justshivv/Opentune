package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.*
import com.opentune.ui.settings.DesignChooser
import com.opentune.ui.settings.DesignPreview
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class InterfaceDesignTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
    }

    @Test fun previewsShowAllDesignsInLightAndDark() {
        var style by mutableStateOf(InterfaceDesign.CURRENT)
        var dark by mutableStateOf(false)
        var player by mutableStateOf(false)
        compose.setContent {
            DesignPreview(InterfaceSettings(design = DesignOptions(style, DesignMotion.FLUID), reduceAnimation = true),
                ThemeSettings(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, pureBlack = false), player, Modifier.padding(12.dp))
        }
        InterfaceDesign.entries.forEach { option ->
            listOf(false, true).forEach { night ->
                compose.runOnIdle { style = option; dark = night; player = false }
                compose.onNodeWithText("Your daily soundtrack").assertIsDisplayed()
                save("${option.name.lowercase()}-${if (night) "dark" else "light"}-home")
            }
            compose.runOnIdle { player = true }
            compose.onNodeWithText("NOW PLAYING").assertIsDisplayed()
            save("${option.name.lowercase()}-player-preview")
        }
    }

    @Test fun cancelLeavesPreferencesUntouched() {
        var closed = false
        var applied: DesignOptions? = null
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) { DesignChooser(InterfaceSettings(), ThemeSettings(), { closed = true }, { applied = it }) }
        }
        compose.onNodeWithText("Flow", substring = false).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(closed)
        assertNull(applied)
    }

    @Test fun draftSurvivesRestorationAndAppliesExplicitly() {
        var applied: DesignOptions? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            OpenTuneTheme(ThemeSettings()) { DesignChooser(InterfaceSettings(), ThemeSettings(), {}, { applied = it }) }
        }
        compose.onNodeWithText("Editorial", substring = false).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Editorial", substring = false).assertIsSelected()
        assertNull(applied)
        compose.onNodeWithText("Apply design").performClick()
        assertEquals(InterfaceDesign.EDITORIAL, applied!!.style)
    }

    @Test fun dockRetainsEveryDestinationIncludingSearchInRtl() {
        var selected: Int? = 0
        var search = false
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                OpenTuneTheme(ThemeSettings(), ui = InterfaceSettings(design = DesignOptions(InterfaceDesign.FLOW, DesignMotion.EXPRESSIVE), reduceAnimation = true)) {
                    DesignChrome(true, listOf(ChromeTab("Home", Icons.Outlined.Home), ChromeTab("Explore", Icons.Outlined.Explore),
                        ChromeTab("Podcasts", Icons.Outlined.Podcasts), ChromeTab("Library", Icons.Outlined.LibraryMusic)), selected,
                        { selected = it }, search, { search = true }, null, Modifier.width(320.dp))
                }
            }
        }
        compose.onNodeWithContentDescription("Library").performClick()
        assertEquals(3, selected)
        compose.onNodeWithContentDescription("Podcasts").performClick()
        assertEquals(2, selected)
        compose.onNodeWithContentDescription("Explore").performClick()
        assertEquals(1, selected)
        compose.onNodeWithContentDescription("Home").performClick()
        assertEquals(0, selected)
        compose.onNodeWithContentDescription("Search").performClick()
        assertTrue(search)
        save("dock-compact-rtl")
    }

    @Test fun narrowRtlPreviewSupportsLargeTextAndCompactDensity() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                DesignPreview(InterfaceSettings(design = DesignOptions(InterfaceDesign.EDITORIAL, DesignMotion.CALM, DesignDensity.COMPACT), reduceAnimation = true),
                    ThemeSettings(mode = ThemeMode.LIGHT), false, Modifier.width(320.dp))
            }
        }
        compose.onNodeWithText("Play", substring = false).assertIsDisplayed()
        save("editorial-narrow-rtl-large-text")
    }

    @Test fun rapidDockChangesKeepEveryTouchTargetAvailableDuringMotion() {
        var motion by mutableStateOf(DesignMotion.FLUID)
        var selected by mutableIntStateOf(0)
        var folded by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenTuneTheme(ThemeSettings(), ui = InterfaceSettings(design = DesignOptions(InterfaceDesign.FLOW, motion))) {
                DesignChrome(folded, listOf(ChromeTab("Home", Icons.Outlined.Home), ChromeTab("Explore", Icons.Outlined.Explore),
                    ChromeTab("Podcasts", Icons.Outlined.Podcasts), ChromeTab("Library", Icons.Outlined.LibraryMusic)),
                    if (selected == 4) null else selected, { selected = it }, selected == 4, { selected = 4 }, null, Modifier.width(320.dp))
            }
        }
        listOf(DesignMotion.CALM, DesignMotion.FLUID, DesignMotion.EXPRESSIVE).forEach { profile ->
            compose.runOnIdle { motion = profile; folded = false }
            compose.mainClock.advanceTimeBy(800)
            listOf("Library", "Explore", "Search", "Home").forEach { label ->
                compose.onNodeWithContentDescription(label).performClick()
                compose.mainClock.advanceTimeBy(80)
                val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
                val minimum = 48 * compose.activity.resources.displayMetrics.density
                assertTrue("$label must keep a 48dp target while moving", bounds.width >= minimum - 1 && bounds.height >= minimum - 1)
            }
            compose.runOnIdle { folded = true }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithContentDescription("Library").assertExists()
        }
    }

    private fun save(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = File("build/screenshots/interface-design/$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
