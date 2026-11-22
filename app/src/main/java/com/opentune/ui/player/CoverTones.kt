package com.opentune.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import kotlin.math.max
import kotlin.math.min

/**
 * The cover's own colours, made ready for the player's backgrounds. The player's
 * colour scheme is built from one colour of the cover in Material's "vibrant"
 * style, which turns hues and pushes colour up, so a background drawn from it
 * could look little like the cover. These keep each colour's own hue and
 * colourfulness and only set how light it is: [deep] behind the cover and title,
 * [second] the cover's next colour as deep, [base] the near-black the controls
 * sit on (still faintly the cover's hue), and [lights] for glows.
 */
@Immutable
internal class CoverTones(val deep: Color, val second: Color, val base: Color, val lights: List<Color>)

internal fun coverTones(palette: List<Color>, scheme: ColorScheme): CoverTones {
    if (palette.isEmpty()) {
        return CoverTones(scheme.primaryContainer, scheme.secondaryContainer, scheme.surface,
            listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.inversePrimary))
    }
    val hct = palette.map { Hct.fromInt(it.toArgb()) }
    fun at(c: Hct, tone: Double, maxChroma: Double) = Color(Hct.from(c.hue, min(c.chroma, maxChroma), tone).toInt())
    val first = hct[0]
    // A cover of one colour: its second is the same hue a little turned, so the two still differ.
    val other = hct.getOrNull(1) ?: Hct.from((first.hue + 28) % 360, first.chroma, first.tone)
    val lights = List(4) { i -> at(hct[i % hct.size], LIGHT_TONE, 90.0) }
    return CoverTones(
        deep = at(first, DEEP_TONE, 80.0),
        second = at(other, SECOND_TONE, 80.0),
        base = at(first, BASE_TONE, 12.0),
        lights = lights,
    )
}

/** [coverTones] for the cover at [artworkUrl], easing from one song's to the next. */
@Composable
internal fun rememberCoverTones(artworkUrl: String?): CoverTones {
    val palette = com.opentune.ui.theme.rememberArtworkPalette(artworkUrl)
    val scheme = MaterialTheme.colorScheme
    val target = remember(palette, scheme) { coverTones(palette, scheme) }
    val spec = tween<Color>(900)
    val deep by animateColorAsState(target.deep, spec, label = "coverDeep")
    val second by animateColorAsState(target.second, spec, label = "coverSecond")
    val base by animateColorAsState(target.base, spec, label = "coverBase")
    val l0 by animateColorAsState(target.lights[0], spec, label = "coverLight0")
    val l1 by animateColorAsState(target.lights[1], spec, label = "coverLight1")
    val l2 by animateColorAsState(target.lights[2], spec, label = "coverLight2")
    val l3 by animateColorAsState(target.lights[3], spec, label = "coverLight3")
    return CoverTones(deep, second, base, listOf(l0, l1, l2, l3))
}

/** How light each is, on HCT's 0 (black) to 100 (white) scale. */
private const val DEEP_TONE = 32.0
private const val SECOND_TONE = 26.0
private const val BASE_TONE = 7.0
private const val LIGHT_TONE = 58.0

/**
 * The liked heart's colour, taken from the cover: its first colour with real
 * colour in it (the palette puts colourful ones first), kept at its hue but
 * set light enough to stand out on the player's dark buttons ([onDark]), or
 * dark enough on light ones, and pushed up to a clear colourfulness so a
 * muted cover still gives a heart that reads as coloured. Null for a cover
 * that is all greys, or not read yet, where the heart keeps its usual colour.
 */
internal fun coverLikeColor(palette: List<Color>, onDark: Boolean = true): Color? {
    val pick = palette.map { Hct.fromInt(it.toArgb()) }.firstOrNull { it.chroma >= LIKE_MIN_SOURCE_CHROMA } ?: return null
    val tone = if (onDark) LIKE_TONE_ON_DARK else LIKE_TONE_ON_LIGHT
    return Color(Hct.from(pick.hue, max(pick.chroma, LIKE_MIN_CHROMA), tone).toInt())
}

/**
 * [coverLikeColor] for the cover at [artworkUrl], or [fallback], easing from one
 * song's to the next as the player's other cover colours do. While the next
 * cover is still being read the last song's colour stays, rather than
 * flashing back to [fallback] in between.
 */
@Composable
internal fun rememberCoverLikeColor(artworkUrl: String?, fallback: Color): Color {
    val palette = com.opentune.ui.theme.rememberArtworkPalette(artworkUrl)
    val onDark = LocalContentColor.current.luminance() > 0.5f
    // Plain memory, not state: it only bridges the gap and never needs to recompose anything.
    val last = remember { LastLikeColor() }
    // An opt-in extra (Settings > Like button colour): off, the heart keeps its usual colour.
    val coloured = com.opentune.data.settings.AppSettings.ui.collectAsState().value.likeCoverColor
    val target = if (!coloured) fallback
    else if (palette.isEmpty()) last.color ?: fallback
    else (coverLikeColor(palette, onDark) ?: fallback).also { last.color = it }
    return animateColorAsState(target, tween(900), label = "coverLike").value
}

private class LastLikeColor { var color: Color? = null }

/** A cover colour below this chroma counts as grey. */
private const val LIKE_MIN_SOURCE_CHROMA = 10.0
/** The heart's least colourfulness, so it never reads as grey. */
private const val LIKE_MIN_CHROMA = 52.0
/** Its lightness on dark buttons and on light ones. */
private const val LIKE_TONE_ON_DARK = 70.0
private const val LIKE_TONE_ON_LIGHT = 40.0
