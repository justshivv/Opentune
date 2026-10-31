package com.opentune.ui.library

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Rule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Library's row of playlist tiles, the page of them all, and the app icon picker, as pictures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LibraryTilesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val nav = LibraryNav({}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun playlists() {
        LibraryStore.init(ApplicationProvider.getApplicationContext())
        listOf("Lub", "Old Hindi Romantic Songs", "Throwback Workout", "Late night drive", "Punjabi hits", "Ghazals").forEachIndexed { i, name ->
            LibraryStore.createPlaylist(name, Song("v$i", "Song $i", "Artist $i", null))
        }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
        save(name, topWindow() ?: compose.activity.window.decorView)
    }

    @Test fun libraryRow() {
        playlists()
        shoot("library-tiles") { LibraryScreen(PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), nav) }
    }

    @Test fun allPlaylists() {
        playlists()
        shoot("library-all-playlists") { AllPlaylistsScreen(PaddingValues(), nav, onBack = {}) }
    }

    /** The list view chosen in Settings: new and import, then each source's playlists, five until Show more. */
    @Test fun libraryList() {
        playlists()
        com.opentune.data.settings.AppSettings.init(ApplicationProvider.getApplicationContext())
        com.opentune.data.settings.AppSettings.updateUi { it.copy(playlistList = true) }
        try {
            compose.setContent {
                OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        LibraryScreen(PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), nav)
                    }
                }
            }
            compose.waitForIdle()
            compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(androidx.compose.ui.test.hasText("Show more (1)"))
            compose.onNodeWithText("View all").assertExists()
            org.junit.Assert.assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("On this phone")).fetchSemanticsNodes().isNotEmpty())
            val names = listOf("Lub", "Old Hindi Romantic Songs", "Throwback Workout", "Late night drive", "Punjabi hits", "Ghazals")
            fun shown() = names.count { compose.onAllNodes(androidx.compose.ui.test.hasText(it)).fetchSemanticsNodes().isNotEmpty() }
            org.junit.Assert.assertEquals("five before Show more", 5, shown())
            compose.onNodeWithText("Show more (1)").performClick()
            compose.waitForIdle()
            org.junit.Assert.assertEquals("all six after", 6, shown())
            save("library-list", topWindow() ?: compose.activity.window.decorView)
        } finally {
            com.opentune.data.settings.AppSettings.updateUi { it.copy(playlistList = false) }
        }
    }

    @Test fun allPlaylistsAsAList() {
        playlists()
        com.opentune.data.settings.AppSettings.init(ApplicationProvider.getApplicationContext())
        com.opentune.data.settings.AppSettings.updateUi { it.copy(playlistList = true) }
        try {
            shoot("library-all-playlists-list") { AllPlaylistsScreen(PaddingValues(), nav, onBack = {}) }
        } finally {
            com.opentune.data.settings.AppSettings.updateUi { it.copy(playlistList = false) }
        }
    }

    @Test fun appIcons() {
        shoot("app-icons") { com.opentune.ui.settings.AppIconDialog(onDismiss = {}) }
    }

    private fun topWindow(): View? = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(global.getMethod("getInstance").invoke(null)) as List<*>
        views.filterIsInstance<View>().lastOrNull { it.isAttachedToWindow }
    }.getOrNull()

    private fun save(name: String, view: View) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
