package com.opentune.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * In the player the next song's colours take over straight from the last song's: while the
 * new cover is being read, the old colours stay rather than the app's accent showing between.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ArtworkColorsHoldTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun solid(c: Int) = BitmapDrawable(context.resources, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }).asImage()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder()
                .intercept({ it is String && it.contains("hold-red") }, solid(android.graphics.Color.rgb(200, 30, 40)))
                .intercept({ it is String && it.contains("hold-blue") }, solid(android.graphics.Color.rgb(30, 60, 210)))
                .build())
        }.build())
    }

    private fun run(keepLast: Boolean): Pair<List<List<Color>>, List<Color?>> {
        val url = mutableStateOf("https://img/hold-red-$keepLast.jpg")
        val palettes = mutableListOf<List<Color>>()
        val seeds = mutableListOf<Color?>()
        compose.setContent {
            val u = url.value
            val palette = rememberArtworkPalette(u, keepLast)
            val seed = rememberArtworkSeed(u, keepLast)
            SideEffect { palettes += palette; seeds += seed }
        }
        runCatching { compose.waitUntil(10_000) { palettes.lastOrNull()?.isNotEmpty() == true && seeds.lastOrNull() != null } }
            .onFailure { throw AssertionError("first cover: palettes=${palettes.map { it.size }} seeds=$seeds", it) }
        val firstRed = palettes.last()
        compose.runOnUiThread { url.value = "https://img/hold-blue-$keepLast.jpg" }
        compose.waitForIdle()
        runCatching { compose.waitUntil(10_000) { palettes.last() != firstRed && palettes.last().isNotEmpty() } }
            .onFailure { throw AssertionError("second cover: palettes=$palettes seeds=$seeds", it) }
        return palettes to seeds
    }

    @Test fun thePlayerHoldsTheLastColoursUntilTheNextAreRead() {
        val (palettes, seeds) = run(keepLast = true)
        val from = palettes.indexOfFirst { it.isNotEmpty() }
        assertTrue("an empty palette showed between songs", palettes.drop(from).all { it.isNotEmpty() })
        val seedFrom = seeds.indexOfFirst { it != null }
        assertTrue("the seed went missing between songs", seeds.drop(seedFrom).all { it != null })
        assertEquals(2, palettes.drop(from).distinct().size)
    }

    /** Elsewhere (a list of covers) each cover's colours are its own: none carried over. */
    @Test fun elsewhereANewCoverStartsEmpty() {
        val (palettes, _) = run(keepLast = false)
        val from = palettes.indexOfFirst { it.isNotEmpty() }
        assertTrue("the old colours were carried over", palettes.drop(from).any { it.isEmpty() })
    }
}
