package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.model.BrowseType
import com.opentune.ui.PageMotion
import com.opentune.ui.theme.Text
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An album card on a page, and its album page, in a NavHost set up as the
 * app's is: the page grows out of the card when it's tapped, and comes in
 * the usual way when it's opened otherwise or with motion reduced.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CoverMorphTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private lateinit var nav: NavHostController
    private var allowed by mutableStateOf(true)

    @After fun forget() = CoverMorph.clear()

    private fun pages() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ it is String && it.contains("/melodrama") }, android.graphics.drawable.ColorDrawable(0xFF3A7BD5.toInt()).asImage())
            .intercept({ it is String && it.contains("/other") }, android.graphics.drawable.ColorDrawable(0xFFE0457B.toInt()).asImage())
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        compose.setContent {
            nav = rememberNavController()
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), background = Color(0xFF0B0B0E))) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FollowCoverPages(nav, allowed, setOf("collection/{id}"))
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalSharedPages provides this) {
                            NavHost(
                                nav, "home", Modifier.fillMaxSize(),
                                enterTransition = { if (CoverMorph.opens(targetState.id)) PageMotion.growIn else fadeIn() },
                                exitTransition = { if (CoverMorph.opens(targetState.id)) PageMotion.recede else fadeOut() },
                                popEnterTransition = { if (CoverMorph.closesInto(initialState.id, targetState.id)) PageMotion.comeForward else fadeIn() },
                                popExitTransition = { if (CoverMorph.closesInto(initialState.id, targetState.id)) PageMotion.growOut else fadeOut() },
                            ) {
                                composable("home") {
                                    CompositionLocalProvider(LocalPageScope provides this) {
                                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 80.dp)) {
                                            Text("Albums for you", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                                            Row(Modifier.padding(horizontal = 10.dp)) {
                                                ItemCard("Melodrama", "Album • Lorde", "https://img/melodrama", BrowseType.ALBUM,
                                                    onClick = { nav.navigate("collection/MPREb_a") }, modifier = Modifier.testTag("card"), opens = "MPREb_a")
                                                ItemCard("Pure Heroine", "Album • Lorde", "https://img/other", BrowseType.ALBUM,
                                                    onClick = { nav.navigate("collection/MPREb_b") }, opens = "MPREb_b")
                                            }
                                        }
                                    }
                                }
                                composable("collection/{id}") { e ->
                                    CompositionLocalProvider(LocalPageScope provides this) {
                                        Column(Modifier.fillMaxSize().coverMorphPage(e.id).background(Color(0xFF14161C))) {
                                            Artwork("https://img/melodrama", Modifier.fillMaxWidth().aspectRatio(1f), RectangleShape)
                                            Text("Melodrama", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(20.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aTappedCoverOpensItsPageOutOfItself() {
        pages()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("card").performClick()
        compose.mainClock.advanceTimeBy(140)
        save("cover-grow-mid")
        assertTrue(CoverMorph.opens(nav.currentBackStackEntry!!.id))
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        save("cover-grow-open")
        val page = nav.currentBackStackEntry!!.id
        // And back: it shrinks into the cover it came out of.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.popBackStack() }
        compose.mainClock.advanceTimeBy(200)
        save("cover-grow-back")
        assertTrue(CoverMorph.closesInto(page, nav.currentBackStackEntry!!.id))
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse("forgotten once it has gone", CoverMorph.opens(page))
    }

    @Test fun withMotionReducedThePageComesInTheUsualWay() {
        allowed = false
        pages()
        compose.onNodeWithTag("card").performClick()
        compose.waitForIdle()
        assertTrue(nav.currentBackStackEntry!!.destination.route == "collection/{id}")
        assertFalse(CoverMorph.opens(nav.currentBackStackEntry!!.id))
    }

    @Test fun aPageOpenedWithoutATapComesInTheUsualWay() {
        pages()
        // A link opened from outside: no cover, so no place to grow from.
        compose.runOnUiThread { nav.navigate("collection/MPREb_a") }
        compose.waitForIdle()
        assertFalse(CoverMorph.opens(nav.currentBackStackEntry!!.id))
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
