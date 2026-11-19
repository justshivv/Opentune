package com.opentune.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/*
 * The web player's colours for the window's chrome, from its stylesheets
 * (web/app/src/styles/tokens.css and opentune.css), worked out from this
 * theme's own ink so they follow light and dark as the web's do.
 */

/** Whether the theme in force is a dark one. */
@Composable
@ReadOnlyComposable
internal fun darkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** The web's quinary fill: the soft wash under a hovered row or button. */
@Composable
@ReadOnlyComposable
internal fun quinaryFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme()) 0.05f else 0.045f)

/** The web's quaternary fill: a hovered button that already has a soft fill, or a track a line runs along. */
@Composable
@ReadOnlyComposable
internal fun quaternaryFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

/**
 * The web's key colour, made from the theme's accent as opentune.css makes it:
 * pale on dark, deep on light, so it reads on the glass either way.
 */
@Composable
internal fun keyColour(): Color {
    val seed = railAccent()
    return if (darkTheme()) lerp(Color(0xFFFFE0D5), seed, 0.32f) else lerp(Color(0xFF44211B), seed, 0.65f)
}

/** The fill under the page showing in the sidebar (navSidebarSelectedState). */
@Composable
internal fun selectedFill(): Color =
    if (darkTheme()) keyColour().copy(alpha = 0.17f) else railAccent().copy(alpha = 0.14f)

/** OpenTune's mark, the wave alone without its tile, in [tint], as the web's sidebar and player show it bare. */
@Composable
internal fun LogoMark(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    // The wave is wider than tall; fitted into a square box it sits in the middle, as the web's does.
    Image(rememberLogo(Logo.MARK), null, modifier.size(size), contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(tint))
}

/**
 * The glass the sidebar and the column beside the page are made of (the web's
 * glass-material): what's behind them, the window's backdrop, blurred 36dp
 * under a wash of the sidebar's colour. Thinner over Windows' own material,
 * which then does the work; a plain see-through fill with blur reduced.
 */
@Composable
internal fun Modifier.chromeGlass(haze: HazeState?): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val material by WindowsFrame.activeMaterial.collectAsState()
    val over = material != WindowsFrame.Material.OFF
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    if (haze == null || ui.reduceBlur) return background(base.copy(alpha = if (over) 0.32f else 0.74f))
    return hazeEffect(
        haze,
        HazeStyle(
            backgroundColor = MaterialTheme.colorScheme.background,
            tint = HazeTint(base.copy(alpha = if (over) 0.3f else 0.62f)),
            blurRadius = 36.dp,
            noiseFactor = 0f,
        ),
    )
}

/** The web's 0.5px hairline where the glass meets the page, a touch stronger on light. */
@Composable
@ReadOnlyComposable
internal fun glassEdge(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme()) 0.08f else 0.10f)

/*
 * The window's chrome as the sidebar-and-bar layout draws it: flat greys off the theme's ink, a
 * 1px hairline between the sidebar and the page, white text with a 60% grey under it.
 */
internal object ChromeInk {
    /** The ink of a row or icon that isn't chosen: a grey-white, not the full white. */
    @Composable @ReadOnlyComposable
    fun idle(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme()) 0.78f else 0.74f)

    /** Second lines: an artist, a handle. */
    @Composable @ReadOnlyComposable
    fun secondary(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    /** The search field's fill and the now-playing box's. */
    @Composable @ReadOnlyComposable
    fun field(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme()) 0.07f else 0.06f)

    @Composable @ReadOnlyComposable
    fun lcd(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme()) 0.06f else 0.05f)

    /** The grey pill under the page showing. */
    @Composable @ReadOnlyComposable
    fun pill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme()) 0.10f else 0.08f)

    /** The faint fill under the pointer. */
    @Composable @ReadOnlyComposable
    fun hover(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme()) 0.05f else 0.045f)

    /** Hairlines: the sidebar's edge, its dividers, the line over Settings. */
    @Composable @ReadOnlyComposable
    fun hairline(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme()) 0.09f else 0.11f)
}

/**
 * The sidebar's own surface: the page's grey a shade darker, nearly solid, so the cover's
 * colour behind the window barely tints it. Thinner over Windows' material, which then shows.
 */
@Composable
internal fun Modifier.chromeSidebarSurface(haze: HazeState?): Modifier {
    val ui by AppSettings.ui.collectAsState()
    val material by WindowsFrame.activeMaterial.collectAsState()
    val over = material != WindowsFrame.Material.OFF
    val page = MaterialTheme.colorScheme.background
    val base = if (darkTheme()) lerp(page, Color.Black, 0.12f) else lerp(page, Color.Black, 0.025f)
    if (haze == null || ui.reduceBlur) return background(base.copy(alpha = if (over) 0.4f else 1f))
    return hazeEffect(
        haze,
        HazeStyle(backgroundColor = page, tint = HazeTint(base.copy(alpha = if (over) 0.4f else 0.9f)), blurRadius = 36.dp, noiseFactor = 0f),
    )
}
