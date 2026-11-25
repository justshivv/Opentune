package com.opentune.ui.theme

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.data.settings.liteModeApplied
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Material hands its colour scheme down as one fixed object, and a new one composes everything
 * under the theme again, skipping nothing. These keep the themes from handing down a new but
 * equal scheme (which rebuilt the whole player at each play or pause, and the whole app at each
 * new song), while a real change of colours still fades where frames have the time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SteadySchemeTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun settings() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { liteModeApplied(it, false).copy(reduceAnimation = false) }
    }

    @After fun reset() {
        AppSettings.updateUi { liteModeApplied(it, false).copy(reduceAnimation = false) }
    }

    /** Composed once per pass that reaches it; skipped when nothing it reads has changed. */
    @Composable private fun Leaf(count: IntArray) {
        SideEffect { count[0]++ }
    }

    @Test fun appThemeComposedAgainLeavesThePageAlone() {
        val tick = mutableIntStateOf(0)
        val leaf = intArrayOf(0)
        compose.setContent {
            // The theme's caller composes again (a new song, say) with the same colours.
            val t = tick.intValue
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false, colorFromArtwork = false), artworkSeed = Color(0xFF000000 + t)) {
                Leaf(leaf)
            }
        }
        compose.waitForIdle()
        val before = leaf[0]
        repeat(5) {
            compose.runOnIdle { tick.intValue++ }
            compose.waitForIdle()
        }
        assertEquals("the page under the theme was composed again", before, leaf[0])
    }

    @Test fun playerThemeComposedAgainLeavesThePlayerAlone() {
        val tick = mutableIntStateOf(0)
        val leaf = intArrayOf(0)
        compose.setContent {
            val t = tick.intValue
            // The content changes (as the player's does with play, pause and buffering), the colours don't.
            PlayerTheme(seed = Color(0xFF3355AA), settings = ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                SideEffect { t.hashCode() }
                Leaf(leaf)
            }
        }
        compose.waitForIdle()
        val before = leaf[0]
        repeat(5) {
            compose.runOnIdle { tick.intValue++ }
            compose.waitForIdle()
        }
        assertEquals("the player under its theme was composed again", before, leaf[0])
    }

    @Test fun newColoursStillFade() {
        val red = darkColorScheme(primary = Color(0xFFCC2020), background = Color(0xFF200000))
        val blue = darkColorScheme(primary = Color(0xFF2040CC), background = Color(0xFF000020))
        val scheme = mutableStateOf(red)
        val seen = mutableListOf<Color>()
        compose.setContent {
            MaterialTheme(colorScheme = scheme.value.animated()) {
                val p = MaterialTheme.colorScheme.primary
                SideEffect { seen += p }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { scheme.value = blue }
        // Each frame of the fade composes the content once; idle once the fade is done.
        compose.waitForIdle()
        val distinct = seen.distinct()
        assertEquals(red.primary, distinct.first())
        assertEquals(blue.primary, distinct.last())
        assertTrue("it faded through colours in between: $distinct", distinct.size > 5)
        // No jumps: each frame moves only a little of the way.
        val steps = distinct.zipWithNext { a, b -> kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.blue - b.blue) }
        assertTrue("a jump in the fade: $steps", steps.max() < 0.25f)
    }

    @Test fun liteModeChangesColoursAtOnce() {
        AppSettings.updateUi { liteModeApplied(it, true) }
        val red = darkColorScheme(primary = Color(0xFFCC2020))
        val blue = darkColorScheme(primary = Color(0xFF2040CC))
        val scheme = mutableStateOf(red)
        val seen = mutableListOf<Color>()
        compose.setContent {
            MaterialTheme(colorScheme = scheme.value.animated()) {
                val p = MaterialTheme.colorScheme.primary
                SideEffect { seen += p }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { scheme.value = blue }
        compose.waitForIdle()
        assertEquals(listOf(red.primary, blue.primary), seen.distinct())
    }

    @Test fun equalSchemesAreOneObject() {
        val a = darkColorScheme(primary = Color(0xFFCC2020))
        val b = a.copy()
        assertTrue(a.sameColorsAs(b))
        assertNotEquals(false, a.sameColorsAs(a))
        assertTrue(!a.sameColorsAs(a.copy(surfaceContainerHigh = Color.Red)))
        assertTrue(!a.sameColorsAs(a.copy(onTertiaryFixedVariant = Color.Red)))
    }
}
