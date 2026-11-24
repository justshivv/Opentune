package com.opentune.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.i18n.Tr

/*
 * More of the kit (see TvKit.kt), for forms and lists: a text field that suits a remote, a switch
 * row for settings, a section title, and a card with an icon for a cover. Same rules: solid
 * colours, the accent ring on what has the focus, nothing that moves.
 */

/** A section's title inside a list page (Settings' Account, Playback…), in from the sides. */
@Composable
fun TvSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = TvType.Title, color = TvColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.safeSides().padding(top = TvSpace.L, bottom = TvSpace.XS),
    )
}

/** A switch, drawn still: a pill in the accent with the knob at the end when on, a quiet one when off. */
@Composable
fun TvSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(56.dp, 32.dp)
            .background(if (checked) TvColors.Accent else TvColors.SurfaceTop, TvShapes.Pill)
            .then(if (checked) Modifier else Modifier.border(2.dp, TvColors.TextFaint, TvShapes.Pill))
            .padding(5.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(if (checked) TvColors.OnAccent else TvColors.TextDim, TvShapes.Round))
    }
}

/**
 * A settings row that turns something on or off: [title], a dim [subtitle], an optional [icon],
 * and a [TvSwitch] at the end. OK flips it. Like [TvRowButton], it fills the width: pass
 * `Modifier.safeSides()` in a [TvPage].
 */
@Composable
fun TvToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    focusKey: String? = null,
    entryFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    TvSurface(
        onClick = { onCheckedChange(!checked) }, shape = TvShapes.Row, role = Role.Switch,
        container = TvColors.Surface, focusedContainer = TvColors.SurfaceHigh,
        focusKey = focusKey, entryFocus = entryFocus, focusRequester = focusRequester,
        modifier = modifier.fillMaxWidth().semantics {
            toggleableState = ToggleableState(checked)
            stateDescription = Tr.t(if (checked) "On" else "Off")
        },
    ) { _ ->
        Row(Modifier.padding(horizontal = TvSpace.L, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = TvColors.TextDim, modifier = Modifier.size(TvSizes.Icon))
                Spacer(Modifier.width(TvSpace.L))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = TvType.ItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = TvType.Meta, color = TvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(TvSpace.L))
            TvSwitch(checked)
        }
    }
}

/**
 * A square card whose cover is a solid [color] with an [icon] on it, for a list that has no art of
 * its own (Liked songs). Sized and focused as a [TvCard] of [TvCardKind.Square], so it sits in a
 * shelf beside them.
 */
@Composable
fun TvIconCard(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = TvColors.SurfaceHigh,
    iconTint: Color = TvColors.Text,
    focusKey: String? = null,
    entryFocus: Boolean = false,
) {
    val focus = rememberTvFocus(focusKey, entryFocus)
    val lit = focus.lit
    val grow by animateFloatAsState(if (lit) TvMotion.FocusScale else 1f, tween(TvMotion.FocusMs), label = "grow")
    Column(modifier.width(TvSizes.CardSquare).tvFocusTarget(focus, onClick)) {
        Box(
            Modifier.graphicsLayer { scaleX = grow; scaleY = grow }
                .tvFocusRing(lit, TvShapes.Card)
                .size(TvSizes.CardSquare)
                .clip(TvShapes.Card)
                .background(color),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(64.dp)) }
        Spacer(Modifier.height(TvSpace.M))
        Text(title, style = TvType.ItemTitle, color = TvColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        if (!subtitle.isNullOrBlank()) Text(
            subtitle, style = TvType.Meta, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** The height of [TvTextField]. */
private val FieldHeight = 64.dp

/**
 * A one-line text field made for a remote. Resting, it is a pill the focus can pass over without
 * the keyboard popping up; OK on it starts typing with the TV's keyboard. The keyboard's action
 * key (Search, Done) calls [onSubmit] and puts the focus back on the pill; moving the focus away
 * with the arrows ends typing too. [editNow] starts typing at once (a search page opened fresh).
 * [focusKey], [entryFocus] and [focusRequester] (the resting pill's) as for [rememberTvFocus].
 * Focus properties set on [modifier] (e.g. where Down goes) apply both resting and typing.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.Rounded.Search,
    imeAction: ImeAction = ImeAction.Search,
    onSubmit: () -> Unit = {},
    focusKey: String? = null,
    entryFocus: Boolean = false,
    editNow: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var editing by remember { mutableStateOf(editNow) }
    var refocus by remember { mutableStateOf(false) }
    val pill = focusRequester ?: remember { FocusRequester() }
    Box(modifier.fillMaxWidth().height(FieldHeight)) {
        if (!editing) {
            TvSurface(
                onClick = { editing = true }, shape = TvShapes.Pill,
                focusKey = focusKey, entryFocus = entryFocus, focusRequester = pill,
                modifier = Modifier.matchParentSize(),
            ) { _ ->
                FieldRow(icon) {
                    if (value.isEmpty()) Text(placeholder, style = TvType.Body, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Text(value, style = TvType.Body, color = TvColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LaunchedEffect(Unit) { if (refocus) { refocus = false; runCatching { pill.requestFocus() } } }
        } else {
            val memory = LocalPageMemory.current
            val input = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            var focused by remember { mutableStateOf(false) }
            var had by remember { mutableStateOf(false) }
            // Its own copy of the text, so the cursor starts at the end of what is there.
            var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
            if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
            Box(
                Modifier.matchParentSize()
                    .tvFocusRing(focused, TvShapes.Pill)
                    .clip(TvShapes.Pill)
                    .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface),
            ) {
                FieldRow(icon) {
                    if (value.isEmpty()) Text(placeholder, style = TvType.Body, color = TvColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it; if (it.text != value) onValueChange(it.text) },
                        singleLine = true,
                        textStyle = TvType.Body.copy(color = TvColors.Text),
                        cursorBrush = SolidColor(TvColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = imeAction),
                        keyboardActions = KeyboardActions(onAny = {
                            keyboard?.hide()
                            onSubmit()
                            refocus = true
                            editing = false
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(input).onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) {
                                had = true
                                memory?.focusKey = focusKey
                            } else if (had) editing = false
                        },
                    )
                }
            }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { input.requestFocus() } }
        }
    }
}

@Composable
private fun FieldRow(icon: ImageVector?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(FieldHeight).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = TvColors.TextDim, modifier = Modifier.size(TvSizes.Icon))
            Spacer(Modifier.width(TvSpace.M))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { content() }
    }
}
