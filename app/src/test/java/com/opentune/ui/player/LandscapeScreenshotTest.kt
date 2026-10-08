package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The player on its side, with the cover, then with lyrics, to build/screenshots/landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi", application = android.app.Application::class)
class LandscapeScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components { add(FakeImageLoaderEngine.Builder().default(android.graphics.drawable.BitmapDrawable(context.resources, coverArt()).asImage()).build()) }
                .coroutineContext(Dispatchers.Unconfined).build(),
        )
    }

    private val song = Song("x", "Sanctuary", "Joji", "https://covers.invalid/sanctuary.jpg", "3:00", albumName = "Nectar")
    private val lyrics = LyricsState.Found(
        Lyrics.Synced(
            listOf(
                "In the middle of the night", "More than fun, you're the sanctuary", "Souls that dream alone lie awake",
                "I'll give you something so real", "Hold on to me",
            ).mapIndexed { i, t -> LyricLine(i * 20_000L, i * 20_000L + 19_000, t, emptyList(), false) },
            "test",
        ),
    )

    @Test fun cover() = shoot("landscape/cover", initialLyrics = false)

    @Test fun withLyrics() = shoot("landscape/lyrics", initialLyrics = true)

    private fun shoot(name: String, initialLyrics: Boolean) {
        val state = PlayerUiState(
            song = song, isPlaying = true, isBuffering = false, hasNext = true, shuffle = false, repeatMode = Player.REPEAT_MODE_OFF,
            durationMs = 180_000, lyrics = lyrics, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), ui = InterfaceSettings(liquidGlass = false, reduceAnimation = true),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    PlayerLayout(state, position = { 29_000 }, buffered = { 60_000 }, actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}), initialLyrics = initialLyrics)
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** A cover to draw with: a real one from OPENTUNE_SHOT_ART when set, else a red study in light and shadow. */
internal fun coverArt(): Bitmap {
    System.getenv("OPENTUNE_SHOT_ART")?.let { File(it, "00hd.jpg") }?.takeIf { it.isFile }?.let { f ->
        android.graphics.BitmapFactory.decodeFile(f.path)?.let { return it }
    }
    val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(0xFF1A0505.toInt())
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.shader = android.graphics.RadialGradient(380f, 260f, 320f, 0xFFD3271F.toInt(), 0x001A0505, android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, 600f, 600f, p)
    p.shader = null
    p.color = 0xFF0A0202.toInt()
    c.drawCircle(180f, 470f, 210f, p)
    return b
}
