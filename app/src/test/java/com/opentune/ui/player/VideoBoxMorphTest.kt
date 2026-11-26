package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.PlayerStyle
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.components.PlayerMorph
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The music video's box: how big it is in each layout, when the picture is cut to fit and
 * when it shows whole, and the change between the song and its video as one continuous
 * morph out of the cover, exactly where and as the layout draws it, and back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class VideoBoxMorphTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        PlayerMorph.playerCover = null
        PlayerMorph.lastPlayerCover = null
        PlayerMorph.flying = false
        VideoMorphProbe.last = null
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder().default(BitmapDrawable(context.resources, cover()).asImage()).build())
        }.coroutineContext(Dispatchers.Unconfined).build())
    }

    // The box's size and the crop-or-whole choice.

    @Test fun edgeToEdgeTheBoxTakesThePicturesOwnShape() {
        // The S25 Ultra's region, in dp: 383 wide (14dp short of each side), 325 tall.
        // The box is as wide as the region and as tall as the picture's shape asks.
        assertEquals(VideoBoxSpec(383f, 383f * 9f / 16f, crop = false), videoBoxSpec(383f, 325f, 16f / 9f, fill = true))
        assertEquals(VideoBoxSpec(383f, 383f * 3f / 4f, crop = false), videoBoxSpec(383f, 325f, 4f / 3f, fill = true))
        // Not known yet: as 16:9.
        assertEquals(VideoBoxSpec(383f, 383f * 9f / 16f, crop = false), videoBoxSpec(383f, 325f, 0f, fill = true))
        // Even in a square region a 16:9 picture shows whole, at its own shape.
        assertFalse(videoBoxSpec(330f, 330f, 16f / 9f, fill = true).crop)
    }

    @Test fun aTallPictureShowsWholeAtTheRegionsHeightInsteadOfLosingMostOfIt() {
        val tall = videoBoxSpec(383f, 325f, 9f / 16f, fill = true)
        assertFalse(tall.crop)
        assertEquals(325f, tall.height, 0.01f)
        assertEquals(325f * 9f / 16f, tall.width, 0.01f)
        // A very wide one too: cut to the region it would lose more than 45% of its width.
        val scope = videoBoxSpec(383f, 325f, 2.39f, fill = true)
        assertFalse(scope.crop)
        assertEquals(383f, scope.width, 0.01f)
        assertEquals(383f / 2.39f, scope.height, 0.01f)
    }

    @Test fun inASquareLayoutTheBoxIsAsWideAsTheCoverAndAsTallAsThePictureNeeds() {
        assertEquals(VideoBoxSpec(363f, 363f * 9f / 16f, crop = false), videoBoxSpec(363f, 420f, 16f / 9f, fill = false))
        assertEquals(VideoBoxSpec(363f, 363f * 3f / 4f, crop = false), videoBoxSpec(363f, 420f, 4f / 3f, fill = false))
        // Tall: up to the square's height; a 9:16 shows whole, a 4:5 fills the square cut a little.
        assertEquals(VideoBoxSpec(363f * 9f / 16f, 363f, crop = false), videoBoxSpec(363f, 420f, 9f / 16f, fill = false))
        assertEquals(VideoBoxSpec(363f, 363f, crop = true), videoBoxSpec(363f, 420f, 4f / 5f, fill = false))
        // The square is the smaller side of the region.
        assertEquals(300f, videoBoxSpec(400f, 300f, 16f / 9f, fill = false).width, 0.01f)
    }

    @Test fun theCornersAreACardsNotAPills() {
        assertEquals(12.dp, videoBoxCorner(edgeToEdge = true, coverCorner = 0.dp))
        assertEquals(12.dp, videoBoxCorner(edgeToEdge = false, coverCorner = 28.dp))
        // A cover set square keeps its video square.
        assertEquals(0.dp, videoBoxCorner(edgeToEdge = false, coverCorner = 0.dp))
        assertEquals(8.dp, videoBoxCorner(edgeToEdge = false, coverCorner = 8.dp))
    }

    // The morph, as geometry.

    @Test fun theMorphStartsOnTheCoverEndsOnTheBoxAndNeverJumps() {
        val at = EdgeCoverAt(Offset(84f, 180f), playerWidth = 1233f, coverHeight = 1400f, solid = 0.55f)
        val box = Rect(-30f, 0f, 1095f, 975f)
        for (cover in listOf(edgeCoverPlace(at), squareCoverPlace(1065f, 975f, 84f))) {
            val first = morphFrame(cover, box, 36f, 0f)
            assertEquals(cover.clip, first.clip)
            assertEquals(cover.image, first.image)
            assertEquals(cover.corner, first.corner, 0.001f)
            val last = morphFrame(cover, box, 36f, 1f)
            assertEquals(box, last.clip)
            assertEquals(36f, last.corner, 0.001f)
            // At the end the image fills the box, centred.
            assertEquals(box.center.x, last.image.center.x, 0.01f)
            assertEquals(maxOf(box.width, box.height), last.image.width, 0.01f)
            // Sampled finely, every edge moves by a sliver at a time: one shape on its way, no step.
            var prev = first
            for (i in 1..200) {
                val f = morphFrame(cover, box, 36f, i / 200f)
                assertTrue(gap(prev, f) <= gap(first, last) / 200f * 1.01f + 0.01f)
                prev = f
            }
        }
    }

    @Test fun theEdgeToEdgeCoverMatchesTheBackdropsArt() {
        // A 1233px-wide player whose cover is 1400px tall: a 1400px square, centred, cut by the sides.
        val at = EdgeCoverAt(Offset(84f, 180f), playerWidth = 1233f, coverHeight = 1400f, solid = 0.55f)
        val place = edgeCoverPlace(at)
        assertEquals(Rect(-83.5f - 84f, -180f, 1316.5f - 84f, 1220f), place.image)
        assertEquals(Rect(-84f, -180f, 1233f - 84f, 1400f * 0.55f - 180f), place.clip)
        assertEquals(1f, edgeCoverAlpha(0f), 0f)
        assertEquals(0f, edgeCoverAlpha(0.7f), 0f)
    }

    // The morph, in the player.

    @Test fun edgeToEdgeTheSwitchIsOneMorphOutOfTheFullCoverAndBack() = switchBothWays("edge", fullCover = true)

    @Test fun inTheSquareLayoutTheSwitchIsOneMorphOutOfTheCoverAndBack() = switchBothWays("square", fullCover = false)

    @Test fun aTallVideoInTheEdgeToEdgeLayoutShowsWhole() {
        val bitmap = frame(720, 1280)
        rig(fullCover = true, bitmap = bitmap, videoMode = true, ready = true)
        settle()
        val box = compose.onNodeWithTag("video-cover-box").getBoundsInRoot()
        assertEquals(9f / 16f, (box.right - box.left) / (box.bottom - box.top), 0.02f)
        shoot(capture(), "edge-9x16-video")
    }

    private class Rig(val mode: androidx.compose.runtime.MutableState<Boolean>, val ready: androidx.compose.runtime.MutableState<Boolean>)

    private fun rig(fullCover: Boolean, bitmap: Bitmap = frame(), videoMode: Boolean = false, ready: Boolean = false): Rig {
        compose.mainClock.autoAdvance = false
        val mode = mutableStateOf(videoMode)
        val drawn = mutableStateOf(ready)
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(
                    state(mode.value, fullCover), position = { 92_000 }, buffered = { 140_000 },
                    actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                    videoSurface = {},
                    videoPage = VideoPage(picture(bitmap), aspect = bitmap.width.toFloat() / bitmap.height),
                    videoReady = { drawn.value },
                )
            }
        }
        return Rig(mode, drawn)
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun switchBothWays(name: String, fullCover: Boolean) {
        val p = rig(fullCover)
        settle()
        val song = capture()
        shoot(song, "$name-0-song")

        // Song to video. The frame the switch lands in draws the box exactly over the cover.
        p.mode.value = true
        compose.mainClock.advanceTimeByFrame()
        val switched = capture()
        shoot(switched, "$name-1-switch-frame")
        val region = coverRegion()
        val start = difference(song, switched, region)
        assertTrue("the switch's first frame changed the cover by $start", start < 0.02f)
        val cover = VideoMorphProbe.last ?: error("the box drew no frame")

        // Then frame by frame, the video slow to load (no frame drawn yet): one shape on its way.
        val frames = mutableListOf(cover)
        var elapsed = 0L
        while (elapsed < VIDEO_BOX_MS + 120L) {
            compose.mainClock.advanceTimeByFrame()
            elapsed += 16
            frames += VideoMorphProbe.last!!
            if (elapsed in setOf(64L, 128L, 208L, 320L)) shoot(capture(), "$name-2-to-video-${elapsed}ms")
        }
        assertContinuous(frames)
        val box = frames.last()
        // Settled on the box, the cover still in it: nothing of the picture before its first frame.
        val waiting = capture()
        shoot(waiting, "$name-3-box-waiting-for-first-frame")
        compose.mainClock.advanceTimeBy(500)
        assertTrue("the picture showed before its first frame", difference(waiting, capture(), region) < 0.01f)

        // The first frame is drawn: the picture fades in over the cover.
        p.ready.value = true
        compose.mainClock.advanceTimeBy(VIDEO_PICTURE_IN_MS / 2L)
        shoot(capture(), "$name-4-picture-fading-in")
        compose.mainClock.advanceTimeBy(VIDEO_PICTURE_IN_MS + 200L)
        val video = capture()
        shoot(video, "$name-5-video")
        assertTrue("the picture never came in", difference(waiting, video, region) > 0.03f)
        assertEquals(box.clip, VideoMorphProbe.last!!.clip)

        // And back: the picture fades to the cover as the box grows back into it.
        p.mode.value = false
        p.ready.value = false
        val back = mutableListOf(VideoMorphProbe.last!!)
        elapsed = 0L
        while (elapsed < VIDEO_BOX_MS + 120L) {
            compose.mainClock.advanceTimeByFrame()
            elapsed += 16
            back += VideoMorphProbe.last!!
            if (elapsed in setOf(64L, 128L, 208L, 320L)) shoot(capture(), "$name-6-to-song-${elapsed}ms")
        }
        assertContinuous(back)
        // The last frame of the box was the cover's own place, then the cover itself, unmoved.
        assertEquals(cover.clip.left, back.last().clip.left, 1f)
        assertEquals(cover.clip.top, back.last().clip.top, 1f)
        assertEquals(cover.clip.right, back.last().clip.right, 1f)
        assertEquals(cover.clip.bottom, back.last().clip.bottom, 1f)
        settle()
        val after = capture()
        shoot(after, "$name-7-song-again")
        compose.onNodeWithTag("video-cover-box").assertDoesNotExist()
        val end = difference(song, after, region)
        assertTrue("back on the song, the cover differs by $end", end < 0.02f)
    }

    /** No edge of the box moves more than a fifth of its whole way in one frame, nor turns back. */
    private fun assertContinuous(frames: List<MorphFrame>) {
        val total = gap(frames.first(), frames.last())
        assertTrue("the box never moved", total > 10f)
        for (i in 1 until frames.size) {
            val step = gap(frames[i - 1], frames[i])
            assertTrue("frame $i jumped $step of $total", step <= total * 0.2f)
        }
        // Monotonic: each edge goes one way only.
        fun edge(f: MorphFrame, k: Int) = when (k) { 0 -> f.clip.left; 1 -> f.clip.top; 2 -> f.clip.right; else -> f.clip.bottom }
        for (k in 0..3) {
            val sign = Math.signum(edge(frames.last(), k) - edge(frames.first(), k))
            for (i in 1 until frames.size) {
                val d = edge(frames[i], k) - edge(frames[i - 1], k)
                assertTrue("edge $k turned back at frame $i by $d", d * sign >= -0.5f)
            }
        }
    }

    private fun gap(a: MorphFrame, b: MorphFrame): Float = maxOf(
        abs(a.clip.left - b.clip.left), abs(a.clip.top - b.clip.top), abs(a.clip.right - b.clip.right), abs(a.clip.bottom - b.clip.bottom),
        abs(a.image.left - b.image.left), abs(a.image.top - b.image.top), abs(a.image.right - b.image.right), abs(a.corner - b.corner),
    )

    /** The screen's cover region, between the status line and the Song | Video switch, in px. */
    private fun coverRegion(): android.graphics.Rect {
        val d = compose.density.density
        return android.graphics.Rect(0, (120 * d).toInt(), (411 * d).toInt(), (420 * d).toInt())
    }

    /** The mean difference of two shots inside [r], 0 (the same) to 1. */
    private fun difference(a: Bitmap, b: Bitmap, r: android.graphics.Rect): Float {
        var sum = 0L
        var n = 0L
        for (y in r.top until r.bottom step 3) for (x in r.left until r.right step 3) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
            n += 3
        }
        return sum / (n * 255f)
    }

    private fun capture(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun shoot(bitmap: Bitmap, name: String) {
        File(SHOTS, "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val song = Song("x", "Jo Tum Mere Ho", "Anuv Jain", "https://covers.invalid/jtmh.jpg", "4:11")

    private fun state(videoMode: Boolean, fullCover: Boolean) = PlayerUiState(
        song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true, shuffle = false,
        repeatMode = Player.REPEAT_MODE_OFF, durationMs = 251_000, lyrics = LyricsState.NotFound, queue = listOf(song),
        currentIndex = 0, upNext = emptyList(), sound = SoundSettings(), theme = ThemeSettings(),
        ui = InterfaceSettings(playerStyle = PlayerStyle.APPLE_MUSIC, reduceAnimation = false, fullScreenCover = fullCover, movingCover = false),
        videoMode = videoMode, videoAvailable = true,
    )

    /** A cover with detail in every corner, so a cover moved or scaled shows in a shot. */
    private fun cover(): Bitmap {
        val s = 300
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), 0xFF3A6C71.toInt(), 0xFF6D77D7.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.shader = null
        p.color = 0xFFF2E6C9.toInt()
        for (i in 0 until 6) c.drawCircle(40f + i * 44f, 60f + (i % 3) * 70f, 16f, p)
        p.color = 0xFF20304A.toInt()
        c.drawRect(30f, 230f, 270f, 260f, p)
        return b
    }

    /** A frame standing in for the picture, [w] by [h], with black bars of its own at the sides as many music videos have. */
    private fun frame(w: Int = 1280, h: Int = 720): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF5A3A2C.toInt(), 0xFFE0B080.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFF2B1A12.toInt()
        c.drawCircle(w * 0.45f, h * 0.45f, minOf(w, h) * 0.22f, p)
        p.color = 0xFFF5E8D0.toInt()
        c.drawRect(w * 0.78f, h * 0.1f, w * 0.84f, h * 0.9f, p)
        return b
    }

    private fun picture(bitmap: Bitmap): @Composable BoxScope.(Boolean) -> Unit = { crop ->
        Box(Modifier.matchParentSize().testTag("video-picture"), contentAlignment = Alignment.Center) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        }
    }

    private companion object {
        val SHOTS = File("/tmp/claude-0/-home-user-Opentune/cb396347-e5a5-515e-abbd-8501f88d5f1d/scratchpad/video-box-shots")
    }
}
