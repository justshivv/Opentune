package com.opentune.ui.library

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.history.PlayRecord
import com.opentune.data.library.SmartMatch
import com.opentune.data.library.SmartPlaylist
import com.opentune.data.library.SmartPlaylists
import com.opentune.data.library.SmartRule
import com.opentune.data.library.SmartRules
import com.opentune.data.library.SmartSort
import com.opentune.data.library.SmartSource
import com.opentune.data.library.SongRef
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PageLayout
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The smart playlist's page and its rule editor, as pictures in build/screenshots (invented songs only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SmartPlaylistScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val actions = SongActions(null, false, { _, _, _, _ -> }, {}, {})
    private val day = 24L * 60 * 60 * 1000

    private val songs = listOf(
        "Paper Lanterns" to "Moss Lantern", "Low Tide Static" to "Tide Pool Choir", "Velvet Arcade" to "The Quiet Orchards",
        "Northbound Glass" to "Moss Lantern", "Small Hours" to "Juniper Vale", "Citrus Window" to "Neon Paddock",
        "Harbour Lights" to "Glass Harbour", "Slow Comet" to "Juniper Vale", "Borrowed Summer" to "Tide Pool Choir",
        "Fern Radio" to "The Quiet Orchards", "Copper Rain" to "Neon Paddock", "Last Ferry" to "Glass Harbour",
    ).mapIndexed { i, (t, a) -> SongRef("s$i", t, a, durationText = "${2 + i % 3}:${(17 + i * 7) % 60}".padEnd(4, '0')) }

    private val onRepeat = SmartPlaylist("p1", "On repeat", SmartPlaylists.presets.first().second, createdAt = 1)

    /** Plays over the last weeks: the first songs most, so most played reads in order. */
    private val facts: SmartPlaylists.Facts get() {
        val now = System.currentTimeMillis()
        val plays = songs.flatMapIndexed { i, s -> List(12 - i) { k -> PlayRecord(s.videoId, s.title, s.artist, durationText = s.durationText, playedAt = now - (k * 2 + i) * day) } }
            .sortedByDescending { it.playedAt }
        return SmartPlaylists.Facts(plays, liked = songs.filterIndexed { i, _ -> i % 3 == 0 }, downloads = emptyMap())
    }

    @Before fun settings() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { it.copy(reduceBlur = true) }
    }

    @After fun reset() {
        AppSettings.updateUi { it.copy(pageLayout = PageLayout.CLASSIC, reduceBlur = false) }
    }

    private fun poster() = AppSettings.updateUi { it.copy(pageLayout = PageLayout.POSTER) }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun page(name: String, dark: Boolean, playlist: SmartPlaylist = onRepeat, refs: List<SongRef>? = songs) {
        show(dark) { SmartPlaylistPage(playlist, refs, PaddingValues(), actions, {}, {}, {}, {}) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        save(name)
    }

    @Test fun pageDark() = page("smart-page-dark", dark = true)

    @Test fun pageLight() = page("smart-page-light", dark = false)

    @Test fun pagePoster() {
        poster()
        page("smart-page-poster-dark", dark = true)
    }

    @Test fun pagePosterLight() {
        poster()
        page("smart-page-poster-light", dark = false)
    }

    @Test fun pageEmpty() = page(
        "smart-page-empty-light", dark = false,
        playlist = SmartPlaylist("p2", "Forgotten favourites", SmartPlaylists.presets[1].second, createdAt = 1), refs = emptyList(),
    )

    @Test fun pageLoading() = page("smart-page-loading-dark", dark = true, refs = null)

    private fun editor(name: String, dark: Boolean, existing: SmartPlaylist?) {
        show(dark) { SmartEditorPage(existing, facts, PaddingValues(), {}, { _, _ -> }, if (existing == null) null else ({})) }
        // The count is worked out off the main thread.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("match", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        save(name)
    }

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun editorNewDark() = editor("smart-editor-new-dark", dark = true, existing = null)

    private val mixed = SmartPlaylist("p3", "Moss and tide", SmartRules(
        SmartSource.EVERYTHING, SmartMatch.ANY,
        listOf(SmartRule.Artist("Moss Lantern"), SmartRule.Artist("Tide Pool Choir"), SmartRule.Plays(5, 30)),
        SmartSort.RECENTLY_PLAYED, 100,
    ), createdAt = 1)

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun editorExistingLight() = editor("smart-editor-edit-light", dark = false, existing = mixed)

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun editorPoster() {
        poster()
        editor("smart-editor-poster-light", dark = false, existing = mixed)
    }

    @Test fun editorProblem() = editor("smart-editor-problem-dark", dark = true, existing = SmartPlaylist("p4", "Never", SmartRules(
        SmartSource.HISTORY, SmartMatch.ALL, listOf(SmartRule.Plays(1, 30), SmartRule.NotPlayedFor(90)),
    ), createdAt = 1))

    @Test fun ruleKindsSheet() {
        editor("smart-editor-before-sheet", dark = true, existing = onRepeat)
        compose.onNodeWithText("Add a rule").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Play count")).fetchSemanticsNodes().isNotEmpty())
        save("smart-sheet-kinds-dark")
    }

    @Test fun ruleValuesSheet() {
        editor("smart-editor-before-sheet-light", dark = false, existing = onRepeat)
        compose.onNodeWithText("Played 3+ times in the last month").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("would match", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // Changing a value: the rule in words follows.
        compose.onNodeWithText("10 times").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Played 10+ times in the last month")).fetchSemanticsNodes().isNotEmpty())
        save("smart-sheet-values-light")
    }

    @Test fun artistSheet() {
        editor("smart-editor-before-artist", dark = true, existing = onRepeat)
        compose.onNodeWithText("Add a rule").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("Artist").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        // No name yet: the sheet says so on its button, and offers the artists played most.
        assertTrue(compose.onAllNodes(hasText("Pick an artist, or type part of a name")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText("Moss Lantern")).fetchSemanticsNodes().isNotEmpty())
        save("smart-sheet-artist-dark")
    }

    @Test fun pickingAnArtistAddsTheRule() {
        var saved: SmartRules? = null
        show(dark = false) { SmartEditorPage(onRepeat, facts, PaddingValues(), {}, { _, r -> saved = r }, {}) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("match", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add a rule").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("Artist").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("Juniper Vale").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Add rule").performClick()
        compose.mainClock.advanceTimeBy(4_000)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("By Juniper Vale")).fetchSemanticsNodes().isNotEmpty())
        // The sheet has gone, and stays gone.
        assertTrue(compose.onAllNodes(hasText("Artists you play")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf(SmartRule.Plays(3, 30), SmartRule.Artist("Juniper Vale")), saved?.conditions)
    }

    /** Every window on screen, the page and any sheet over it, drawn where each sits. */
    private fun save(name: String) {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = android.graphics.Canvas(bitmap)
            val rootAt = IntArray(2).also { root.getLocationOnScreen(it) }
            windows().forEach { v ->
                val at = IntArray(2).also { v.getLocationOnScreen(it) }
                canvas.save()
                canvas.translate((at[0] - rootAt[0]).toFloat(), (at[1] - rootAt[1]).toFloat())
                v.draw(canvas)
                canvas.restore()
            }
        }
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun windows(): List<View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(global.getMethod("getInstance").invoke(null)) as List<*>
        views.filterIsInstance<View>().filter { it.isAttachedToWindow && it.width > 0 }
    }.getOrNull().orEmpty().ifEmpty { listOf(compose.activity.window.decorView) }
}
