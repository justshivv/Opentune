package com.opentune.ui.radio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.RadioMixes
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Radio page laid out from a pretend "Mixed for you": tiles, Your Mixes, mood rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-xxhdpi", application = Application::class)
class RadioMixesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val pairs = listOf(
        0xFF8E8A92 to 0xFF4A3F45, 0xFF2D3FD0 to 0xFF3B5E73, 0xFF7A3A36 to 0xFFC0603A, 0xFF6B6385 to 0xFF7A3A4A,
        0xFF1F4A3A to 0xFF2C3B33, 0xFF5FC6D9 to 0xFFF2A65A, 0xFFF39A2B to 0xFFE0457B, 0xFFB7A6E0 to 0xFFF3B6C9,
        0xFF6A3BF0 to 0xFFD23BE0, 0xFF5B7A99 to 0xFF223344,
    )

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        val engine = FakeImageLoaderEngine.Builder().apply {
            pairs.forEachIndexed { i, (a, b) ->
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bm ->
                    Canvas(bm).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 96f, 96f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP) })
                }
                intercept({ it is String && it.contains("/c$i/") }, BitmapDrawable(context.resources, bitmap).asImage())
            }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private fun mix(title: String, subtitle: String, c: Int) = ShelfItem(title, subtitle, "https://img/c$c/x.jpg", null, "VLRDTMAK_${title.replace(' ', '_')}")

    @Test fun thePage() {
        val page = RadioMixes.arrange(listOf(
            HomeShelf("Mixed for you", listOf(
                mix("My Supermix", "Arijit Singh, Shubh, Kishore Kumar", 0), mix("Discover Mix", "New to you", 1),
                mix("New Release Mix", "Fresh from artists you play", 2), mix("Replay Mix", "Your repeats", 3),
                mix("Archive Mix", "Older favourites", 4),
                mix("My Mix 1", "Kishore Kumar, Hemlata, Lata Mangeshkar, Mahendra Kapoor", 9),
                mix("My Mix 2", "Shubh, Ikky, Sidhu Moose Wala, Diljit Dosanjh", 1),
            )),
            HomeShelf("Moods", listOf(
                mix("Chill Supermix", "Jay Trak, Gurinder Gill, AP Dhillon", 5), mix("Chill Mix 1", "Navjot Ahuja, Vishal-Shekhar", 9),
                mix("Chill Mix 2", "Kaleo, Libianca", 7),
                mix("Workout Supermix", "The Weeknd, Syra, Salim", 6), mix("Workout Mix 1", "Sia, Kygo, Dua Lipa", 2),
                mix("Feel Good Supermix", "Hero And King Of Jhankar", 7), mix("Feel Good Mix 1", "Vishal-Shekhar, Neeti Mohan", 3),
                mix("Party Supermix", "Asha Bhosle, R. D. Burman", 8), mix("Party Mix 1", "Gur Sidhu, Shubh, Karan Aujla", 0),
            )),
        ))
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RadioMixesPage(PaddingValues(bottom = 24.dp), page, signedIn = true, failed = false, onRetry = {}, onItemClick = {}, onOpenLiked = {},
                        onOpenStations = {}, onSignIn = {}, onOpenSettings = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Discover Mix").assertExists()
        compose.onNodeWithText("Your Mixes").assertExists()
        compose.onNodeWithText("Chill").assertExists()
        shot("radio-mixes")
    }

    @Test fun signedOut() {
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RadioMixesPage(PaddingValues(bottom = 24.dp), null, signedIn = false, failed = false, onRetry = {}, onItemClick = {}, onOpenLiked = {},
                        onOpenStations = {}, onSignIn = {}, onOpenSettings = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Liked Music").assertExists()
        compose.onNodeWithText("Sign in").assertExists()
        shot("radio-mixes-signed-out")
    }

    private fun shot(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots/radio/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
