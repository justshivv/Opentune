package com.opentune.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import com.opentune.ui.theme.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/** Small gray uppercase label above a group. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    if (posterLayout()) {
        // The Poster layout's: a hairline across, the name in spaced capitals under it.
        Column(modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 8.dp)) {
            HorizontalDivider(Modifier.padding(bottom = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            PosterCaps(com.opentune.ui.theme.tr(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        }
        return
    }
    Text(
        com.opentune.ui.theme.tr(text).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 10.dp),
    )
}

/** A rounded card holding rows, with hairline dividers between them. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // The Poster layout lists its rows straight on the page, with hairlines between, no card.
    if (posterLayout()) {
        Column(modifier.fillMaxWidth().padding(horizontal = 8.dp), content = content)
        return
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        thickness = 0.75.dp,
        color = if (posterLayout()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else MaterialTheme.colorScheme.outlineVariant,
    )
}

/** How much of a setting's colour shows while it is off because another setting makes it do nothing. */
const val DISABLED_ROW_ALPHA = 0.38f

/**
 * One setting: optional icon, title, summary, and whatever sits on the
 * right (a switch, a value and chevron, nothing).
 *
 * With [enabled] false the row is greyed out and doesn't respond, the controls
 * under it ([below]) included, and [disabledReason], when given, stands in for
 * the summary: what turns the setting on. What it's set to is left as it was.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    /** Shown where the icon would be; an avatar, say. Takes the icon's place when set. */
    leading: (@Composable () -> Unit)? = null,
    /** Still being worked on: marked with a small "Beta" tag after the title. */
    beta: Boolean = false,
    /** Shown in the summary's place while [enabled] is false, saying what turns the setting on. */
    disabledReason: String? = null,
    /** Caps on the title's and summary's lines, the rest ending in an ellipsis; uncapped by default. */
    titleMaxLines: Int = Int.MAX_VALUE,
    summaryMaxLines: Int = Int.MAX_VALUE,
) {
    val alpha = if (enabled) 1f else DISABLED_ROW_ALPHA
    val shownSummary = if (!enabled && disabledReason != null) disabledReason else summary
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Box(Modifier.width(26.dp))
            } else if (icon != null) {
                Icon(icon, null, tint = (if (posterLayout()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha), modifier = Modifier.size(26.dp))
                Box(Modifier.width(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(com.opentune.ui.theme.tr(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), modifier = Modifier.weight(1f, fill = false), maxLines = titleMaxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (beta) BetaTag(Modifier.padding(start = 8.dp).alpha(alpha))
                }
                if (!shownSummary.isNullOrBlank()) {
                    Text(
                        com.opentune.ui.theme.tr(shownSummary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = summaryMaxLines,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Box(Modifier.padding(start = 12.dp)) { trailing() }
            }
        }
        if (below != null) {
            Column(
                Modifier.padding(start = if (icon != null || leading != null) 52.dp else 0.dp, top = 12.dp)
                    .then(if (enabled) Modifier else Modifier.inert()),
                content = below,
            )
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    beta: Boolean = false,
    /** See [SettingRow]: what turns the setting on, shown while it's off. */
    disabledReason: String? = null,
) {
    SettingRow(title, summary = summary, icon = icon, enabled = enabled, beta = beta, disabledReason = disabledReason, onClick = { onChange(!checked) }, trailing = {
        AppSwitch(checked, onChange, enabled)
    })
}

@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    dot: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    /** Off where the setting has no effect: dimmed, value and arrow too, and it doesn't open. */
    enabled: Boolean = true,
    /** A small pill on the right, like a version number; null shows none. */
    badge: String? = null,
    /** Working: a small spinner stands where the arrow goes, and the row doesn't open until it is done. */
    busy: Boolean = false,
    /** See [SettingRow]: what turns the setting on, shown while it's off. */
    disabledReason: String? = null,
    /** See [SettingRow]: caps on the title's and summary's lines. */
    titleMaxLines: Int = Int.MAX_VALUE,
    summaryMaxLines: Int = Int.MAX_VALUE,
) {
    val alpha = if (enabled) 1f else DISABLED_ROW_ALPHA
    SettingRow(title, summary = summary, icon = icon, enabled = enabled, disabledReason = disabledReason, titleMaxLines = titleMaxLines, summaryMaxLines = summaryMaxLines, onClick = if (busy) null else onClick, leading = leading, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot) UpdateDot(Modifier.padding(end = 6.dp))
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (value != null) {
                Text(com.opentune.ui.theme.tr(value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            } else {
                Icon(ImageVector.vectorResource(AppIcons.KeyboardArrowRight), null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
    })
}

/**
 * The controls under a setting that's off: greyed with the row, and out of reach of touch and
 * of TalkBack (the row's summary says why), so a slider or a choice there can't be moved.
 */
private fun Modifier.inert(): Modifier = this
    .alpha(DISABLED_ROW_ALPHA)
    .clearAndSetSemantics { }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            // Taken on the way down, before the controls under it see it.
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

/** The small "Beta" tag on a setting that still has rough edges. */
@Composable
fun BetaTag(modifier: Modifier = Modifier) {
    Text(
        "Beta",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** High-contrast switch: a light track with a dark thumb when on. */
@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.onSurface,
            checkedThumbColor = c.background,
            checkedBorderColor = c.onSurface,
            uncheckedTrackColor = c.surfaceContainerHighest,
            uncheckedThumbColor = c.onSurfaceVariant.copy(alpha = 0.6f),
            uncheckedBorderColor = c.surfaceContainerHighest,
        ),
    )
}

/** A segmented choice drawn as a sliding light pill on a dark track. */
@Composable
fun <T> PillSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    if (options.isEmpty()) return
    val c = MaterialTheme.colorScheme
    val reduced = com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceAnimation
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = options.map { com.opentune.ui.theme.tr(label(it)) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val segment = (maxWidth - 10.dp) / options.size
        val labelHeight = labels.maxOf { text ->
            measurer.measure(text, MaterialTheme.typography.labelLarge,
                // Weighted rows round each segment to whole pixels. Measure
                // conservatively so a one-pixel difference cannot add a clipped line.
                constraints = Constraints(maxWidth = with(density) { (segment - 16.dp).toPx().toInt().minus(1).coerceAtLeast(1) })).size.height
        }
        // Keep the whole label at larger font sizes instead of silently clipping it.
        val height = maxOf(58.dp, with(density) { labelHeight.toDp() } + 26.dp)
        // The Poster layout's: a clear glass capsule with a capsule picking the choice.
        val poster = posterLayout()
        val inner = if (poster) RoundedCornerShape(percent = 50) else RoundedCornerShape(12.dp)
        Box(Modifier.fillMaxWidth().height(height)
            .then(if (poster) Modifier.posterGlass(RoundedCornerShape(percent = 50))
            else Modifier.clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHighest.copy(alpha = 0.7f)))
            .padding(5.dp)) {
            val index = options.indexOf(selected).coerceAtLeast(0)
            val x by animateDpAsState(segment * index, if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 500f), label = "pill")
            Box(
                Modifier
                    .offset { IntOffset(x.roundToPx(), 0) }
                    .width(segment)
                    .fillMaxHeight()
                    .clip(inner)
                    .background(c.onSurface),
            )
            Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
                options.forEachIndexed { i, option ->
                    val color by animateColorAsState(if (i == index) c.background else c.onSurfaceVariant, if (reduced) snap() else spring(), label = "pillText")
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(inner)
                            .selectable(selected = i == index, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(labels[i], color = color, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
