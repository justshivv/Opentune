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
