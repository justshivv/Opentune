package com.opentune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.ui.theme.Text

// The Poster layout (Settings › Page layout): covers and photos across the top of the page,
// big titles, spaced capitals and clear glass buttons. These are its shared pieces.

/** Whether album, playlist and artist pages wear the Poster layout (Settings › Page layout). */
@Composable
internal fun posterLayout(): Boolean =
    com.opentune.data.settings.AppSettings.ui.collectAsState().value.pageLayout == com.opentune.data.settings.PageLayout.POSTER

/** A line in the Poster layout's spaced capitals: "ALBUM · 9 SONGS · 42 MIN", an artist's name under a title. */
@Composable
internal fun PosterCaps(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text.uppercase(),
        style = style.copy(letterSpacing = 3.sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = color,
        textAlign = textAlign,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The Poster layout's glass, as Settings › Glass style asks. Liquid Glass (the default) is the
 * web player's ([webLiquidGlass]): the page behind bent at the rim and only lightly blurred,
 * under a clear film (a light one on a light theme). Frosted is the app's own [glass], as the
 * Classic layout has it.
 */
@Composable
internal fun Modifier.posterGlass(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val style = com.opentune.data.settings.AppSettings.ui.collectAsState().value.glassStyle
    if (style != com.opentune.data.settings.GlassStyle.WEB) return this.glass(shape).clip(shape)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return this.webLiquidGlass(shape, webFilm(dark)).clip(shape)
}

/** The Poster layout's Play: a wide glass pill with the word in it (or an accent one, as Settings › Page buttons asks). */
@Composable
internal fun PosterPill(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    val ink = pageButtonInk()
    Row(
        modifier.height(56.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pageButtonFace(shape, poster = true).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = ink)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

/** The Poster layout's round glass button, for shuffle, save and the like (or an accent one, as Settings › Page buttons asks). */
@Composable
internal fun PosterRound(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(POSTER_ROUND).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pageButtonFace(androidx.compose.foundation.shape.CircleShape, poster = true)
            .clickable(enabled = enabled, onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = pageButtonInk()) }
}

internal val POSTER_ROUND = 52.dp

/** The smoke the Poster layout's dock and mini player are made of. */
internal val POSTER_SMOKE = Color(0xFF2A2D32)

/**
 * The Poster layout's smoked glass, for the dock and the mini player: the glass Settings › Glass
 * style asks for, as its buttons have it, under a dark film whatever the theme. What's on it is
 * drawn light (see [PosterInk]), so the film is as dark as the style needs for that to read.
 */
@Composable
internal fun Modifier.posterSmoke(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    val smoke = posterSmokeFilm(ui.glassStyle)
    // With nothing blurred behind, the glass is the theme's surface under this film; on a light
    // theme a thin one left it mid-grey under white ink, so then it's nearly the smoke itself.
    val solid = ui.reduceBlur || (LocalBackdrop.current == null && LocalHazeState.current == null)
    val film = if (solid) smoke.copy(alpha = maxOf(smoke.alpha, 0.88f)) else smoke
    // Liquid Glass is the web player's, about half strength as its dock has.
    if (ui.glassStyle == com.opentune.data.settings.GlassStyle.WEB) return this.webLiquidGlass(shape, film).clip(shape)
    // The card is dragged about, so the glass doesn't swell under a finger (as the Classic one doesn't).
    return this.glass(shape, tint = film, swell = false).clip(shape)
}

/**
 * The dark film over [posterSmoke] for each [GlassStyle]: Liquid Glass's own, and a little more
 * for Frosted, whose frost blurs what's behind into a paler field of its colours, so light ink
 * on it reads as well as on Liquid Glass.
 */
internal fun posterSmokeFilm(style: com.opentune.data.settings.GlassStyle): Color = when (style) {
    com.opentune.data.settings.GlassStyle.WEB -> POSTER_SMOKE.copy(alpha = 0.42f)
    com.opentune.data.settings.GlassStyle.LIQUID -> POSTER_SMOKE.copy(alpha = 0.5f)
}

/** What's drawn on [posterSmoke]: white, and a softer white for what's not picked. */
internal object PosterInk {
    val on = Color.White
    val off = Color.White.copy(alpha = 0.62f)
}

/**
 * How far the Poster chrome is folded by a scroll, 0 open to 1 folded, read by the mini player
 * under it: folded, its cover turns round.
 */
internal val LocalPosterFold = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 0f } }
