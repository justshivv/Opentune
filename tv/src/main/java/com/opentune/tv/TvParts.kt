package com.opentune.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Pieces shared by the detail pages and Now Playing, built from TvKit and the tokens in TvTheme:
 * a page's header with its cover, a section title, a small tag. Nothing here moves or is focusable
 * on its own; the buttons a header holds are the kit's.
 */

/** The size of a detail page's cover: about 40% of the screen's height. */
val TvHeaderCover: Dp = 232.dp

/**
 * The top of an album, playlist or artist page: the cover (round for an artist) at the left, and
 * beside it a small [kind] line ("Album"), the [title] large, a dim [subtitle], a [note] (an
 * artist's bio, two lines at most) and the page's [actions] (Play, Shuffle). In from the sides.
 */
@Composable
fun TvDetailHeader(
    title: String,
    modifier: Modifier = Modifier,
    kind: String? = null,
    subtitle: String? = null,
    note: String? = null,
    art: String? = null,
    round: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.safeSides(), verticalAlignment = Alignment.CenterVertically) {
        Cover(art, Modifier.size(TvHeaderCover), if (round) TvShapes.Round else TvShapes.Card, px = TvArt.Large)
        Spacer(Modifier.width(TvSpace.XL))
        Column(Modifier.weight(1f)) {
            if (!kind.isNullOrBlank()) Text(kind, style = TvType.Meta, color = TvColors.TextDim, maxLines = 1)
            Text(title, style = TvType.Display, color = TvColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, style = TvType.Body, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TvSpace.XS),
            )
            if (!note.isNullOrBlank()) Text(
                note, style = TvType.Meta, color = TvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TvSpace.S),
            )
            if (actions != null) Row(
                Modifier.padding(top = TvSpace.L),
                horizontalArrangement = Arrangement.spacedBy(TvSpace.M),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** A section's title inside a page ("Songs"), in line with the page's text. */
@Composable
fun TvSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = TvType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.safeSides())
}

/**
 * A small solid tag with a word or two: "Dolby Atmos" on Now Playing, "Loading…" while the song
 * comes in. [strong] fills it light with dark ink, for the one tag that should stand out.
 */
@Composable
fun TvTag(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        text, style = TvType.Meta, maxLines = 1,
        color = if (strong) TvColors.Background else TvColors.Text,
        modifier = modifier
            .background(if (strong) TvColors.Text else TvColors.SurfaceTop, TvShapes.Small)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** A still placeholder bar for a line of text that is on its way. */
@Composable
internal fun TvPlaceholderLine(width: Dp, height: Dp = 18.dp, color: Color = TvColors.Surface) {
    Spacer(Modifier.size(width, height).background(color, TvShapes.Small))
}

/** A gap of [height], for spacing between a page's items where the list's own spacing is too small. */
@Composable
internal fun TvGap(height: Dp) = Spacer(Modifier.height(height))
