package com.opentune.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Glass on the page must never draw the page into itself: Skia then drew it inside itself
 * until the stack ran out, and the app closed a few seconds after it started.
 */
class LiquidGlassTest {
    @Test
    fun glassKnowsTheAreaItSitsInAndLeavesItOut() {
        val haze = HazeState()
        val inPage = GlassSource()
        val onTop = GlassSource()
        val scene = ImageComposeScene(400, 300, Density(1f)) {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().hazeSource(haze, zIndex = 0f))
                    Box(Modifier.fillMaxSize().hazeSource(haze, zIndex = 1f)) {
                        Box(Modifier.size(40.dp).liquidGlass(haze, CircleShape, Color.Gray, inPage))
                    }
                    Box(Modifier.size(60.dp).liquidGlass(haze, CircleShape, Color.Gray, onTop))
                }
            }
        }
        try {
            repeat(3) { scene.render(it * 16_000_000L) }
            assertEquals(1f, inPage.zIndex)
            assertNull(onTop.zIndex)
            assertEquals(2, haze.areas.size)
            // In the page: only the backdrop under it. Over everything: both.
            assertEquals(listOf(0f), drawableAreas(haze.areas, inPage.zIndex) { false }.map { it.zIndex })
            assertEquals(listOf(0f, 1f), drawableAreas(haze.areas, onTop.zIndex) { false }.map { it.zIndex })
        } finally {
            scene.close()
        }
    }
}
