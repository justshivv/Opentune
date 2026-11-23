// The parts of the phone's com.opentune.ui.components that the shared interface
// files reach for and that lean on Android: glass, haptics. See Downloads.kt for
// why these files sit here rather than under their packages' folders.
package com.opentune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.GlassStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The content layer that floating glass blurs, provided by the window. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Liquid Glass's recorded layer on the phone. The desktop has no lens shaders,
 * so nothing provides one and the chrome draws its frosted look.
 */
val LocalBackdrop = staticCompositionLocalOf<Any?> { null }

/** Liquid Glass needs Android's runtime shaders. */
@Composable
fun liquidGlassOn(): Boolean = false

/**
 * The phone's glass: Liquid Glass, as the web player's (the Liquid Glass style, the default),
 * with blur on; Frosted (once called Liquid) is the frost alone here, as the desktop has no
 * lens for Apple's look: what's behind, blurred through Haze, with the same hairline edge.
 * Solid with blur reduced or with nothing behind to blur.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val haze = LocalHazeState.current
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    if (haze != null && !ui.reduceBlur && ui.glassStyle == GlassStyle.WEB) {
        val film = if (tint.alpha < 1f) tint.copy(alpha = minOf(tint.alpha, 0.55f)) else tint.copy(alpha = if (dark) 0.42f else 0.5f)
        return this.liquidGlass(haze, shape, film).clip(shape)
    }
    val base = this.clip(shape)
    val filled = if (haze == null || ui.reduceBlur) {
        base.background(if (tint.alpha < 1f) tint else tint.copy(alpha = 0.96f))
    } else {
        base.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = MaterialTheme.colorScheme.background,
                tint = HazeTint(if (tint.alpha < 1f) tint else tint.copy(alpha = FROST_FILM)),
                blurRadius = FROST_BLUR,
                noiseFactor = 0f,
            ),
        )
    }
    return filled.border(0.75.dp, edge, shape)
}

/** Frosted's frost, as the phone draws it without a lens. */
private val FROST_BLUR = 20.dp
private const val FROST_FILM = 0.30f

/** The phone's haptics. A computer has nothing to buzz, so each is a no-op. */
class Haptics {
    enum class Pattern { SHUFFLE, REPEAT_ALL, REPEAT_ONE, AUTOPLAY, OFF, DONE, PLAY, PAUSE, NEXT, PREVIOUS, LIKE }

    fun tick() = Unit
    fun press() = Unit
    @Suppress("UNUSED_PARAMETER")
    fun pattern(p: Pattern) = Unit
}

@Composable
fun rememberHaptics(): Haptics = remember { Haptics() }

/**
 * The phone's Poster layout, as far as the shared chrome (BottomChrome.kt) reaches for it.
 * The desktop has its own window and pages and no Poster layout, so it's always off here.
 */
@Composable
internal fun posterLayout(): Boolean = false

/** The Poster layout's smoked glass; on the desktop, the frosted glass. */
@Composable
internal fun Modifier.posterSmoke(shape: Shape): Modifier = glass(shape)

/** The smoke the phone's Poster dock is made of, which its dock's Liquid Glass ink takes too. */
internal val POSTER_SMOKE = Color(0xFF2A2D32)

/** What's drawn on the Poster layout's smoked glass. */
internal object PosterInk {
    val on = Color.White
    val off = Color.White.copy(alpha = 0.62f)
}

/** How far the Poster chrome is folded by a scroll; never on the desktop. */
internal val LocalPosterFold = staticCompositionLocalOf<() -> Float> { { 0f } }

/** The phone's recording for a raised lens to bend; the desktop has no lens shaders, so none. */
class LensSource private constructor()

@Composable
fun rememberLensSource(): LensSource? = null

fun Modifier.lensSource(source: LensSource?): Modifier = this

/** A raised piece of clear glass: on the desktop, a clear face with a light sheen. */
fun Modifier.raisedLens(source: LensSource?, shape: Shape, dark: Boolean): Modifier =
    this.clip(shape).background(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.06f)))
    )
