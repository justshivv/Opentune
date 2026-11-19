package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.FocusRing

/*
 * The web player's grouped inset rows (web/app/src/pages/settingsStyles.ts), for the
 * desktop's Settings: a soft 18dp card of rows, each a title (and a quieter detail line)
 * with its control at the right, a hairline between rows inset to where the text starts.
 * The switch, the segmented control, the select and the pill buttons are the web's too,
 * drawn here rather than taken from Material so the two players read alike.
 */

// ---- The web's greys --------------------------------------------------------------------

/** The page is dark, so the web's dark tokens apply. */
@Composable
private fun isDark() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** systemSecondary: a row's detail, a value. */
@Composable
internal fun inkSecondary() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark()) 0.64f else 0.56f)

/** systemTertiary: chevrons, placeholders, a disabled arrow. */
@Composable
internal fun inkTertiary() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark()) 0.4f else 0.48f)

/** systemQuaternary: the off switch, a field, a quiet button. */
@Composable
internal fun fillQuaternary() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)

/** systemQuinary: the card the rows sit on. */
@Composable
internal fun fillQuinary() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)

/** labelDivider: the hairline between two rows. */
@Composable
private fun hairlineColour() = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark()) 0.1f else 0.15f)

/** The key colour the web fills a switch and the main button with: here, the chosen accent. */
@Composable
internal fun keyColour(): Color = Color(AppSettings.theme.collectAsState().value.seedColor)

/** White on the key colour, unless the accent is so light (the yellow) that white wouldn't read on it. */
internal fun inkOn(colour: Color): Color = if (colour.luminance() > 0.6f) Color(0xFF1C1C1E) else Color.White

/** Whether to move at all: smooth motion off makes every change here a cut. */
@Composable
private fun still(): Boolean = AppSettings.ui.collectAsState().value.reduceAnimation

/** The web's settle curve (--ot-ease-settle), which the switch's knob glides on. */
private val Settle = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

// ---- Type ---------------------------------------------------------------------------------

/** A row's name: the web's title-3, 15 on 20. */
@Composable
internal fun rowTitleStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp)

/** A row's detail line: the web's callout, a touch larger so it reads at a desktop's distance. */
@Composable
internal fun rowDetailStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp)

/** A value or a field's text: the web's body-reduced, 14 on 20. */
@Composable
private fun valueStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)

/** A button's label: the web's body-emphasized. */
@Composable
private fun buttonStyle(size: Int = 13): TextStyle =
    MaterialTheme.typography.labelLarge.copy(fontSize = size.sp, lineHeight = (size + 3).sp, fontWeight = FontWeight.SemiBold)

// ---- The card and its hairlines -------------------------------------------------------------

/** How far in a row's hairline starts: past the padding for a plain row, past the icon for one with an icon square. */
internal val TEXT_INSET = 16.dp
internal val ICON_INSET = 56.dp

/** A row's hairline inset, read by [Rows]; null draws no hairline above that row. */
private class HairlineInset(val start: Dp?) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@HairlineInset
}

/** Sets where the hairline above this row starts; null for none. Only on a direct child of a card. */
internal fun Modifier.hairline(start: Dp?): Modifier = this.then(HairlineInset(start))

/**
 * Rows one under another, full width, with a hairline between each two. A row that isn't
 * there (nothing drawn, no height) takes no hairline, so rows can come and go freely.
 */
@Composable
private fun Rows(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colour = hairlineColour()
    // Where the lines go is only known once the rows are placed; kept as state so the card redraws when they move.
    var lines by remember { mutableStateOf(emptyList<Pair<Int, Int>>()) }
    Layout(
        content,
        modifier.drawWithContent {
            drawContent()
            val stroke = maxOf(1f, 0.5.dp.toPx())
            lines.forEach { (y, start) ->
                drawLine(colour, Offset(start.toFloat(), y + stroke / 2), Offset(size.width, y + stroke / 2), stroke)
            }
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
        layout(width, placeables.sumOf { it.height }) {
            val found = ArrayList<Pair<Int, Int>>()
            var y = 0
            var any = false
            placeables.forEachIndexed { i, p ->
                if (p.height > 0) {
                    val inset = (measurables[i].parentData as? HairlineInset)
                    val start = if (inset == null) TEXT_INSET else inset.start
                    if (any && start != null) found += y to start.roundToPx()
                    any = true
                }
                p.placeRelative(0, y)
                y += p.height
            }
            if (found != lines) lines = found
        }
    }
}

/** A soft rounded card of rows, as the web's settings group (rounded-[18px] bg-quinary). */
@Composable
internal fun RowsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Rows(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(fillQuinary()), content)
}

/** A small heading between two cards of one group, as the web's section header. */
@Composable
internal fun CardHeading(text: String) {
    Text(
        text, style = buttonStyle(14), color = inkSecondary(),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** A note under a card, as the web's footer: what the rows above mean, or what they need. */
@Composable
internal fun CardFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = rowDetailStyle().copy(lineHeight = 18.sp), color = inkSecondary(),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth(0.92f),
    )
}

// ---- Rows ----------------------------------------------------------------------------------

/**
 * One row: an optional icon square, the title over its detail, and [trailing] at the right.
 * When the window is too narrow for the control beside the words, it drops under them.
 * With [onClick] the whole row is the button, lit faintly under the pointer.
 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailColour: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    leadingSize: Dp = 28.dp,
    onClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    enabled: Boolean = true,
    below: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val hover = remember { MutableInteractionSource() }
    val clickable = if (onClick != null) {
        Modifier.rowHover(hover).clickable(hover, FocusRing(RoundedCornerShape(4.dp)), enabled = enabled, role = role, onClick = onClick)
    } else Modifier
    // The hairline starts where the words do: past the padding, and past the icon or photo when there's one.
    val inset = if (leading != null) TEXT_INSET + leadingSize + 12.dp else TEXT_INSET
    RowFrame(modifier.hairline(inset).then(clickable), leading, leadingSize, enabled) {
        SideBySide(
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = rowTitleStyle(), color = MaterialTheme.colorScheme.onSurface)
                    detail?.let {
                        Text(it, style = rowDetailStyle(), color = if (detailColour == Color.Unspecified) inkSecondary() else detailColour)
                    }
                    below?.invoke()
                }
            },
            control = trailing?.let { t -> { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = t) } },
        )
    }
}

/** The padding every row shares (px-4 py-[13px]), and the 28dp icon square with 12dp after it. */
@Composable
private fun RowFrame(modifier: Modifier, leading: (@Composable () -> Unit)?, leadingSize: Dp, enabled: Boolean, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp).alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Box(Modifier.size(leadingSize), contentAlignment = Alignment.Center) { it() }
            Box(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** The faint fill of a clickable row under the pointer (hover:bg-quinary). */
@Composable
private fun Modifier.rowHover(source: MutableInteractionSource): Modifier {
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(
        if (hovered) fillQuinary() else Color.Transparent, if (still()) snap() else tween(150), label = "row hover",
    )
    return this.background(fill).hoverable(source)
}

/**
 * The words and the control side by side, the control at the right and both centred;
 * or, when the words would be squeezed under [squeeze], the control under the words.
 */
@Composable
private fun SideBySide(text: @Composable () -> Unit, control: (@Composable () -> Unit)?, squeeze: Dp = 180.dp) {
    if (control == null) return Box(Modifier.fillMaxWidth()) { text() }
    Layout(contents = listOf(text, control), modifier = Modifier.fillMaxWidth()) { (texts, controls), constraints ->
        val width = constraints.maxWidth
        val gap = 16.dp.roundToPx()
        val c = controls.first().measure(Constraints(maxWidth = width))
        val room = width - c.width - gap
        val wanted = texts.first().maxIntrinsicWidth(Constraints.Infinity)
        if (room >= minOf(wanted, squeeze.roundToPx())) {
            val t = texts.first().measure(Constraints(maxWidth = room.coerceAtLeast(0)))
            val h = maxOf(t.height, c.height)
            layout(width, h) {
                t.placeRelative(0, (h - t.height) / 2)
                c.placeRelative(width - c.width, (h - c.height) / 2)
            }
        } else {
            val t = texts.first().measure(Constraints(maxWidth = width))
            val below = 10.dp.roundToPx()
            layout(width, t.height + below + c.height) {
                t.placeRelative(0, 0)
                c.placeRelative(0, t.height + below)
            }
        }
    }
}

/** A row with a switch: the whole row is the switch, as the web's label around its checkbox. */
@Composable
internal fun ToggleRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    leadingSize: Dp = 28.dp,
    extra: (@Composable RowScope.() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    SettingRow(
        title,
        modifier.toggleable(checked, source, FocusRing(RoundedCornerShape(4.dp)), enabled = enabled, role = Role.Switch, onValueChange = onChange),
        detail = detail, leading = leading, leadingSize = leadingSize, enabled = enabled,
    ) {
        extra?.invoke(this)
        WebSwitch(checked, pressed = source.collectIsPressedAsState().value)
    }
}

/**
 * The web's switch: 44 by 26, the key colour when on, a white knob that slides across on the
 * settle curve and widens a touch while held. It only draws; the row around it is the control.
 */
@Composable
internal fun WebSwitch(checked: Boolean, pressed: Boolean = false) {
    val still = still()
    val track by animateColorAsState(if (checked) keyColour() else fillQuaternary(), if (still) snap() else tween(200), label = "switch track")
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, if (still) snap() else tween(250, easing = Settle), label = "switch knob")
    val grow by animateFloatAsState(if (pressed && !still) 1.1f else 1f, tween(150), label = "switch press")
    Box(Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(track)) {
        Box(
            Modifier.offset(x = x, y = 3.dp).size(20.dp).scale(grow, 1f)
                .shadow(1.5.dp, CircleShape).background(Color.White, CircleShape),
        )
    }
}

/** An integration's coloured app-icon square, as the web's Glyph: 28dp, 9dp corners, the glyph in [ink]. */
@Composable
internal fun AppTile(colour: Color, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(colour), contentAlignment = Alignment.Center, content = content)
}

/** An [AppTile] holding one icon. */
@Composable
internal fun IconTile(icon: ImageVector, colour: Color, ink: Color = Color.White, size: Dp = 16.dp) {
    AppTile(colour) { Icon(icon, null, Modifier.size(size), tint = ink) }
}

/** An [AppTile] holding a letter or two, as the web marks JioSaavn with its J. */
@Composable
internal fun LetterTile(text: String, colour: Color, ink: Color) {
    AppTile(colour) { Text(text, color = ink, fontSize = 13.sp, lineHeight = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
}

/**
 * An integration as the web lists one: its icon square, its name, and at the right its state
 * ("Connected", "Not connected") in the quiet ink, then the row's own action or a chevron.
 */
@Composable
internal fun IntegrationRow(
    name: String,
    state: String?,
    tile: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    SettingRow(name, modifier, detail = detail, leading = tile, onClick = onClick) {
        state?.let { Text(it, style = valueStyle(), color = inkSecondary(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) }
        action?.invoke(this)
    }
}

/** A green dot before a state that's live, as the web's connected marker. */
@Composable
internal fun LiveDot() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF30D158)))
}

// ---- Choices ---------------------------------------------------------------------------------

/**
 * The web's segmented control: the choices in a grey pill, the picked one raised on its own
 * lighter pill. A radio group to the keyboard and to screen readers.
 */
@Composable
internal fun <T> Segmented(name: String, choices: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val still = still()
    val raised = if (isDark()) Color(0xFF636366) else Color.White
    Row(
        Modifier.clip(CircleShape).background(fillQuaternary()).padding(3.dp).selectableGroup().semantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        choices.forEach { c ->
            val on = c == selected
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val fill by animateColorAsState(if (on) raised else Color.Transparent, if (still) snap() else tween(200), label = "segment")
            val ink by animateColorAsState(
                if (on || hovered) MaterialTheme.colorScheme.onSurface else inkSecondary(), if (still) snap() else tween(200), label = "segment ink",
            )
            Text(
                label(c), style = buttonStyle(14), color = ink, maxLines = 1, softWrap = false,
                modifier = Modifier.shadow(if (on) 2.dp else 0.dp, CircleShape).clip(CircleShape).background(fill)
                    .hoverable(source).selectable(on, source, FocusRing(CircleShape), role = Role.RadioButton) { onPick(c) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** A row whose choice is a [Segmented] control at the right, for a few short choices. */
@Composable
internal fun <T> SegmentedRow(title: String, detail: String? = null, choices: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    SettingRow(title, detail = detail) { Segmented(title, choices, selected, label, onPick) }
}

/**
 * A row whose choice is the web's select: the picked one in a small grey field with a chevron,
 * the others in a menu under it. For a longer list than a segmented control holds.
 */
@Composable
internal fun <T> MenuRow(
    title: String,
    detail: String? = null,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    summary: ((T) -> String?)? = null,
    onOpen: (() -> Unit)? = null,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, detail = detail, onClick = { onOpen?.invoke(); open = true }, role = Role.DropdownList) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(fillQuaternary()).padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label(selected), style = valueStyle(), color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp),
                )
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.padding(start = 6.dp).size(18.dp), tint = inkTertiary())
            }
            DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(12.dp)) {
                choices.forEach { c ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(label(c), style = valueStyle())
                                summary?.invoke(c)?.let { Text(it, style = rowDetailStyle(), color = inkSecondary()) }
                            }
                        },
                        leadingIcon = { Box(Modifier.size(18.dp)) { if (c == selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = keyColour()) } },
                        onClick = { open = false; onPick(c) },
                    )
                }
            }
        }
    }
}

/**
 * A slider row: the name and its value on one line, the slider across the row under them.
 * [reset] puts a Reset button beside the value while it's off its default.
 */
@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    detail: String? = null,
    reset: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.hairline(TEXT_INSET).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 6.dp)) {
        SideBySide(
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = rowTitleStyle())
                    detail?.let { Text(it, style = rowDetailStyle(), color = inkSecondary()) }
                }
            },
            control = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(shown, style = valueStyle(), color = inkSecondary())
                    reset?.let { RowAction("Reset", it) }
                }
            },
        )
        WebSlider(value, range, title, onChange)
    }
}

/** A slim slider in the key colour, keyboard-steppable as Material's is. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun WebSlider(value: Float, range: ClosedFloatingPointRange<Float>, name: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    val key = keyColour()
    val colours = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = key, inactiveTrackColor = fillQuaternary())
    val source = remember { MutableInteractionSource() }
    Slider(
        value, onChange, valueRange = range, interactionSource = source,
        modifier = modifier.semantics { contentDescription = name },
        thumb = { Box(Modifier.size(18.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape)) },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state, colors = colours, thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                modifier = Modifier.padding(vertical = 0.dp).size(width = Dp.Unspecified, height = 4.dp),
            )
        },
    )
}

// ---- Fields and buttons ---------------------------------------------------------------------------

/**
 * The web's text field: a small grey rounded box, the placeholder in the tertiary ink.
 * [name] is what a screen reader calls it, as the row's title labels the web's input.
 */
@Composable
internal fun WebField(
    value: String,
    onChange: (String) -> Unit,
    name: String,
    modifier: Modifier = Modifier.width(220.dp),
    placeholder: String = "",
    secret: Boolean = false,
    error: Boolean = false,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = valueStyle().copy(color = ink),
        cursorBrush = SolidColor(ink),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = modifier.semantics { contentDescription = name },
        decorationBox = { field ->
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(fillQuaternary())
                    .then(if (error) Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp)) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = valueStyle(), color = inkTertiary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            }
        },
    )
}

/** A row with a text field at the right, as the web's input rows; [problem] shows in red under the title. */
@Composable
internal fun FieldRow(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    detail: String? = null,
    problem: String? = null,
    secret: Boolean = false,
    inset: Dp = TEXT_INSET,
) {
    SettingRow(
        title, modifier.hairline(inset).padding(start = inset - TEXT_INSET),
        detail = problem ?: detail, detailColour = if (problem != null) MaterialTheme.colorScheme.error else Color.Unspecified,
    ) { WebField(value, onChange, title, placeholder = placeholder, secret = secret, error = problem != null) }
}

/** A plain note inside a card: words only, no control, in the detail ink. */
@Composable
internal fun NoteRow(text: String, modifier: Modifier = Modifier, colour: Color = Color.Unspecified, inset: Dp = TEXT_INSET) {
    Text(
        text, style = rowDetailStyle().copy(lineHeight = 18.sp), color = if (colour == Color.Unspecified) inkSecondary() else colour,
        modifier = modifier.hairline(inset).fillMaxWidth().padding(start = inset, end = 16.dp, top = 12.dp, bottom = 12.dp),
    )
}

/** Pressed buttons sink a little, as the web's do (active:scale-.96), unless motion is off. */
@Composable
private fun Modifier.press(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !still()) 0.96f else 1f, tween(160, easing = Settle), label = "press")
    return this.scale(scale)
}

/** The web's main button: a pill in the key colour, white words. For the one thing a card is for. */
@Composable
internal fun KeyPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val key = keyColour()
    val fill = if (hovered && enabled) androidx.compose.ui.graphics.lerp(key, Color.White, 0.08f) else key
    Text(
        label, style = buttonStyle(), color = inkOn(key), maxLines = 1, softWrap = false,
        modifier = modifier.press(source).alpha(if (enabled) 1f else 0.45f).clip(CircleShape).background(fill)
            .hoverable(source).clickable(source, FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** The web's quiet button: a grey pill, the words in the page's ink. */
@Composable
internal fun QuietPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Text(
        label, style = buttonStyle(), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false,
        modifier = modifier.press(source).alpha(if (enabled) 1f else 0.45f).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (hovered && enabled) 0.15f else 0.1f))
            .hoverable(source).clickable(source, FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A row's own small action after its state (Connect, Disconnect, Switch), as the web's inline button. */
@Composable
internal fun RowAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Text(
        label, style = buttonStyle(12), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false,
        modifier = Modifier.press(source).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (hovered && enabled) 0.16f else 0.1f))
            .hoverable(source).clickable(source, FocusRing(RoundedCornerShape(12.dp)), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A small round icon button inside a row, such as moving a row up or down its list. */
@Composable
internal fun SmallIconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(if (hovered && enabled) fillQuaternary() else Color.Transparent)
            .hoverable(source).clickable(source, FocusRing(CircleShape), enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (enabled) inkSecondary() else inkTertiary().copy(alpha = 0.25f))
    }
}

/** Buttons in a row of their own inside a card, under the rows they act on. */
@Composable
internal fun ButtonsRow(modifier: Modifier = Modifier, inset: Dp = TEXT_INSET, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.hairline(inset).fillMaxWidth().padding(start = inset, end = 16.dp, top = 12.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

// ---- Brand glyphs ---------------------------------------------------------------------------------

/**
 * YouTube Music's mark (Simple Icons, CC0, as the phone's ic_youtube_music): the red disc,
 * its white ring and play triangle. The web sets it on a white square.
 */
internal val YouTubeMusicMark: ImageVector by lazy {
    ImageVector.Builder("YouTube Music", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M12,0C5.376,0 0,5.376 0,12s5.376,12 12,12s12,-5.376 12,-12S18.624,0 12,0Z"), fill = SolidColor(Color(0xFFFF0033)))
        addPath(
            addPathNodes("M12,19.104c-3.924,0 -7.104,-3.18 -7.104,-7.104S8.076,4.896 12,4.896s7.104,3.18 7.104,7.104s-3.18,7.104 -7.104,7.104Z M12,5.772c-3.432,0 -6.228,2.796 -6.228,6.228S8.568,18.228 12,18.228s6.228,-2.796 6.228,-6.228S15.432,5.772 12,5.772Z"),
            fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd,
        )
        addPath(addPathNodes("M9.684,15.54V8.46L15.816,12l-6.132,3.54Z"), fill = SolidColor(Color.White))
    }.build()
}

/** Discord's mark, the white controller face (as the phone's ic_discord), for the blurple square. */
internal val DiscordMark: ImageVector by lazy {
    ImageVector.Builder("Discord", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            addPathNodes(
                "M20.317,4.3698 l-4.8851,-1.5152 l-.0785,.0371 c-.211,.3753,-.4447,.8648,-.6083,1.2495 c-1.8447,-.2762,-3.68,-.2762,-5.4868,0 c-.1636,-.3933,-.4058,-.8742,-.6177,-1.2495 l-.0785,-.037 l-4.8852,1.515 l-.0321,.0277 C.5334,9.0458,-.319,13.5799,.0992,18.0578 l.0312,.0561 c2.0528,1.5076,4.0413,2.4228,5.9929,3.0294 l.0842,-.0276 c.4616,-.6304,.8731,-1.2952,1.226,-1.9942 l-.0416,-.1057 c-.6528,-.2476,-1.2743,-.5495,-1.8722,-.8923 l-.0076,-.1277 c.1258,-.0943,.2517,-.1923,.3718,-.2914 l.0776,-.0105 c3.9278,1.7933,8.18,1.7933,12.0614,0 l.0785,.0095 c.1202,.099,.246,.1981,.3728,.2924 l-.0066,.1276 l-1.873,.8914 l-.0407,.1067 c.3604,.698,.7719,1.3628,1.225,1.9932 l.0842,.0286 c1.961,-.6067,3.9495,-1.5219,6.0023,-3.0294 l.0313,-.0552 c.5004,-5.177,-.8382,-9.6739,-3.5485,-13.6604 l-.0312,-.0286 z M8.02,15.3312 c-1.1825,0,-2.1569,-1.0857,-2.1569,-2.419 c0,-1.3332,.9555,-2.4189,2.157,-2.4189 c1.2108,0,2.1757,1.0952,2.1568,2.419 c0,1.3332,-.9555,2.4189,-2.1569,2.4189 z m7.9748,0 c-1.1825,0,-2.1569,-1.0857,-2.1569,-2.419 c0,-1.3332,.9554,-2.4189,2.1569,-2.4189 c1.2108,0,2.1757,1.0952,2.1568,2.419 c0,1.3332,-.946,2.4189,-2.1568,2.4189 Z",
            ),
            fill = SolidColor(Color.White),
        )
    }.build()
}

/** The brands' own colours, as the web's integration rows use them. */
internal object Brand {
    val YouTubeMusic = Color.White
    val Discord = Color(0xFF5865F2)
    val LastFm = Color(0xFFD51007)
    val JioSaavn = Color(0xFF2BC5B4)
    val JioSaavnInk = Color(0xFF08201D)
    val Server = Color(0xFF5E5CE6)
    val Grey = Color(0xFF636366)
}

/** YouTube Music's square: the mark on white, as the web's. */
@Composable
internal fun YouTubeMusicTile() {
    AppTile(Brand.YouTubeMusic) { androidx.compose.foundation.Image(YouTubeMusicMark, null, Modifier.size(18.dp)) }
}

/** Discord's square: the white face on blurple. */
@Composable
internal fun DiscordTile() {
    AppTile(Brand.Discord) { androidx.compose.foundation.Image(DiscordMark, null, Modifier.size(17.dp)) }
}

/** Last.fm's square: its red, with the lowercase "fm" of its wordmark. */
@Composable
internal fun LastFmTile() = LetterTile("fm", Brand.LastFm, Color.White)
