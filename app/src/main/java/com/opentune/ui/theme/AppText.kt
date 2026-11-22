package com.opentune.ui.theme

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import com.opentune.i18n.Tr

/*
 * The app's Text and Icon: Material's, with the words shown in the chosen language.
 * Every screen imports these in place of Material's, so a label written in English in the
 * code shows translated without each one being wrapped. Song, artist and playlist names
 * pass through unchanged unless one happens to be exactly one of the app's own words.
 */

/** Set by a component that already lays its text out in the words' own direction (the scrolling title does): the shared Text then leaves direction alone instead of providing it a second time. */
internal val LocalTextDirectionHandled = compositionLocalOf { false }

/**
 * One manual fit, remembered for the width it was made at: the string to
 * draw (null = the original fits that width whole, draw it as it is).
 */
private class EllipsisFit(val availPx: Int, val text: String?)

/**
 * The longest prefix of [full] that, with an ellipsis after it, fits
 * [availPx] — or null when [full] already fits whole. The platform
 * ellipsizer cuts a line flush at its box's edge, and on some Arabic
 * lines the dots of its ellipsis land on that edge and are drawn shaved.
 * A line fitted here is drawn as ordinary text instead: the ellipsis is
 * a normal last character, laid out inside the box like any other, so
 * there is nothing at the edge to shave — and the text keeps the full
 * width it was given, neither shrunk nor spilling into its neighbour.
 */
private fun fitEllipsis(full: String, availPx: Int, measurer: TextMeasurer, style: TextStyle): String? {
    fun widthOf(s: String): Int = measurer.measure(
        AnnotatedString(s), style = style,
        overflow = TextOverflow.Visible, softWrap = false, maxLines = 1,
    ).size.width
    if (widthOf(full) <= availPx) return null
    var lo = 0
    var hi = full.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        var cut = mid
        if (cut > 0 && full[cut - 1].isHighSurrogate()) cut--
        if (cut > lo && widthOf(full.take(cut) + "…") <= availPx) lo = cut else hi = mid - 1
    }
    return full.take(lo) + "…"
}

/** The style the measurer must use: what the render below resolves to. */
@Composable
private fun measureStyleOf(
    style: TextStyle,
    fontSize: TextUnit, fontWeight: FontWeight?, fontFamily: FontFamily?,
    fontStyle: FontStyle?, letterSpacing: TextUnit, lineHeight: TextUnit,
): TextStyle {
    val merged = LocalTextStyle.current.merge(style)
    return merged.copy(
        fontSize = if (fontSize != TextUnit.Unspecified) fontSize else merged.fontSize,
        fontWeight = fontWeight ?: merged.fontWeight,
        fontFamily = fontFamily ?: merged.fontFamily,
        fontStyle = fontStyle ?: merged.fontStyle,
        letterSpacing = if (letterSpacing != TextUnit.Unspecified) letterSpacing else merged.letterSpacing,
        lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight else merged.lineHeight,
        textDirection = TextDirection.Content,
    )
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val shown = Tr.t(text)
    val measurer = rememberTextMeasurer()
    val measureStyle = measureStyleOf(style, fontSize, fontWeight, fontFamily, fontStyle, letterSpacing, lineHeight)
    var fit by remember(shown, measureStyle) { mutableStateOf<EllipsisFit?>(null) }
    // The paragraph's direction comes from the words themselves, here in the
    // one Text every screen uses, so no screen has to remember: a line that
    // reads right-to-left (an Arabic title, say) is laid out from its right —
    // its start, a leading number included, at the right, its wraps and its
    // overflow cut at the left — wherever it appears, in a card, a row or
    // the player. The layout direction around it is left alone: flipping
    // that pushed an ellipsized line's cut edge outside its own bounds and
    // shaved dots off its ellipsis.
    if (LocalTextDirectionHandled.current) {
        androidx.compose.material3.Text(
            text = shown, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
            textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
            minLines = minLines, onTextLayout = onTextLayout, style = style,
        )
    } else if (overflow == TextOverflow.Ellipsis && maxLines == 1 && softWrap && !shown.contains('\n')) {
        // A single ellipsized line is fitted by hand (see fitEllipsis): the
        // first layout reports the width it was given, the fit is made for
        // exactly that width, and from then on the line is drawn as the
        // ordinary text it now is.
        val fitted = fit?.text
        androidx.compose.material3.Text(
            text = AnnotatedString(fitted ?: shown, ParagraphStyle(textDirection = TextDirection.Content)),
            modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
            textAlign = textAlign, lineHeight = lineHeight,
            overflow = if (fitted != null) TextOverflow.Visible else overflow,
            softWrap = softWrap, maxLines = maxLines,
            minLines = minLines,
            onTextLayout = { result ->
                onTextLayout?.invoke(result)
                val avail = result.layoutInput.constraints.maxWidth
                if (avail > 0 && avail != Constraints.Infinity && fit?.availPx != avail) {
                    fit = EllipsisFit(avail, fitEllipsis(shown, avail, measurer, measureStyle))
                }
            },
            style = style,
        )
    } else {
        androidx.compose.material3.Text(
            text = AnnotatedString(shown, ParagraphStyle(textDirection = TextDirection.Content)),
            modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
            textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
            minLines = minLines, onTextLayout = onTextLayout ?: {}, style = style,
        )
    }
}

/** Styled text is shown as it is: its spans are tied to the English wording's positions. */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    // Links, inline content and other annotations also carry ranges, even when
    // there are no font or paragraph spans. Never flatten an AnnotatedString:
    // the content direction is added as one more paragraph style over the
    // whole range, and only the direction property is taken from it.
    val directed = if (LocalTextDirectionHandled.current) text else AnnotatedString(
        text.text, text.spanStyles,
        text.paragraphStyles + AnnotatedString.Range(ParagraphStyle(textDirection = TextDirection.Content), 0, text.length),
    )
    androidx.compose.material3.Text(
        text = directed,
        modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
        textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        minLines = minLines, inlineContent = inlineContent, onTextLayout = onTextLayout, style = style,
    )
}

@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    androidx.compose.material3.Icon(imageVector, contentDescription?.let(Tr::t), modifier, tint)

@Composable
fun Icon(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    androidx.compose.material3.Icon(bitmap, contentDescription?.let(Tr::t), modifier, tint)

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    androidx.compose.material3.Icon(painter, contentDescription?.let(Tr::t), modifier, tint)
