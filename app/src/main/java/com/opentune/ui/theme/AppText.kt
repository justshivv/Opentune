package com.opentune.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.opentune.i18n.Tr
import com.opentune.ui.components.isRtlText

/*
 * The app's Text and Icon: Material's, with the words shown in the chosen language.
 * Every screen imports these in place of Material's, so a label written in English in the
 * code shows translated without each one being wrapped. Song, artist and playlist names
 * pass through unchanged unless one happens to be exactly one of the app's own words.
 */

/** Set by a component that already lays its text out in the words' own direction (the scrolling title does): the shared Text then leaves direction alone instead of providing it a second time. */
internal val LocalTextDirectionHandled = compositionLocalOf { false }

/**
 * Room kept free at an ellipsized line's cut side: the wider of the ellipsis
 * glyph and three dots at this style, plus a point. An ellipsized line is
 * laid out exactly as wide as its box, its ellipsis flush at the edge, and
 * the drawn dots run wider than the measured ones — without the room, a
 * dot or two is cut off the end.
 */
@Composable
private fun ellipsisRoom(style: TextStyle, fontSize: TextUnit, fontWeight: FontWeight?, fontFamily: FontFamily?): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val effective = style.copy(
        fontSize = if (fontSize != TextUnit.Unspecified) fontSize else style.fontSize,
        fontWeight = fontWeight ?: style.fontWeight,
        fontFamily = fontFamily ?: style.fontFamily,
    )
    return remember(measurer, effective) {
        val glyph = measurer.measure("…", effective).size.width
        val dots = measurer.measure("...", effective).size.width
        with(density) { maxOf(glyph, dots).toDp() } + 1.dp
    }
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
    val inner = if (overflow == TextOverflow.Ellipsis) modifier.padding(end = ellipsisRoom(style, fontSize, fontWeight, fontFamily)) else modifier
    // The words' own direction, here in the one Text every screen uses, so
    // no screen has to remember: a line that reads right-to-left (an Arabic
    // title, say) is laid out from its right — its start, a leading number
    // included, at the right, its wraps and its overflow cut at the left —
    // wherever it appears, in a card, a row or the player.
    CompositionLocalProvider(LocalLayoutDirection provides if (!LocalTextDirectionHandled.current && isRtlText(shown)) LayoutDirection.Rtl else LocalLayoutDirection.current) {
        androidx.compose.material3.Text(
            text = shown, modifier = inner, color = color, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
            textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
            minLines = minLines, onTextLayout = onTextLayout, style = style,
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
    // there are no font or paragraph spans. Never flatten an AnnotatedString.
    val inner = if (overflow == TextOverflow.Ellipsis) modifier.padding(end = ellipsisRoom(style, fontSize, fontWeight, fontFamily)) else modifier
    CompositionLocalProvider(LocalLayoutDirection provides if (!LocalTextDirectionHandled.current && isRtlText(text.text)) LayoutDirection.Rtl else LocalLayoutDirection.current) {
        androidx.compose.material3.Text(
            text = text,
            modifier = inner, color = color, fontSize = fontSize, fontStyle = fontStyle,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
            textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
            minLines = minLines, inlineContent = inlineContent, onTextLayout = onTextLayout, style = style,
        )
    }
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
