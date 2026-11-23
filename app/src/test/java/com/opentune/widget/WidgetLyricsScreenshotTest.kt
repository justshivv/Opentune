package com.opentune.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.opentune.R
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.settings.AppSettings
import com.opentune.lyrics.LyricsNow
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The larger widgets with lyrics under the title, at their usual sizes; saved to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "xxhdpi", application = Application::class)
class WidgetLyricsScreenshotTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun widgetsWithLyrics() {
        LibraryStore.init(context)
        AppSettings.init(context)
        AppSettings.updateLyrics { it.copy(onWidgets = true) }
        context.getSharedPreferences("widget", 0).edit().putString("id", "aaaaaaaaaa1")
            .putString("title", "Bohemian Rhapsody").putString("artist", "Queen").commit()
        val shadow = shadowOf(AppWidgetManager.getInstance(context))
        val manager = AppWidgetManager.getInstance(context)
        val album = shadow.createWidget(AlbumArtWidget::class.java, R.layout.widget_album_art)
        val studio = shadow.createWidget(StudioWidget::class.java, R.layout.widget_studio)
        val tall = shadow.createWidget(NowPlayingWidget::class.java, R.layout.widget_now_playing)
        val options = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
        }
        manager.updateAppWidgetOptions(tall, options)
        NowPlayingWidget.showLyrics(context, LyricsNow("aaaaaaaaaa1", line("Is this the real life? Is this just fantasy?"), line("Caught in a landslide, no escape from reality")))
        AlbumArtWidget().onUpdate(context, manager, intArrayOf(album, studio))
        NowPlayingWidget().onAppWidgetOptionsChanged(context, manager, tall, options)
        save("widget-lyrics-album-art", shadow.getViewFor(album), 270, 380)
        save("widget-lyrics-studio", shadow.getViewFor(studio), 270, 250)
        save("widget-lyrics-now-playing-tall", shadow.getViewFor(tall), 360, 160)
        NowPlayingWidget.showLyrics(context, null)
    }

    private fun save(name: String, view: View, widthDp: Int, heightDp: Int) {
        val d = context.resources.displayMetrics.density
        val w = (widthDp * d).toInt()
        val h = (heightDp * d).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF3A4A5C.toInt())
        view.draw(canvas)
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun line(text: String) = LyricLine(0, 1_000, text, listOf(LyricWord(text, 0, 1_000)), wordSynced = true)
}
