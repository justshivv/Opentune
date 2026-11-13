package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import com.opentune.data.model.BrowseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import coil3.asImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A long-pressed card lifts out as a copy that settles back onto the card when the
 * menu closes. The copy has to draw exactly what the card draws: before, it had more
 * padding, a smaller cover and a one-line bold title, so the cover jumped as the
 * menu went away.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CardPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val cover = "https://covers.invalid/album.jpg"

    /** Covers come from memory on the test's own thread, as in ReadmeScreenshotTest, so drawing settles. */
    @Before fun setUp() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        com.opentune.data.settings.AppSettings.init(context)
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(200, 60, 80))
        }
        val engine = coil3.test.FakeImageLoaderEngine.Builder()
            .default(android.graphics.drawable.BitmapDrawable(context.resources, bmp).asImage())
            .build()
        coil3.SingletonImageLoader.setUnsafe(
            coil3.ImageLoader.Builder(context).components { add(engine) }.coroutineContext(kotlinx.coroutines.Dispatchers.Unconfined).build(),
        )
    }

    /** The window drawn into a bitmap, cut to [r]: no waiting for idle, which the cover's shimmer never gives. */
    private fun shot(r: androidx.compose.ui.geometry.Rect): android.graphics.Bitmap {
        val view = compose.activity.window.decorView
        val all = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(all))
        return android.graphics.Bitmap.createBitmap(all, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
    }

    private fun assertCopyMatchesCard(type: BrowseType, title: String, subtitle: String) {
        // The cover's loading shimmer never settles: hold the clock so both are drawn at one moment.
        compose.mainClock.autoAdvance = false
        var cardAt = androidx.compose.ui.geometry.Rect.Zero
        var copyAt = androidx.compose.ui.geometry.Rect.Zero
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                var size by remember { mutableStateOf(IntSize.Zero) }
                val density = LocalDensity.current
                Row {
                    Box(Modifier.onSizeChanged { size = it }.onGloballyPositioned { cardAt = it.boundsInWindow() }) {
                        ItemCard(title, subtitle, cover, type, onClick = {})
                    }
                    if (size != IntSize.Zero) {
                        val w = with(density) { size.width.toDp() }
                        val h = with(density) { size.height.toDp() }
                        Box(Modifier.size(w, h).onGloballyPositioned { copyAt = it.boundsInWindow() }) { CardPreview(title, subtitle, cover, type) }
                    }
                }
            }
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val card = shot(cardAt)
        val copy = shot(copyAt)
        assertEquals(card.width, copy.width)
        assertEquals(card.height, copy.height)
        // Anti-aliased edges may round a channel by one; a moved or resized cover changes far more.
        fun near(a: Int, b: Int) = (0..3).all { shift -> kotlin.math.abs(((a shr (shift * 8)) and 0xff) - ((b shr (shift * 8)) and 0xff)) <= 2 }
        var differing = 0
        for (y in 0 until card.height) for (x in 0 until card.width) {
            if (!near(card.getPixel(x, y), copy.getPixel(x, y))) differing++
        }
        // A handful of edge pixels may round differently; a cover even one pixel out of place differs in thousands.
        assertTrue("$differing of ${card.width * card.height} pixels differ between the card and its lifted copy", differing <= 30)
    }

    @Test fun albumCopyDrawsExactlyTheCard() = assertCopyMatchesCard(BrowseType.ALBUM, "A Fairly Long Album Title That Wraps", "Album • Some Artist")

    @Test fun artistCopyDrawsExactlyTheCard() = assertCopyMatchesCard(BrowseType.ARTIST, "An Artist", "Artist • 2M listeners")
}
