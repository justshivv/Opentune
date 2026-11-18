package com.opentune.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverLikeColorTest {
    private fun hct(c: Color) = Hct.fromInt(c.toArgb())
    private fun hueGap(a: Double, b: Double) = abs(a - b).let { minOf(it, 360 - it) }

    @Test fun noCoverYetGivesNothing() {
        assertNull(coverLikeColor(emptyList()))
    }

    @Test fun anAllGreyCoverGivesNothing() {
        assertNull(coverLikeColor(listOf(Color(0xFF808080), Color(0xFF111111), Color(0xFFEEEEEE))))
    }

    @Test fun keepsTheCoversHue() {
        val blue = Color(0xFF1E4FD8)
        val heart = hct(coverLikeColor(listOf(blue))!!)
        assertTrue(hueGap(heart.hue, hct(blue).hue) < 8)
    }

    @Test fun skipsGreysForTheFirstRealColour() {
        val green = Color(0xFF2E9E3A)
        val heart = hct(coverLikeColor(listOf(Color(0xFF777777), green))!!)
        assertTrue(hueGap(heart.hue, hct(green).hue) < 8)
    }

    @Test fun aVeryDarkCoverStillGivesALightHeart() {
        val nearBlackRed = Color(0xFF2A0508)
        val heart = hct(coverLikeColor(listOf(nearBlackRed), onDark = true)!!)
        assertEquals(70.0, heart.tone, 1.5)
        assertTrue("clearly coloured", heart.chroma > 40)
    }

    @Test fun aPaleCoverIsDarkenedOnLightButtons() {
        val paleYellow = Color(0xFFF6EBA8)
        val heart = hct(coverLikeColor(listOf(paleYellow), onDark = false)!!)
        assertEquals(40.0, heart.tone, 1.5)
    }

    @Test fun aMutedCoverIsMadeMoreColourful() {
        val dustyBlue = Color(0xFF6C7A8C)
        val source = hct(dustyBlue)
        assertTrue(source.chroma in 10.0..30.0)
        val heart = hct(coverLikeColor(listOf(dustyBlue))!!)
        assertTrue(heart.chroma > source.chroma + 15)
    }
}
