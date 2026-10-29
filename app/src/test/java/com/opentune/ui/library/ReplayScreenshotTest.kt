package com.opentune.ui.library

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.history.History
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Replay's page and story, with a week of listening, as pictures to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ReplayScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun listens() {
        val now = System.currentTimeMillis()
        val songs = listOf(
            Triple("Kal Chaudhvin Ki Raat Thi", "Jagjit Singh", "Duniya"), Triple("Ye Dil Tum Bin Lagta Nahin", "Lata Mangeshkar", "Forever"),
            Triple("Desires", "AP Dhillon", "Hidden Gems"), Triple("Dollar", "Sidhu Moosewala", "Pbx 1"), Triple("Udaarian", "Satinder Sartaaj", "Seasons"),
        )
        val records = JsonArray((0 until 30).map { i ->
            val (t, a, al) = songs[i % songs.size]
            buildJsonObject {
                put("videoId", "v${i % songs.size}"); put("title", t); put("artist", a); put("albumName", al)
                put("playedAt", now - i * 3_600_000L); put("listenedMs", 200_000L + (i % 3) * 60_000L)
            }
        })
        History.init(ApplicationProvider.getApplicationContext())
        History.importJson(records)
    }

    @Test fun page() {
        listens()
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ReplayScreen(PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {})
                }
            }
        }
        compose.waitForIdle()
        save("replay-page", compose.activity.window.decorView)
    }

    @Test fun story() {
        listens()
        compose.mainClock.autoAdvance = false
        val summary = History.replay(History.records.value, ReplayRange.WEEK.since())
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                ReplayStory(summary, ReplayRange.WEEK, onDismiss = {})
            }
        }
        for (page in 0 until 7) {
            compose.mainClock.advanceTimeBy(if (page == 0) 1_500 else 6_000)
            save("replay-story-$page", topWindow() ?: compose.activity.window.decorView)
        }
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
