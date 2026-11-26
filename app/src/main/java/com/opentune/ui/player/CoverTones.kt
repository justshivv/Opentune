package com.opentune.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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

/**
 * A cover's tones easing from one song's to the next: [target] is where they're going,
 * [now] where they are, to be read while drawing. Read while composing instead, the
 * player's whole backdrop was composed again on every frame of the fade.
 */
@Stable
internal class FadingCoverTones(
    val target: CoverTones,
    private val deep: State<Color>,
    private val second: State<Color>,
    private val base: State<Color>,
    private val lights: List<State<Color>>,
) {
    fun now(): CoverTones = CoverTones(deep.value, second.value, base.value, lights.map { it.value })
}

/** [coverTones] for the cover at [artworkUrl], easing from one song's to the next. */
@Composable
internal fun rememberCoverTones(artworkUrl: String?): FadingCoverTones {
    val palette = com.opentune.ui.theme.rememberArtworkPalette(artworkUrl, keepLast = true)
    val scheme = MaterialTheme.colorScheme
    val target = remember(palette, scheme) { coverTones(palette, scheme) }
    val spec = tween<Color>(900)
    val deep = animateColorAsState(target.deep, spec, label = "coverDeep")
    val second = animateColorAsState(target.second, spec, label = "coverSecond")
    val base = animateColorAsState(target.base, spec, label = "coverBase")
    val l0 = animateColorAsState(target.lights[0], spec, label = "coverLight0")
    val l1 = animateColorAsState(target.lights[1], spec, label = "coverLight1")
    val l2 = animateColorAsState(target.lights[2], spec, label = "coverLight2")
    val l3 = animateColorAsState(target.lights[3], spec, label = "coverLight3")
    return remember(target) { FadingCoverTones(target, deep, second, base, listOf(l0, l1, l2, l3)) }
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

/**
 * The colours of a sheet about one song (the song sheet, About song, the credits, the menu's
 * quick actions), taken from its cover as the player's backdrop is: the cover's colours read
 * by [com.opentune.ui.theme.rememberArtworkPalette], each kept at its hue and only set how
 * light and how colourful it is. [fill] is the sheet, a deep tint of the cover; [card] one
 * tonal step up from it for the cards and rows on it, [raised] another for what sits on a
 * card or stands alone (the menu's round buttons); [ink] and [inkSoft] the text; [accent] the
 * cover's clearest colour, for the main button and what is on (a liked heart), with
 * [onAccent] on it.
 */
@Immutable
internal class SheetTint(
    val fill: Color,
    val card: Color,
    val raised: Color,
    val ink: Color,
    val inkSoft: Color,
    val accent: Color,
    val onAccent: Color,
    /** What is on (a liked heart, a kept download): the accent, or the player's own red on a cover of greys. */
    val on: Color,
    val dark: Boolean,
)

/**
 * [SheetTint] from a cover's [palette]. [black] keeps the sheet pure black with neutral cards
 * (the AMOLED player, or the app's pure black), and [plain] keeps every surface neutral (the
 * Solid sheet style, which takes no colour from the page): either way the accent is still the
 * cover's. A cover of greys, or one not read yet, gives neutral surfaces and a near-white (or
 * near-black) accent.
 */
internal fun sheetTint(palette: List<Color>, dark: Boolean, black: Boolean = false, plain: Boolean = false): SheetTint {
    val hct = palette.map { Hct.fromInt(it.toArgb()) }
    // The surfaces lean on the cover's main colour; the accent on its first real colour.
    val main = hct.firstOrNull()
    val colourful = hct.firstOrNull { it.chroma >= LIKE_MIN_SOURCE_CHROMA }
    val hue = main?.hue ?: 0.0
    val sourceChroma = if (black || plain) 0.0 else main?.chroma ?: 0.0
    fun surface(tone: Double, maxChroma: Double) = Color(Hct.from(hue, min(sourceChroma, maxChroma), tone).toInt())
    fun accent(tone: Double) = colourful?.let { Color(Hct.from(it.hue, it.chroma.coerceIn(ACCENT_MIN_CHROMA, ACCENT_MAX_CHROMA), tone).toInt()) }
        ?: Color(Hct.from(hue, 0.0, if (tone > 50.0) 92.0 else 22.0).toInt())
    // A cover of greys gives a near-white accent: right for Done, too quiet for a liked heart.
    fun on(tone: Double) = if (colourful != null) accent(tone) else Color(Hct.from(LIKE_RED_HUE, LIKE_MIN_CHROMA + 20.0, tone).toInt())
    return if (dark) {
        val accent = accent(ACCENT_TONE_ON_DARK)
        SheetTint(
            fill = if (black) Color.Black else surface(SHEET_TONE_DARK, 16.0),
            card = surface(if (black) 9.0 else SHEET_TONE_DARK + 6.0, 18.0),
            raised = surface(if (black) 16.0 else SHEET_TONE_DARK + 12.0, 20.0),
            ink = surface(97.0, 4.0),
            inkSoft = surface(78.0, 10.0),
            accent = accent,
            onAccent = colourful?.let { Color(Hct.from(it.hue, min(it.chroma, 24.0), 14.0).toInt()) } ?: Color(Hct.from(hue, 0.0, 12.0).toInt()),
            on = on(if (colourful != null) ACCENT_TONE_ON_DARK else 60.0),
            dark = true,
        )
    } else {
        SheetTint(
            fill = surface(SHEET_TONE_LIGHT, 8.0),
            card = surface(SHEET_TONE_LIGHT - 5.0, 12.0),
            raised = surface(SHEET_TONE_LIGHT - 10.0, 14.0),
            ink = surface(10.0, 6.0),
            inkSoft = surface(38.0, 10.0),
            accent = accent(ACCENT_TONE_ON_LIGHT),
            onAccent = Color(Hct.from(colourful?.hue ?: hue, min(colourful?.chroma ?: 0.0, 8.0), 99.0).toInt()),
            on = on(if (colourful != null) ACCENT_TONE_ON_LIGHT else 48.0),
            dark = false,
        )
    }
}

/** The tint the sheets in this part of the screen wear; null where none was set (they keep the theme). */
internal val LocalSheetTint = staticCompositionLocalOf<SheetTint?> { null }

/**
 * [sheetTint] for the cover at [artworkUrl], light or dark as the theme here is. Pure black
 * follows the player's AMOLED background inside the player and the app's pure black outside
 * it, as the surfaces around each already do.
 */
@Composable
internal fun rememberSheetTint(artworkUrl: String?): SheetTint {
    val palette = com.opentune.ui.theme.rememberArtworkPalette(artworkUrl)
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val theme by com.opentune.data.settings.AppSettings.theme.collectAsState()
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    val inPlayer = LocalSongSheet.current != null
    val black = dark && if (inPlayer) theme.playerBackground == com.opentune.data.settings.PlayerBackground.AMOLED else theme.pureBlack
    val plain = ui.sheetStyle == com.opentune.data.settings.SheetStyle.SOLID
    return remember(palette, dark, black, plain) { sheetTint(palette, dark, black, plain) }
}

/** The theme's colours with [tint]'s in place of its surfaces, text and accent, so the app's own parts (buttons, cards, sheets) wear it. */
internal fun ColorScheme.tintedBy(tint: SheetTint): ColorScheme = copy(
    primary = tint.accent, onPrimary = tint.onAccent,
    background = tint.fill, onBackground = tint.ink,
    surface = tint.fill, onSurface = tint.ink, onSurfaceVariant = tint.inkSoft,
    surfaceVariant = tint.card,
    surfaceContainerLowest = tint.fill, surfaceContainerLow = tint.fill, surfaceContainer = tint.fill,
    surfaceContainerHigh = tint.fill, surfaceContainerHighest = tint.card,
    outline = tint.inkSoft.copy(alpha = 0.5f), outlineVariant = tint.raised,
)

/** Wears [tint] in [content]: the theme's colours swapped for it, and the tint itself for the parts that read it. */
@Composable
internal fun SheetTinted(tint: SheetTint, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, tint) { base.tintedBy(tint) }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSheetTint provides tint, LocalContentColor provides tint.ink, content = content)
    }
}

/** How light a sheet is, dark and light; its cards and raised parts step up from it. */
private const val SHEET_TONE_DARK = 13.0
private const val SHEET_TONE_LIGHT = 97.0
private const val ACCENT_TONE_ON_DARK = 82.0
private const val ACCENT_TONE_ON_LIGHT = 40.0
/** The accent's colourfulness: clear enough to read as the cover's colour, never loud. */
private const val ACCENT_MIN_CHROMA = 30.0
private const val ACCENT_MAX_CHROMA = 56.0
/** The hue of the player's own liked red (#FA2D48). */
private const val LIKE_RED_HUE = 18.0

/** A cover colour below this chroma counts as grey. */
private const val LIKE_MIN_SOURCE_CHROMA = 10.0
/** The heart's least colourfulness, so it never reads as grey. */
private const val LIKE_MIN_CHROMA = 52.0
/** Its lightness on dark buttons and on light ones. */
private const val LIKE_TONE_ON_DARK = 70.0
private const val LIKE_TONE_ON_LIGHT = 40.0
