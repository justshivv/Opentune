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
 * The Poster layout's glass: the web player's Liquid Glass ([webLiquidGlass]), the page behind
 * bent at the rim and only lightly blurred, under a clear film (a light one on a light theme).
 */
@Composable
internal fun Modifier.posterGlass(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return this.webLiquidGlass(shape, if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.3f)).clip(shape)
}

/** The Poster layout's Play: a wide glass pill with the word in it. */
@Composable
internal fun PosterPill(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    Row(
        modifier.height(56.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .posterGlass(shape).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** The Poster layout's round glass button, for shuffle, save and the like. */
@Composable
internal fun PosterRound(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(POSTER_ROUND).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .posterGlass(androidx.compose.foundation.shape.CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
}

internal val POSTER_ROUND = 52.dp

/** The smoke the Poster layout's dock and mini player are made of. */
internal val POSTER_SMOKE = Color(0xFF2A2D32)

/**
 * The Poster layout's smoked glass, for the dock and the mini player: the same Liquid Glass as
 * its buttons under a dark film, about half strength as the web player's dock has, whatever the
 * theme. What's on it is drawn light (see [PosterInk]).
 */
@Composable
internal fun Modifier.posterSmoke(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.webLiquidGlass(shape, POSTER_SMOKE.copy(alpha = 0.42f)).clip(shape)

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
