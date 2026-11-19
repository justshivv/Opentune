package com.opentune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PageButtons

// The buttons in a playlist, album, Liked or artist page's header (Play, Shuffle, Save, Follow),
// made of what Settings › Page buttons asks: glass, or the page's accent colour.
//
// Glass can't sample the layer it is drawn in, and these buttons scroll inside the page's list,
// which the app shell and GlassPage record whole. So the page records, on their own, the two
// things that lie behind them: its background, behind the list (recordPageBackdrop), and the
// header's picture, the cover or the artist's photo, in a box beside the buttons rather than
// around them. The buttons refract the two together, so they bend the real cover and wash.

/** A page header's buttons filled with its accent colour: the fill and an ink that reads on it. */
@Immutable
data class PageAccent(val fill: Color, val ink: Color)

/** Given by a page's header to the buttons in it while Settings › Page buttons says Accent colour; null everywhere else. */
val LocalPageAccent = compositionLocalOf<PageAccent?> { null }

/** The page's own background, recorded behind its list for the header's glass; null while nothing records it. */
val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Whether a page records what's behind its header buttons: they're glass (Settings › Page
 * buttons), and the phone can blur a recorded layer (Android 12+, blur not reduced). Liquid
 * Glass on top of that needs Android 13; on 12 the buttons are frosted glass over the recording.
 */
@Composable
fun pageGlassOn(): Boolean {
    val ui by AppSettings.ui.collectAsState()
    return ui.pageButtons == PageButtons.GLASS && backdropBlurOn()
}

/**
 * A page whose header buttons may be glass: gives [content] a layer for the page's background
 * ([recordPageBackdrop]) while [pageGlassOn], and nothing otherwise, so nothing is recorded.
 */
@Composable
fun PageBackdropScope(content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val on = pageGlassOn()
    CompositionLocalProvider(LocalPageBackdrop provides backdrop.takeIf { on }, content = content)
}

/** Records what this draws, the page's background behind its list, for the header's glass ([PageBackdropScope]). */
@Composable
fun Modifier.recordPageBackdrop(): Modifier {
    val backdrop = LocalPageBackdrop.current ?: return this
    return this.layerBackdrop(backdrop)
}

/**
 * A page's background with nothing of its own to paint (a plain page): its colour alone,
 * drawn only while it is recorded for the header's glass, over the same colour already there.
 */
@Composable
fun PlainPageBackdrop() {
    if (LocalPageBackdrop.current == null) return
    Box(Modifier.fillMaxSize().recordPageBackdrop().background(MaterialTheme.colorScheme.background))
}

/**
 * What a header's glass buttons refract: the page's background ([LocalPageBackdrop]) with the
 * header's [picture] over it, when the header records one (see [Modifier.recordHeaderPicture]).
 * Null while nothing is recorded, when the buttons keep their plain glass.
 */
@Composable
fun rememberHeaderBackdrop(picture: LayerBackdrop?): Backdrop? {
    val page = LocalPageBackdrop.current
    val on = pageGlassOn()
    val both = rememberCombinedBackdrop(page ?: emptyBackdrop(), picture ?: emptyBackdrop())
    return both.takeIf { on && (page != null || picture != null) }
}

/** Records the header's picture into [picture] for the buttons beside it, while [backdrop] (from [rememberHeaderBackdrop]) is in use. */
fun Modifier.recordHeaderPicture(picture: LayerBackdrop, backdrop: Backdrop?): Modifier =
    if (backdrop == null) this else this.layerBackdrop(picture)

/**
 * The buttons over a page's header: their glass refracts [backdrop] (the outer one where the
 * header records nothing), or with Accent colour chosen they're filled with [accent].
 */
@Composable
fun HeaderButtonsScope(backdrop: Backdrop?, accent: PageAccent?, content: @Composable () -> Unit) {
    val ui by AppSettings.ui.collectAsState()
    CompositionLocalProvider(
        LocalBackdrop provides (backdrop ?: LocalBackdrop.current),
        LocalPageAccent provides accent.takeIf { ui.pageButtons == PageButtons.ACCENT },
        content = content,
    )
}

/**
 * The face of a button in a page's header, clipped to [shape]: with Accent colour chosen, the
 * page's accent with a faint light rim and no blur; otherwise glass, the Poster layout's
 * [posterGlass] when [poster] and the app's own [glass] when not.
 */
@Composable
internal fun Modifier.pageButtonFace(shape: Shape, poster: Boolean = posterLayout()): Modifier {
    val accent = LocalPageAccent.current
    if (accent != null) {
        return this.clip(shape).background(accent.fill).border(
            1.dp,
            // Lit a little more along the top, as light would catch it.
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.34f), 1f to Color.White.copy(alpha = 0.12f)),
            shape,
        )
    }
    return if (poster) this.posterGlass(shape) else this.glass(shape).clip(shape)
}

/** What's drawn on [pageButtonFace]: the accent's ink, or the page's own text colour on glass. */
@Composable
internal fun pageButtonInk(): Color = LocalPageAccent.current?.ink ?: MaterialTheme.colorScheme.onSurface

/** Black or white, whichever reads better on [fill]. */
internal fun inkOn(fill: Color): Color {
    val l = fill.luminance()
    // Contrast against white is 1.05 / (l + 0.05), against black (l + 0.05) / 0.05.
    return if ((l + 0.05f) * (l + 0.05f) > 1.05f * 0.05f) Color(0xFF111114) else Color.White
}
