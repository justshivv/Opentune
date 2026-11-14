package com.opentune.ui.home

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.history.DailyMixes
import com.opentune.data.model.Song
import com.opentune.ui.browse.SongActions
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h400dp-xxhdpi", application = android.app.Application::class)
class MixesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun shelf() {
        val colors = listOf(0xFFE0457B, 0xFF3A7BD5, 0xFF2BB673, 0xFFF2A93B, 0xFF8E44AD, 0xFF16A085, 0xFFD35400, 0xFF2C3E50)
        val engine = FakeImageLoaderEngine.Builder().apply {
            colors.forEachIndexed { i, c -> intercept({ it is String && it.endsWith("/c$i") }, android.graphics.drawable.ColorDrawable(c.toInt()).asImage()) }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        fun songs(offset: Int) = (0 until 8).map { Song("s$offset$it", "Song", "Artist", "https://img/c${(it + offset) % colors.size}") }
        val mixes = listOf(
            DailyMixes.Mix("a", "Daily Mix 1", "Anuv Jain, Prateek Kuhad, Ritviz", songs(0)),
            DailyMixes.Mix("b", "Daily Mix 2", "Arijit Singh, Pritam", songs(3)),
            DailyMixes.Mix("t", "Late night mix", "Your after-dark songs", songs(5)),
        )
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) {
                Surface(color = MaterialTheme.colorScheme.background) { MixesShelf(mixes, SongActions(null, false, { _, _, _, _ -> }, {}, {})) }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/home/mixes.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The genre row: real mixes from a history of plays and each artist's genre. */
    @Test fun genres() {
        val colors = listOf(0xFFE0457B, 0xFF3A7BD5, 0xFF2BB673, 0xFFF2A93B, 0xFF8E44AD, 0xFF16A085, 0xFFD35400, 0xFF2C3E50)
        val engine = FakeImageLoaderEngine.Builder().apply {
            colors.forEachIndexed { i, c -> intercept({ it is String && it.endsWith("/c$i") }, android.graphics.drawable.ColorDrawable(c.toInt()).asImage()) }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        val genreOf = mapOf("Arijit Singh" to "Bollywood", "Shreya Ghoshal" to "Bollywood", "Diljit Dosanjh" to "Punjabi",
            "Karan Aujla" to "Punjabi", "Divine" to "Hip-Hop", "Seedhe Maut" to "Hip-Hop")
        var n = 0
        val records = genreOf.keys.flatMapIndexed { a, artist ->
            (0 until 5).flatMap { s -> (0..(5 - s)).map { com.opentune.data.history.PlayRecord("$a-$s", "Song $s", artist, "https://img/c${(n++) % colors.size}", null, null, 1_000L * n) } }
        }
        val mixes = com.opentune.data.history.GenreMixes.build(records, { genreOf[it] })
        org.junit.Assert.assertEquals(3, mixes.size)
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MixesShelf(mixes, SongActions(null, false, { _, _, _, _ -> }, {}, {}), "What you play, sorted by genre, in a new order each day", title = "Your genres")
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/home/genre-mixes.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun streaks() {
        val badges = listOf(
            com.opentune.data.history.Streaks.Badge.DayStreak(12),
            com.opentune.data.history.Streaks.Badge.ArtistHours("Diljit Dosanjh", 25),
            com.opentune.data.history.Streaks.Badge.NewArtists(4),
        )
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) {
                Surface(color = MaterialTheme.colorScheme.background) { StreakPills(badges) }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/home/streaks.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun daylist() {
        val colors = listOf(0xFFE0457B, 0xFF3A7BD5, 0xFF2BB673, 0xFFF2A93B)
        val engine = FakeImageLoaderEngine.Builder().apply {
            colors.forEachIndexed { i, c -> intercept({ it is String && it.endsWith("/c$i") }, android.graphics.drawable.ColorDrawable(c.toInt()).asImage()) }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        val mix = DailyMixes.Mix("daylist", "Punjabi and Hip-Hop, Friday night", "", (0 until 12).map { Song("d$it", "Song", "Artist", "https://img/c${it % 4}") })
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), primaryContainer = Color(0xFF6B2A1A), onPrimaryContainer = Color(0xFFFFDBD0), tertiaryContainer = Color(0xFF3D2F5C))) {
                Surface(color = MaterialTheme.colorScheme.background) { DaylistHero(mix) {} }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/home/daylist.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
