package com.opentune.ui.wrapped

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.history.History
import com.opentune.data.history.PlayRecord
import com.opentune.data.settings.ControlStyle
import com.opentune.ui.browse.SongActions
import com.opentune.ui.player.PlayerControls
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every Wrapped page, and the Morph buttons part-way through their change, to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class WrappedScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 8, 21, 0, 0) }.timeInMillis
    private val colors = listOf(0xFFE0457B, 0xFF3A7BD5, 0xFF2BB673, 0xFFF2A93B, 0xFF8E44AD, 0xFF16A085)

    @Before fun setUp() {
        val context = compose.activity
        com.opentune.data.settings.AppSettings.init(context)
        val engine = FakeImageLoaderEngine.Builder().apply {
            colors.forEachIndexed { i, c ->
                intercept({ it is String && it.contains("/c$i") }, android.graphics.drawable.ColorDrawable(c.toInt()).asImage())
            }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.coroutineContext(Dispatchers.Unconfined).build())
        val artists = listOf("Anuv Jain", "Arijit Singh", "The Weeknd", "Prateek Kuhad", "Dua Lipa", "AP Dhillon")
        val songs = listOf("Husn", "Kesariya", "Blinding Lights", "cold/mess", "Levitating", "Excuses", "Baarishein", "Tum Se Hi")
        val records = buildList {
            var t = now
            for (i in 0 until 420) {
                val s = (i * 7 + i / 5) % songs.size
                val weight = listOf(0, 0, 0, 1, 1, 2, 3, 4, 5, 6, 7)[i % 11]
                val pick = if (i % 3 == 0) 0 else weight % songs.size
                val a = pick % artists.size
                add(PlayRecord("v$pick", songs[pick], artists[a], "https://img.invalid/c${pick % colors.size}=w60-h60", "3:30", "Album ${a + 1}", t, 210_000))
                // Mostly late at night, a few each day, across most of the year.
                t -= if (i % 4 == 3) 20 * 60 * 60 * 1000L else 15 * 60 * 1000L
                if (s == 99) break
            }
        }
        History.importJson(Json.encodeToJsonElement(ListSerializer(PlayRecord.serializer()), records))
    }

    @Test fun slides() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                WrappedScreen(SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {}, nowMs = now)
            }
        }
        compose.mainClock.advanceTimeBy(2_500)
        for (i in 0 until 10) {
            save("wrapped/${"%02d".format(i)}")
            compose.onRoot().performTouchInput { click(Offset(width * 0.85f, height * 0.62f)) }
            // Caught mid-turn once, to see the circle opening.
            if (i == 2) {
                compose.mainClock.advanceTimeBy(260)
                save("wrapped/turning")
                compose.mainClock.advanceTimeBy(2_340)
            } else {
                compose.mainClock.advanceTimeBy(2_600)
            }
        }
    }

    @Test fun poster() {
        val summary = com.opentune.data.history.Wrapped.summarize(History.records.value, 0, now)
        val cover = android.graphics.Bitmap.createBitmap(200, 200, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(colors[1].toInt()) }
        val poster = WrappedPoster.draw(summary, "2026", cover, listOf(cover, null, cover, cover, null))
        val file = File("build/screenshots/wrapped/poster.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun morphButtons() {
        var playing by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF15131A)).padding(24.dp), contentAlignment = Alignment.Center) {
                    PlayerControls(playing, false, true, {}, {}, {}, style = ControlStyle.MORPH)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("morph/paused")
        compose.runOnUiThread { playing = true }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var at = 0L
        listOf(0L, 60, 120, 200, 300, 450, 700, 1_200).forEach { t ->
            compose.mainClock.advanceTimeBy(t - at)
            at = t
            save("morph/play-${"%04d".format(t)}")
        }
        compose.runOnUiThread { playing = false }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(150)
        save("morph/pause-0150")
        compose.mainClock.advanceTimeBy(1_200)
        save("morph/pause-1350")
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
