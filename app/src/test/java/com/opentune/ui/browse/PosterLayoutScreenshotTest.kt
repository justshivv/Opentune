package com.opentune.ui.browse

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.GlassStyle
import com.opentune.data.settings.PageLayout
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the Poster layout's album and artist headers to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class PosterLayoutScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun poster() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { it.copy(liquidGlass = false, reduceBlur = true, glassStyle = GlassStyle.FROSTED, pageLayout = PageLayout.POSTER, artistSignatures = false) }
    }

    private val actions = SongActions(null, false, { _, _, _, _ -> }, {}, {})
    private val songs = List(9) { Song("id$it", "Song $it", "Barasuara", null, durationText = "4:40") }
    private val page = Color(0xFF2D3540)

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(darkColorScheme(background = page, surface = page, onSurface = Color(0xFFECEFF3), onSurfaceVariant = Color(0xFFB4BCC6))) {
                androidx.compose.material3.Surface(color = page, contentColor = Color(0xFFECEFF3)) {
                    Box(Modifier.fillMaxSize().background(page)) { content() }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun album() = shoot("poster-album") {
        Column(Modifier.fillMaxWidth()) {
            CollectionHero(
                title = "Jalaran Sadrah",
                subtitle = "Barasuara",
                explicit = false,
                meta = playlistMeta(songs),
                thumbnailUrl = null,
                fallbackCover = { Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF9FB3C4), Color(0xFF55606C))))) },
                kind = "Album",
                buttons = { ListPlayRow(enabled = true, saved = false, onPlay = {}, onShuffle = {}, onSmartShuffle = {}, onSave = {}, modifier = Modifier.fillMaxWidth()) },
            )
        }
    }

    @Test fun artist() = shoot("poster-artist") {
        ArtistHero(
            browseId = "UC1",
            page = ArtistPage(songs, null, emptyList(), name = "Barasuara"),
            photo = null,
            listState = rememberLazyListState(),
            actions = actions,
        )
    }

    @Test fun pagePieces() = shoot("poster-page") {
        Column(Modifier.fillMaxWidth()) {
            com.opentune.ui.components.PageHeader("Downloads", onBack = {})
            com.opentune.ui.components.SectionHeader("Recently played", subtitle = "From the last week")
            com.opentune.ui.components.MenuRow(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.opentune.ui.theme.AppIcons.PlaylistPlay), "Play next") {}
            com.opentune.ui.components.MenuRow(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.opentune.ui.theme.AppIcons.QueueMusic), "Add to queue") {}
            com.opentune.ui.components.SectionHeader("Your playlists")
        }
    }

    @Test fun songMenu() {
        val song = Song("abc", "Antea", "Barasuara", null, albumId = "MPRE1", artistId = "UC1")
        compose.setContent {
            MaterialTheme(darkColorScheme(background = page, surface = page, surfaceContainerHigh = Color(0xFF39424D), onSurface = Color(0xFFECEFF3), onSurfaceVariant = Color(0xFFB4BCC6))) {
                androidx.compose.material3.Surface(color = page, contentColor = Color(0xFFECEFF3)) {
                    androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongMenu provides com.opentune.ui.components.SongMenuActions({}, {}, {}, {}, {})) {
                        com.opentune.ui.components.SongMenuSheet(song, onDismiss = {}, startExpanded = true)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val back = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        back.draw(canvas)
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        canvas.save()
        canvas.translate(0f, (back.height - sheet.height).toFloat())
        sheet.draw(canvas)
        canvas.restore()
        File(File("build/screenshots").apply { mkdirs() }, "poster-song-menu.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
