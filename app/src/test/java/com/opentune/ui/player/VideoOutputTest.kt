package com.opentune.ui.player

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * Into full screen and back, the player keeps drawing into the one surface it
 * was given: the view moves, its texture is kept, and the player is never
 * handed a new surface or told the old one is gone. Robolectric draws no
 * hardware layers, so the platform's own texture calls are made by hand where
 * a phone would make them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class VideoOutputTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val given = mutableListOf<Surface>()
    private val taken = mutableListOf<Surface>()

    @Test fun leavingFullScreenKeepsTheSameSurfaceOnThePlayer() {
        lateinit var output: VideoOutput
        var full by mutableStateOf(false)
        compose.setContent {
            output = androidx.compose.runtime.remember { VideoOutput(compose.activity, { given += it }, { taken += it }) }
            val size = VideoSize(1280, 720)
            if (full) {
                Box(Modifier.fillMaxSize().testTag("full")) { VideoSurface(output, size, Modifier.fillMaxSize(), crop = true) }
            } else {
                Box(Modifier.size(360.dp, 203.dp).testTag("inline")) { VideoSurface(output, size, Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        val view = output.view
        assertTrue(view.isAttachedToWindow)
        // The view's first texture, as the platform hands it over on first draw.
        val texture = SurfaceTexture(0)
        view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture, 1080, 608)
        val surface = output.current
        assertNotNull(surface)
        assertEquals(listOf(surface), given)
        val inlineParent = view.parent

        // Into full screen: the same view is moved; the platform reports its texture gone as it leaves.
        moveAndReportDetach(view, texture) { full = true }
        assertTrue(view.isAttachedToWindow)
        assertTrue(view.parent !== inlineParent)
        assertSame(surface, output.current)

        // And back: the player still has the surface it had at the start, never replaced or taken.
        moveAndReportDetach(view, texture) { full = false }
        assertTrue(view.isAttachedToWindow)
        assertSame(surface, output.current)
        assertEquals(listOf(surface), given)
        assertEquals(emptyList<Surface>(), taken)

        // Long after the moves, still the same: the kept texture's release was called off on attach.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(VideoOutput.KEEP_MS * 3))
        assertSame(surface, output.current)
        assertEquals(emptyList<Surface>(), taken)
    }

    @Test fun aPictureNotShownAgainGivesItsSurfaceBack() {
        lateinit var output: VideoOutput
        var shown by mutableStateOf(true)
        compose.setContent {
            output = androidx.compose.runtime.remember { VideoOutput(compose.activity, { given += it }, { taken += it }) }
            if (shown) Box(Modifier.size(200.dp)) { VideoSurface(output, VideoSize(1280, 720), Modifier.fillMaxSize()) }
        }
        compose.waitForIdle()
        val texture = SurfaceTexture(0)
        output.view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture, 600, 600)
        val surface = output.current!!
        moveAndReportDetach(output.view, texture) { shown = false }
        // Kept for a moment in case it's only moving...
        assertEquals(emptyList<Surface>(), taken)
        // ...then given back.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(VideoOutput.KEEP_MS + 100))
        assertEquals(listOf(surface), taken)
        assertEquals(null, output.current)
    }

    /**
     * Runs [change]; when it takes the view off the window, says so the way the
     * platform does on a phone (onSurfaceTextureDestroyed while detaching).
     */
    private fun moveAndReportDetach(view: android.view.TextureView, texture: SurfaceTexture, change: () -> Unit) {
        var keptByListener: Boolean? = null
        val listener = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = Unit
            override fun onViewDetachedFromWindow(v: android.view.View) {
                keptByListener = !view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture)
            }
        }
        // Registered first, so it runs before the output's own attach handling on the way back in.
        view.addOnAttachStateChangeListener(listener)
        compose.runOnIdle(change)
        compose.waitForIdle()
        view.removeOnAttachStateChangeListener(listener)
        // The texture is kept, not released, by the output.
        assertEquals(true, keptByListener)
    }
}
