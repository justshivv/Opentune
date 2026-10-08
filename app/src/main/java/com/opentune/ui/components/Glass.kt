package com.opentune.ui.components

import android.os.Build
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.GlassStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The content layer that floating glass blurs, provided by the app shell. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * The layer Liquid Glass refracts. Only floating chrome drawn outside that
 * layer gets one (the nav pill, mini player, player buttons): glass inside
 * the layer it samples would sample itself.
 */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** How much Liquid Glass frosts what's behind it; Apple's bars sit around here. */
private val LIQUID_BLUR = 22.dp

/** Lens refraction needs runtime shaders, which arrived in Android 13. */
val liquidGlassSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

@Composable
fun liquidGlassOn(): Boolean {
    val ui by AppSettings.ui.collectAsState()
    // The Liquid style is Liquid Glass by definition, whatever the switch says.
    return liquidGlassSupported && (ui.liquidGlass || ui.glassStyle == GlassStyle.LIQUID) && !ui.reduceBlur
}

/**
 * Glass for floating controls, in three grades:
 *
 * - Liquid Glass (Android 13+, setting on): the content behind is blurred a
 *   little, bent at the edges by a lens and lifted by vibrancy, with a rim
 *   highlight, like Apple's material.
 * - Frosted (Android 12+): blurred and tinted via Haze.
 * - Solid: with "Reduce dynamic blur" on, or nothing to sample.
 *
 * Every grade keeps a hairline edge so the shape reads on any background.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val haze = LocalHazeState.current
    val backdrop = LocalBackdrop.current
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val look = glassLook(ui.glassStyle)
    // A caller's own see-through tint wins; otherwise the style colours the glass.
    val colour = if (tint.alpha < 1f) tint else styleTint(ui.glassStyle, tint, MaterialTheme.colorScheme.primary, dark)
    if (backdrop != null && liquidGlassOn() && ui.glassStyle == GlassStyle.LIQUID) {
        // Apple's material: hardly any frost, so what's behind stays sharp in
        // the middle; a deep lens at the edges that bends it and splits the
        // light into a colour fringe; vibrancy so colours glow through; a
        // bright specular rim and a soft inner shade that give the glass depth.
        val film = if (tint.alpha < 1f) tint else (if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.18f))
        return this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(look.liquidBlur.toPx())
                lens(20.dp.toPx(), 44.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow.Default },
            innerShadow = { InnerShadow.Default },
            onDrawSurface = { drawRect(film) },
        ).border(0.75.dp, Color.White.copy(alpha = if (dark) 0.16f else 0.4f), shape)
    }
    if (backdrop != null && liquidGlassOn()) {
        // A see-through dark (or light) film rather than the theme's grey, so
        // the colour behind comes through. OpenTune's film carries a little of
        // the accent, so the glass reads as part of the theme rather than plain smoke.
        val neutral = if (dark) Color(0xFF101014) else Color(0xFFF7F7FA)
        val film = when {
            tint.alpha < 1f -> tint
            ui.glassStyle == GlassStyle.FROSTED -> lerp(neutral, MaterialTheme.colorScheme.primary, 0.14f).copy(alpha = look.filmAlpha)
            else -> lerp(neutral, colour, 0.6f).copy(alpha = look.filmAlpha)
        }
        return this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // Saturated and frosted, then bent at the edges with a little
                // colour fringing, the way Apple's glass reads over artwork.
                colorControls(saturation = look.saturation)
                blur(look.liquidBlur.toPx())
                lens(24.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow.Default },
            onDrawSurface = { drawRect(film) },
        ).border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
    }
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

/** The numbers behind a [GlassStyle], for frosted glass and for Liquid Glass. */
internal data class GlassLook(
    val blur: Dp,
    val tintAlpha: Float,
    val noise: Float,
    val liquidBlur: Dp,
    val filmAlpha: Float,
    val saturation: Float,
)

internal fun glassLook(style: GlassStyle): GlassLook = when (style) {
    GlassStyle.FROSTED -> GlassLook(28.dp, 0.62f, 0.06f, LIQUID_BLUR, 0.42f, 1.5f)
    GlassStyle.CLEAR -> GlassLook(16.dp, 0.30f, 0f, 10.dp, 0.22f, 1.7f)
    GlassStyle.HEAVY -> GlassLook(44.dp, 0.82f, 0.09f, 36.dp, 0.64f, 1.2f)
    GlassStyle.TINTED -> GlassLook(30.dp, 0.58f, 0.05f, LIQUID_BLUR, 0.46f, 1.6f)
    GlassStyle.SMOKE -> GlassLook(34.dp, 0.70f, 0.04f, 26.dp, 0.58f, 1.1f)
    // Without Liquid Glass (older Android, or blur reduced) it's the clearest frost.
    GlassStyle.LIQUID -> GlassLook(14.dp, 0.24f, 0f, 2.dp, 0.06f, 1.8f)
}

/** The colour a [GlassStyle] washes the glass with, from the surface [base] and the [accent]. */
internal fun styleTint(style: GlassStyle, base: Color, accent: Color, dark: Boolean): Color = when (style) {
    GlassStyle.FROSTED, GlassStyle.CLEAR, GlassStyle.HEAVY, GlassStyle.LIQUID -> base
    GlassStyle.TINTED -> lerp(base, accent, if (dark) 0.32f else 0.22f)
    // Smoke stays dark in both themes, a little lighter in the light one so it isn't a hole.
    GlassStyle.SMOKE -> if (dark) lerp(base, Color.Black, 0.65f) else lerp(base, Color(0xFF3A3A42), 0.3f)
}

/**
 * A strip under the status bar where pages blur and fade out as they scroll
 * beneath it, strongest at the top edge and gone 28dp below the bar. With
 * blur reduced it's only the fade. Draws nothing when the setting is off.
 */
@Composable
fun TopEdgeFrost(modifier: Modifier = Modifier) {
    val ui by AppSettings.ui.collectAsState()
    if (!ui.frostedTopEdge) return
    val haze = LocalHazeState.current
    val page = MaterialTheme.colorScheme.background
    val height = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 28.dp
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (haze == null || ui.reduceBlur) Modifier else Modifier.hazeEffect(haze) {
                    backgroundColor = page
                    tints = emptyList()
                    blurRadius = 20.dp
                    noiseFactor = 0f
                    inputScale = HazeInputScale.Fixed(0.5f)
                    progressive = HazeProgressive.verticalGradient(easing = EaseOutCubic, startIntensity = 1f, endIntensity = 0f)
                },
            )
            .background(Brush.verticalGradient(0f to page.copy(alpha = 0.6f), 0.55f to page.copy(alpha = 0.25f), 1f to Color.Transparent)),
    )
}

/** A round glass button holding one icon, as used for back, menu and search. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(size).glass(CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (content != null) content() else Icon(icon, contentDescription, Modifier.size(size * 0.46f))
        }
    }
}

@Composable
fun GlassBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick, modifier)
}

/** A page's top: optional glass back button and a large title. Draws under the status bar inset. */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) GlassBackButton(onBack) else Box(Modifier.size(56.dp))
            Box(Modifier.weight(1f))
            actions?.invoke()
        }
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        )
    }
}
