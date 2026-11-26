package com.opentune.ui.player

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Rational
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The small window over other apps: its shape, its buttons, and when it's offered at all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PictureInPictureTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun theShapeIsThePicturesHeldWithinAndroidsLimits() {
        assertEquals(16f / 9f, PictureInPicture.aspect(16f / 9f)!!.toFloat(), 0.001f)
        assertEquals(0.75f, PictureInPicture.aspect(3f / 4f)!!.toFloat(), 0.001f)
        // A very wide picture is held at the widest allowed, a very tall one at the tallest.
        assertEquals(PictureInPicture.MAX_ASPECT, PictureInPicture.aspect(4f)!!.toFloat(), 0.001f)
        assertEquals(1f / PictureInPicture.MAX_ASPECT, PictureInPicture.aspect(0.2f)!!.toFloat(), 0.001f)
        // Always inside 1:2.39 to 2.39:1, which Android checks.
        for (r in listOf(0.01f, 0.4184f, 0.42f, 1f, 2.39f, 2.4f, 100f)) {
            val a = PictureInPicture.aspect(r)!!.toFloat()
            assertTrue("$r gave $a", a <= 2.39f && a >= 1f / 2.39f)
        }
        // Not known yet: no shape at all, rather than a made-up one.
        assertNull(PictureInPicture.aspect(0f))
        assertNull(PictureInPicture.aspect(Float.NaN))
        assertNull(PictureInPicture.aspect(-1f))
    }

    @Test fun theButtonsFollowThePlayState() {
        val playing = PictureInPicture.buttons(playing = true, hasNext = true)
        assertEquals(
            listOf(PictureInPicture.Control.PREVIOUS, PictureInPicture.Control.PAUSE, PictureInPicture.Control.NEXT),
            playing.map { it.control },
        )
        assertTrue(playing.all { it.enabled })
        val paused = PictureInPicture.buttons(playing = false, hasNext = false)
        assertEquals(
            listOf(PictureInPicture.Control.PREVIOUS, PictureInPicture.Control.PLAY, PictureInPicture.Control.NEXT),
            paused.map { it.control },
        )
        // Nothing after this song: Next is there but can't be pressed.
        assertFalse(paused.last().enabled)
        // Each button is told apart by its code alone.
        PictureInPicture.Control.entries.forEach { assertEquals(it, PictureInPicture.Control.of(it.code)) }
        assertNull(PictureInPicture.Control.of(-1))
    }

    @Test fun theWindowIsToldItsShapeItsPlaceAndItsButtons() {
        val spec = PictureInPicture.Spec(
            aspect = Rational(16, 9), sourceRect = Rect(60, 400, 1020, 940),
            playing = true, hasNext = true, autoEnter = true, title = "Midnight City", subtitle = "M83",
        )
        val params = PictureInPicture.params(context, spec)
        assertEquals(Rational(16, 9), params.aspectRatio)
        assertEquals(Rect(60, 400, 1020, 940), params.sourceRectHint)
        assertTrue(params.isAutoEnterEnabled)
        assertFalse(params.isSeamlessResizeEnabled)
        assertEquals(listOf("Previous", "Pause", "Next"), params.actions.map { it.title.toString() })
        assertEquals(listOf("Previous", "Pause", "Next"), params.actions.map { it.contentDescription.toString() })
        assertEquals("Midnight City", params.title.toString())

        val paused = PictureInPicture.params(context, spec.copy(playing = false, hasNext = false, autoEnter = false))
        assertEquals(listOf("Previous", "Play", "Next"), paused.actions.map { it.title.toString() })
        assertFalse(paused.actions.last().isEnabled)
        assertFalse(paused.isAutoEnterEnabled)

        // The buttons' broadcasts go to this app only, and can't be changed by whoever holds them.
        val intent = shadowOf(paused.actions.first().actionIntent).savedIntent
        assertEquals(PictureInPicture.ACTION, intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertTrue(shadowOf(paused.actions.first().actionIntent).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test fun theButtonShowsOnlyForASongPlayingAsItsVideo() {
        assertTrue(PictureInPicture.buttonShown(videoMode = true, available = true))
        // Audio, or video mode off: no button.
        assertFalse(PictureInPicture.buttonShown(videoMode = false, available = true))
        // The phone has no picture-in-picture, or it's turned off for the app.
        assertFalse(PictureInPicture.buttonShown(videoMode = true, available = false))
    }

    @Test fun homeGoesIntoTheWindowOnlyWhileAVideoPlaysInThePlayer() {
        assertTrue(PictureInPicture.autoEnter(allowed = true, videoMode = true, playerShowsVideo = true, playing = true, inWindow = false))
        // Never for audio.
        assertFalse(PictureInPicture.autoEnter(allowed = true, videoMode = false, playerShowsVideo = true, playing = true, inWindow = false))
        // Not with the player closed, or on its lyrics.
        assertFalse(PictureInPicture.autoEnter(allowed = true, videoMode = true, playerShowsVideo = false, playing = true, inWindow = false))
        // Not paused.
        assertFalse(PictureInPicture.autoEnter(allowed = true, videoMode = true, playerShowsVideo = true, playing = false, inWindow = false))
        // Not where it's turned off, nor when already in it.
        assertFalse(PictureInPicture.autoEnter(allowed = false, videoMode = true, playerShowsVideo = true, playing = true, inWindow = false))
        assertFalse(PictureInPicture.autoEnter(allowed = true, videoMode = true, playerShowsVideo = true, playing = true, inWindow = true))
    }

    @Test fun aPhoneWithoutPictureInPictureIsNotOfferedIt() {
        val pm = shadowOf(context.packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, false)
        assertFalse(PictureInPicture.isAllowed(context))
        pm.setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        assertTrue(PictureInPicture.isAllowed(context))
    }

    @Test fun thePlacesShowingTheVideoAreCountedApart() {
        val sheet = Any()
        val docked = Any()
        PictureInPicture.showing(sheet, true)
        PictureInPicture.showing(docked, true)
        PictureInPicture.showing(docked, false)
        assertTrue(PictureInPicture.showing.value.isNotEmpty())
        PictureInPicture.showing(sheet, false)
        assertTrue(PictureInPicture.showing.value.isEmpty())
    }

    @Test fun inASquareLayoutTheVideoBoxKeepsInsideTheCoversSquare() {
        // Wide: as wide as the cover, the picture's height, shown whole.
        assertEquals(VideoBoxSpec(360f, 202.5f, crop = false), videoBoxSpec(360f, 500f, 16f / 9f, fill = false))
        // Tall: as tall as the cover, shown whole.
        assertEquals(VideoBoxSpec(202.5f, 360f, crop = false), videoBoxSpec(360f, 500f, 9f / 16f, fill = false))
        // Not known yet: taken as 16:9.
        assertEquals(VideoBoxSpec(360f, 202.5f, crop = false), videoBoxSpec(360f, 500f, 0f, fill = false))
    }
}
