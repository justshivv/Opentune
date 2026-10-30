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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
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
 * The phone's frosted glass: what's behind, blurred through Haze and washed
 * with the chosen glass style, with the same hairline edge; solid with blur
 * reduced or with nothing behind to blur.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val haze = LocalHazeState.current
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val look = glassLook(ui.glassStyle)
    val colour = if (tint.alpha < 1f) tint else styleTint(ui.glassStyle, tint, MaterialTheme.colorScheme.primary, dark)
    val base = this.clip(shape)
    val filled = if (haze == null || ui.reduceBlur) {
        base.background(if (tint.alpha < 1f) tint else colour.copy(alpha = 0.96f))
    } else {
        base.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = MaterialTheme.colorScheme.background,
                tint = HazeTint(if (tint.alpha < 1f) tint else colour.copy(alpha = look.tintAlpha)),
                blurRadius = look.blur,
                noiseFactor = look.noise,
            ),
        )
    }
    return filled.border(0.75.dp, edge, shape)
}

/** The numbers behind a [GlassStyle], as the phone's frosted glass uses them. */
internal data class GlassLook(val blur: Dp, val tintAlpha: Float, val noise: Float)

internal fun glassLook(style: GlassStyle): GlassLook = when (style) {
    GlassStyle.FROSTED -> GlassLook(28.dp, 0.62f, 0.06f)
    GlassStyle.CLEAR -> GlassLook(16.dp, 0.30f, 0f)
    GlassStyle.HEAVY -> GlassLook(44.dp, 0.82f, 0.09f)
    GlassStyle.TINTED -> GlassLook(30.dp, 0.58f, 0.05f)
    GlassStyle.SMOKE -> GlassLook(34.dp, 0.70f, 0.04f)
    GlassStyle.LIQUID -> GlassLook(20.dp, 0.30f, 0f)
}

/** The colour a [GlassStyle] washes the glass with, from the surface [base] and the [accent]. */
internal fun styleTint(style: GlassStyle, base: Color, accent: Color, dark: Boolean): Color = when (style) {
    GlassStyle.FROSTED, GlassStyle.CLEAR, GlassStyle.HEAVY, GlassStyle.LIQUID -> base
    GlassStyle.TINTED -> lerp(base, accent, if (dark) 0.32f else 0.22f)
    GlassStyle.SMOKE -> if (dark) lerp(base, Color.Black, 0.65f) else lerp(base, Color(0xFF3A3A42), 0.3f)
}

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
