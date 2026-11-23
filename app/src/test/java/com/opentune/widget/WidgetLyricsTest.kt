package com.opentune.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.opentune.R
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.settings.AppSettings
import com.opentune.lyrics.LyricsNow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Lyrics on widgets: the sung line and the next on the larger widgets, only for their song, only when on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetLyricsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val shadow = shadowOf(AppWidgetManager.getInstance(context))
    private val manager = AppWidgetManager.getInstance(context)

    @Before fun setUp() {
        LibraryStore.init(context)
        AppSettings.init(context)
        AppSettings.updateLyrics { it.copy(onWidgets = true) }
        context.getSharedPreferences("widget", 0).edit().putString("id", SONG)
            .putString("title", "Bohemian Rhapsody").putString("artist", "Queen").commit()
    }

    @After fun tearDown() {
        NowPlayingWidget.showLyrics(context, null)
        AppSettings.updateLyrics { it.copy(onWidgets = false) }
    }

    @Test fun albumArtAndStudioShowTheSungLineAndTheNext() {
        val album = shadow.createWidget(AlbumArtWidget::class.java, R.layout.widget_album_art)
        val studio = shadow.createWidget(StudioWidget::class.java, R.layout.widget_studio)
        assertTrue(NowPlayingWidget.hasLyricRoom(context))
        NowPlayingWidget.showLyrics(context, LyricsNow(SONG, line("Is this the real life?"), line("Is this just fantasy?")))
        AlbumArtWidget().onUpdate(context, manager, intArrayOf(album, studio))
        for (id in listOf(album, studio)) {
            val view = shadow.getViewFor(id)
            assertEquals("Is this the real life?", view.text(R.id.widget_lyric_now))
            assertEquals("Is this just fantasy?", view.text(R.id.widget_lyric_next))
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_lyric_now).visibility)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_lyric_next).visibility)
            // The title keeps to a line so the lyrics fit.
            assertEquals(1, view.findViewById<TextView>(R.id.widget_title).maxLines)
        }
    }

    @Test fun anotherSongsLinesShowNothing() {
        val album = shadow.createWidget(AlbumArtWidget::class.java, R.layout.widget_album_art)
        NowPlayingWidget.showLyrics(context, LyricsNow("bbbbbbbbbb2", line("Not this song"), null))
        AlbumArtWidget().onUpdate(context, manager, intArrayOf(album))
        val view = shadow.getViewFor(album)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_lyric_now).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_lyric_next).visibility)
        assertEquals(2, view.findViewById<TextView>(R.id.widget_title).maxLines)
    }

    @Test fun turnedOffShowsNothing() {
        AppSettings.updateLyrics { it.copy(onWidgets = false) }
        val album = shadow.createWidget(AlbumArtWidget::class.java, R.layout.widget_album_art)
        NowPlayingWidget.showLyrics(context, LyricsNow(SONG, line("Is this the real life?"), line("Is this just fantasy?")))
        AlbumArtWidget().onUpdate(context, manager, intArrayOf(album))
        assertEquals(View.GONE, shadow.getViewFor(album).findViewById<View>(R.id.widget_lyric_now).visibility)
        assertFalse(WidgetLyrics.wanted(context, playing = true))
    }

    @Test fun throughABreakOnlyTheNextLineShows() {
        val album = shadow.createWidget(AlbumArtWidget::class.java, R.layout.widget_album_art)
        NowPlayingWidget.showLyrics(context, LyricsNow(SONG, null, line("Mama, just killed a man")))
        AlbumArtWidget().onUpdate(context, manager, intArrayOf(album))
        val view = shadow.getViewFor(album)
        assertEquals("", view.text(R.id.widget_lyric_now))
        assertEquals("Mama, just killed a man", view.text(R.id.widget_lyric_next))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_lyric_next).visibility)
    }

    @Test fun theOneRowNowPlayingWidgetHasNoRoom() {
        val id = shadow.createWidget(NowPlayingWidget::class.java, R.layout.widget_now_playing)
        manager.updateAppWidgetOptions(id, android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 80)
        })
        assertFalse(NowPlayingWidget.hasLyricRoom(context))
        assertFalse(WidgetLyrics.wanted(context, playing = true))
    }

    /** Android 11, where the widget picks its tall layout itself (see NowPlayingWidgetTest). */
    @Config(sdk = [30])
    @Test fun theTwoRowNowPlayingWidgetShowsThem() {
        val id = shadow.createWidget(NowPlayingWidget::class.java, R.layout.widget_now_playing)
        val options = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
        }
        manager.updateAppWidgetOptions(id, options)
        assertTrue(NowPlayingWidget.hasLyricRoom(context))
        NowPlayingWidget.showLyrics(context, LyricsNow(SONG, line("Is this the real life?"), line("Is this just fantasy?")))
        NowPlayingWidget().onAppWidgetOptionsChanged(context, manager, id, options)
        val view = shadow.getViewFor(id)
        assertEquals("Is this the real life?", view.text(R.id.widget_lyric_now))
        assertEquals("Is this just fantasy?", view.text(R.id.widget_lyric_next))
    }

    @Test fun aRightToLeftLineStartsAtTheRight() {
        val urdu = LyricLine(0, 1_000, "Love you جانم", emptyList(), wordSynced = false, rtl = true)
        assertTrue(NowPlayingWidget.lyricText(urdu).startsWith("‏"))
        assertEquals("Is this the real life?", NowPlayingWidget.lyricText(line("Is this the real life?")))
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun line(text: String) = LyricLine(0, 1_000, text, listOf(LyricWord(text, 0, 1_000)), wordSynced = true)

    private companion object {
        const val SONG = "aaaaaaaaaa1"
    }
}
