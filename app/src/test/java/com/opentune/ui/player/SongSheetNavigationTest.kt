package com.opentune.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.opentune.data.model.Song
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Opening the song sheet, stacking About song on it, closing the top one, and leaving by a View row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class SongSheetNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(true)
    private val artists = mutableListOf<String>()
    private var albums = 0
    private var streamInfo = 0

    private fun show(startOnAbout: Boolean = false) {
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                if (open) SongInfoSheets(
                    SongSheetFixtures.song, SongSheetFixtures.full,
                    SongSheetActions(
                        openArtist = { artists += it },
                        openAlbum = { albums++ },
                        streamInfo = { streamInfo++ },
                    ),
                    onDismiss = { open = false },
                    durationMs = 221_000,
                    startOnAbout = startOnAbout,
                )
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        settle()
    }

    @Test fun opensOnTheSong() {
        show()
        compose.onNodeWithText("View Nova Reyes").assertIsDisplayed()
        compose.onNodeWithText("View Tomas Ilvane").assertIsDisplayed()
        compose.onNodeWithText("View Harbour Lights").assertIsDisplayed()
        compose.onNodeWithText("Stream info").assertIsDisplayed()
        compose.onNodeWithText("General information").assertDoesNotExist()
    }

    @Test fun aboutStacksAndBackClosesTheTopFirst() {
        show()
        compose.onNodeWithText("About song").performClick()
        settle()
        compose.onNodeWithText("General information").assertIsDisplayed()
        // The song sheet is still there under it.
        compose.onNodeWithText("View Nova Reyes").assertExists()
        back()
        compose.onNodeWithText("General information").assertDoesNotExist()
        compose.onNodeWithText("View Nova Reyes").assertIsDisplayed()
        assertTrue("the song sheet stays", open)
        back()
        assertFalse("the second back closes the song sheet", open)
    }

    @Test fun dragDownClosesTheTopSheet() {
        show()
        compose.onNodeWithText("About song").performClick()
        settle()
        compose.onNodeWithContentDescription("Back to song").performTouchInput { swipe(center, center + Offset(0f, 900f), 250) }
        settle()
        compose.onNodeWithText("General information").assertDoesNotExist()
        assertTrue(open)
        compose.onNodeWithContentDescription("Close").performTouchInput { swipe(center, center + Offset(0f, 900f), 250) }
        settle()
        assertFalse(open)
    }

    @Test fun openedOnAboutGoesBackToTheSong() {
        show(startOnAbout = true)
        compose.onNodeWithText("General information").assertIsDisplayed()
        back()
        compose.onNodeWithText("View Nova Reyes").assertIsDisplayed()
        assertTrue(open)
    }

    @Test fun viewRowClosesTheSheetsAndOpensTheArtist() {
        show()
        compose.onNodeWithText("View Tomas Ilvane").performClick()
        settle()
        assertFalse("the sheets close first", open)
        assertEquals(listOf("Tomas Ilvane"), artists)
    }

    @Test fun albumRowOpensTheAlbum() {
        show()
        compose.onNodeWithText("View Harbour Lights").performClick()
        settle()
        assertFalse(open)
        assertEquals(1, albums)
    }

    @Test fun artistInAboutOpensTheArtist() {
        show(startOnAbout = true)
        // The By row's portrait opens the artist too, closing both sheets.
        val portrait = compose.onNode(androidx.compose.ui.test.hasText("Nova Reyes") and androidx.compose.ui.test.hasClickAction())
        repeat(2) {
            compose.onNode(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("General information")))
                .performTouchInput { swipe(Offset(centerX, bottom - 60f), Offset(centerX, top + 60f), 400) }
            settle()
        }
        portrait.assertIsDisplayed().performClick()
        settle()
        assertEquals(listOf("Nova Reyes"), artists)
        assertFalse(open)
    }

    @Test fun streamInfoOpensThePipeline() {
        show()
        compose.onNodeWithText("Stream info").performClick()
        settle()
        assertFalse(open)
        assertEquals(1, streamInfo)
    }

    /** What's found for a song is kept: opening the sheet again asks nothing again. */
    @Test fun infoIsKeptPerSong() {
        var asked = 0
        val sources = object : SongInfoSources {
            override suspend fun description(videoId: String): String? { asked++; return SongSheetFixtures.description }
            override suspend fun apple(song: Song, durationMs: Long) = null.also { asked++ }
            override suspend fun musicBrainz(song: Song, durationMs: Long) = null.also { asked++ }
            override suspend fun artistPhoto(song: Song, name: String): String? { asked++; return "https://img.test/$name" }
            override suspend fun wikipedia(song: Song) = null.also { asked++ }
            override suspend fun lastFm(song: Song) = null.also { asked++ }
            override suspend fun platformLinks(videoId: String): Map<String, String> { asked++; return mapOf("spotify" to "https://open.spotify.com/track/1") }
            override suspend fun deezer(song: Song, durationMs: Long): String? = null.also { asked++ }
            override fun artistGenre(artist: String): String? = "Indie Pop"
        }
        val store = SongInfoStore(sources, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val first = store.load(SongSheetFixtures.song, 221_000).value
        assertTrue(first.pending.isEmpty())
        assertEquals("Lantern Row Records", first.credits?.label)
        assertEquals("Indie Pop", first.genre)
        assertEquals(setOf("Nova Reyes", "Tomas Ilvane"), first.photos.keys)
        assertEquals(listOf(MusicService.SPOTIFY), first.links.map { it.service })
        val count = asked
        val again = store.load(SongSheetFixtures.song, 221_000).value
        assertEquals(count, asked)
        assertEquals(first, again)
        // The app's own links join what was found: YouTube Music for a YouTube song.
        assertEquals(listOf(MusicService.SPOTIFY, MusicService.YOUTUBE_MUSIC), serviceLinks(SongSheetFixtures.song, again).map { it.service })
    }

    @Test fun releaseDatesReadNaturally() {
        assertEquals("Oct 10, 2025", releaseText("2025-10-10", java.util.Locale.US))
        assertEquals("Nov 2019", releaseText("2019-11", java.util.Locale.US))
        assertEquals("2019", releaseText("2019", java.util.Locale.US))
    }
}
