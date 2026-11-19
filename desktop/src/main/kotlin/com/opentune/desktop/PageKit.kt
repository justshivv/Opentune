package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The content pages' own measures: a 20dp gutter, bold 26sp headings, 17sp rows, round 62dp
 * buttons by a list's head, striped rows. Shared by Home, Explore, the Library and the album and
 * playlist pages, so they read as one.
 */
internal object PageType {
    val heading @Composable get() = webType(26f, 32f, 700)
    val pageTitle @Composable get() = webType(30f, 36f, 700)
    /** The small capitals over a list's title ("PLAYLIST"), spaced out. */
    val kicker @Composable get() = webType(14f, 18f, 600, 0.12f)
    @Composable fun bigTitle(size: Float) = webType(size, size * 1.12f, 700, -0.01f)
    val owner @Composable get() = webType(17f, 22f, 500)
    val meta @Composable get() = webType(14f, 18f, 500, 0.04f)
    val rowTitle @Composable get() = webType(17f, 22f, 500)
    val row @Composable get() = webType(17f, 22f)
    val small @Composable get() = webType(14f, 18f)
    val cardTitle @Composable get() = webType(17f, 22f, 700)
    val cardLine @Composable get() = webType(15f, 20f)
    val tab @Composable get() = webType(16f, 20f, 500)
    val field @Composable get() = webType(16f, 20f)
    val emptyTitle @Composable get() = webType(22f, 28f, 700)
    val emptyLine @Composable get() = webType(17f, 22f)
    val button @Composable get() = webType(15f, 20f, 600)
}

/** The pages' ink and fills, from the theme's own text colour so a light theme works too. */
internal object PageInk {
    private val on @Composable get() = MaterialTheme.colorScheme.onSurface
    val text @Composable get() = on
    /** Grey lines: about 60% of the text colour. */
    val grey @Composable get() = on.copy(alpha = 0.6f)
    /** A row's artist column, between white and grey. */
    val soft @Composable get() = on.copy(alpha = 0.78f)
    /** Every other row of a track list. */
    val stripe @Composable get() = on.copy(alpha = 0.04f)
    /** A row under the pointer. */
    val hover @Composable get() = on.copy(alpha = 0.085f)
    /** The row playing now. */
    val current @Composable get() = on.copy(alpha = 0.11f)
    /** A round button at rest, and under the pointer. */
    val button @Composable get() = on.copy(alpha = 0.12f)
    val buttonHover @Composable get() = on.copy(alpha = 0.19f)
    /** A text field's fill. */
    val field @Composable get() = on.copy(alpha = 0.07f)
    val hairline @Composable get() = on.copy(alpha = 0.12f)
}

/** The pages' side margin. */
internal val PageGutter = 20.dp

/** Hover and press changes: quick, as the reference's are. */
internal const val PAGE_HOVER_MS = 130

/**
 * A round button by a list's head: [solid] is Play, the text colour as its fill with the page's
 * colour on it; the others a faint fill. Each brightens under the pointer and gives when pressed.
 */
@Composable
internal fun PageRoundButton(
    icon: ImageVector,
    label: String,
    solid: Boolean = false,
    size: Dp = 62.dp,
    iconSize: Dp = size * 0.45f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = (hovered || focused) && enabled
    val ink = MaterialTheme.colorScheme.onSurface
    val page = MaterialTheme.colorScheme.background
    val fill by animateColorAsState(
        when {
            solid -> if (lit) lerp(ink, page, 0.12f) else ink
            lit -> PageInk.buttonHover
            else -> PageInk.button
        },
        tween(PAGE_HOVER_MS), label = "round fill",
    )
    Box(
        Modifier.pressScale(source).size(size).clip(CircleShape).background(fill)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (solid) page else ink, modifier = Modifier.size(iconSize)) }
}

/**
 * A bare 20dp icon that a soft disc comes up behind under the pointer, the icon brightening to
 * the text colour; [lit] keeps it bright (a liked heart).
 */
@Composable
internal fun PageIconButton(
    icon: ImageVector,
    label: String,
    lit: Boolean = false,
    iconSize: Dp = 20.dp,
    size: Dp = 34.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val on = hovered || focused
    val ink by animateColorAsState(if (on || lit) PageInk.text else PageInk.grey, tween(PAGE_HOVER_MS), label = "icon ink")
    val fill by animateColorAsState(if (on) PageInk.hover else PageInk.hover.copy(alpha = 0f), tween(PAGE_HOVER_MS), label = "icon fill")
    Box(
        modifier.pressScale(source).size(size).clip(CircleShape).background(fill)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = ink, modifier = Modifier.size(iconSize)) }
}

/**
 * A search box: 44dp tall, 8dp corners on a faint fill, the magnifier, the hint in grey, and a
 * cross to clear it once something's typed. [focus] takes the keyboard when given.
 */
@Composable
internal fun PageSearchField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    onEnter: (() -> Unit)? = null,
    icon: Boolean = true,
) {
    val ink = PageInk.text
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(8.dp)).background(PageInk.field).padding(start = if (icon) 12.dp else 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) Icon(Icons.Rounded.Search, null, tint = PageInk.grey, modifier = Modifier.padding(end = 10.dp).size(20.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, style = PageType.field, color = PageInk.grey, maxLines = 1)
            BasicTextField(
                value, onValue, singleLine = true,
                textStyle = PageType.field.copy(color = ink),
                cursorBrush = SolidColor(ink),
                keyboardOptions = KeyboardOptions(imeAction = if (onEnter != null) ImeAction.Done else ImeAction.Search),
                keyboardActions = KeyboardActions(onDone = { onEnter?.invoke() }),
                modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                    .onPreviewKeyEvent { e ->
                        val enter = e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)
                        if (enter && onEnter != null) { onEnter(); true } else false
                    },
            )
        }
        if (value.isNotEmpty()) PageIconButton(Icons.Rounded.Close, "Clear", iconSize = 16.dp, size = 30.dp) { onValue("") }
        else Spacer(Modifier.width(6.dp))
    }
}

/**
 * Tabs spread evenly across the width, their names in 16sp, the chosen one in the text colour
 * with a 3dp underline that slides to it over a hairline the width of the page.
 */
@Composable
internal fun PageTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val still = stillMotion()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val each = maxWidth / labels.size.coerceAtLeast(1)
        val at by animateDpAsState(each * selected, if (still) tween(0) else tween(240, easing = Web.Emph), label = "tab underline")
        Column {
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { i, label ->
                    val source = remember { MutableInteractionSource() }
                    val hovered by source.collectIsHoveredAsState()
                    val chosen = i == selected
                    val ink by animateColorAsState(
                        when { chosen -> PageInk.text; hovered -> PageInk.text.copy(alpha = 0.85f); else -> PageInk.grey },
                        tween(PAGE_HOVER_MS), label = "tab ink",
                    )
                    Box(
                        Modifier.weight(1f).height(48.dp).hoverable(source)
                            .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(i) }
                            .semantics { this.selected = chosen },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = PageType.tab, color = ink, maxLines = 1) }
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(PageInk.hairline))
                Box(Modifier.offset(x = at).width(each).height(3.dp).background(PageInk.text))
            }
        }
    }
}

/** A list with nothing in it: an icon, a bold line and a grey one under it, centred, 90dp down. */
@Composable
internal fun PageEmpty(icon: ImageVector, title: String, line: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 90.dp, bottom = 40.dp, start = PageGutter, end = PageGutter), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = PageInk.text, modifier = Modifier.size(40.dp))
        Text(title, style = PageType.emptyTitle, color = PageInk.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        line?.let { Text(it, style = PageType.emptyLine, color = PageInk.grey, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).widthIn(max = 520.dp)) }
    }
}

/** A section's heading: bold 26sp, with an optional grey strapline under it and [actions] at its end. */
@Composable
internal fun PageHeading(title: String, strapline: String? = null, modifier: Modifier = Modifier, actions: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PageType.heading, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            strapline?.takeIf { it.isNotBlank() }?.let { Text(it.uppercase(), style = PageType.meta, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
        actions?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { it() } }
    }
}

/** A small text button, as the Library's "A–Z": grey at rest, the text colour when [on] or under the pointer. */
@Composable
internal fun PageTextToggle(label: String, on: Boolean, description: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val ink by animateColorAsState(if (on || hovered) PageInk.text else PageInk.grey, tween(PAGE_HOVER_MS), label = "toggle ink")
    val fill by animateColorAsState(if (on) PageInk.button else if (hovered) PageInk.hover else PageInk.hover.copy(alpha = 0f), tween(PAGE_HOVER_MS), label = "toggle fill")
    Box(
        Modifier.pressScale(source).height(44.dp).clip(RoundedCornerShape(8.dp)).background(fill).hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = description; selected = on }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = PageType.button, color = ink, maxLines = 1) }
}

/**
 * A pill button for the pages' dialogs: [primary] in the text colour with the page's on it,
 * [destructive] in red, or else a faint fill.
 */
@Composable
internal fun PageButton(label: String, primary: Boolean = false, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val ink = MaterialTheme.colorScheme.onSurface
    val page = MaterialTheme.colorScheme.background
    val base = when { destructive -> Studio.Destructive; primary -> ink; else -> PageInk.button }
    val fill by animateColorAsState(if (hovered && enabled) lerp(base, if (primary) page else ink, 0.1f) else base, tween(PAGE_HOVER_MS), label = "button fill")
    Box(
        Modifier.pressScale(source).height(40.dp).clip(CircleShape).background(fill).hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = PageType.button, color = when { destructive -> Color.White; primary -> page; else -> ink }, maxLines = 1) }
}

/**
 * A small dialog in the pages' look rather than stock Material: a raised panel with 12dp corners,
 * its title in bold 22sp, [content] under it and [buttons] at its foot on the right. Escape or a
 * click outside closes it.
 */
@Composable
internal fun PageDialog(title: String, onDismiss: () -> Unit, buttons: @Composable () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(min = 360.dp, max = 440.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(24.dp),
        ) {
            Text(title, style = PageType.emptyTitle, color = PageInk.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 14.dp)) { content() }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) { buttons() }
        }
    }
}

/** Asks for a name in a [PageDialog]: the field takes the keyboard at once, Enter confirms. */
@Composable
internal fun PageNameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PageDialog(title, onDismiss, buttons = {
        PageButton("Cancel", onClick = onDismiss)
        PageButton(confirm, primary = true, enabled = name.isNotBlank()) { onDone(name) }
    }) {
        PageSearchField(name, { name = it }, "Name", Modifier.fillMaxWidth(), focus = focus, icon = false, onEnter = { if (name.isNotBlank()) onDone(name) })
    }
}

/** Asks before something goes for good, in a [PageDialog] with a red button. */
@Composable
internal fun PageConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    PageDialog(title, onDismiss, buttons = {
        PageButton("Cancel", onClick = onDismiss)
        PageButton(confirm, destructive = true, onClick = onConfirm)
    }) { Text(message, style = PageType.cardLine, color = PageInk.grey) }
}
