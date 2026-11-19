package com.opentune.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Room around the panel inside its popup, so its shadow isn't cut off. */
private val SHADOW_ROOM = 12.dp

/** Closes the menu an item sits in, so choosing one closes it as the web's do. */
private val LocalMenuClose = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Where a menu opens. [At] a point inside the element it belongs to (a right click there), or
 * [BelowEnd] the element, its right edge lined up with the element's (a ⋯ button).
 */
internal sealed interface MenuAnchor {
    data class At(val point: IntOffset) : MenuAnchor
    data object BelowEnd : MenuAnchor
}

/**
 * The web player's menu (components/Menu.tsx): a frosted panel 185 to 300 wide with 5px of
 * padding and 10px corners, a hairline ring and a soft shadow. It grows in from the corner
 * nearest where it was asked for, 0.97 to full size and from clear, in 160ms on the emphasized
 * curve. Opened at the pointer it sits below it, or above it when the pointer is low in the
 * window. Escape, a click outside, or choosing an item closes it; the arrow keys move between
 * the items and Enter picks one.
 *
 * Place it inside the element it belongs to; [anchor] says where in that element it opens.
 */
@Composable
internal fun WebMenu(anchor: MenuAnchor, onDismiss: () -> Unit, label: String, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val origin = remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val provider = remember(anchor, density) {
        MenuPosition(anchor, with(density) { 4.dp.roundToPx() }, with(density) { 6.dp.roundToPx() }, with(density) { 8.dp.roundToPx() }, with(density) { SHADOW_ROOM.roundToPx() }, origin)
    }
    val still = stillMotion()
    val grown = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { grown.animateTo(1f, tween(160, easing = Web.Emph)) }
    val panel = remember { FocusRequester() }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && run { onDismiss(); true } },
    ) {
        val focus = LocalFocusManager.current
        val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
        // --systemStandardThickMaterialSover, a touch more solid: a popup has no page of its own to blur.
        val material = lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onSurface, if (dark) 0.06f else 0.02f).copy(alpha = 0.97f)
        val shape = RoundedCornerShape(10.dp)
        LaunchedEffect(Unit) { runCatching { panel.requestFocus() } }
        CompositionLocalProvider(LocalMenuClose provides onDismiss) {
            Column(
                Modifier
                    .graphicsLayer {
                        val p = grown.value
                        alpha = p
                        scaleX = 0.97f + 0.03f * p
                        scaleY = 0.97f + 0.03f * p
                        transformOrigin = origin.value
                    }
                    .padding(SHADOW_ROOM)
                    .shadow(22.dp, shape, ambientColor = Color.Black.copy(alpha = if (dark) 0.38f else 0.13f), spotColor = Color.Black.copy(alpha = if (dark) 0.38f else 0.13f))
                    .clip(shape)
                    .background(material)
                    .border(0.5.dp, WebInk.vibrantDivider, shape)
                    .widthIn(min = 185.dp, max = 300.dp)
                    .width(IntrinsicSize.Max)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(5.dp)
                    .semantics { contentDescription = label }
                    .focusRequester(panel)
                    // The panel takes focus as it opens, so the keys reach it; the first arrow press steps onto an item.
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown, Key.Tab -> { focus.moveFocus(FocusDirection.Next); true }
                            Key.DirectionUp -> { focus.moveFocus(FocusDirection.Previous); true }
                            else -> false
                        }
                    },
            ) { content() }
        }
    }
}

/**
 * Places the menu as the web does (Menu.tsx): at a point, 4px below it while the point is in
 * the top 60% of the window and 4px above it lower down; under an element, 6px below its
 * bottom edge, right edges together, or above it when there's no room below. Kept 8px inside
 * the window. Notes the corner it grows from as it places it.
 */
private class MenuPosition(
    private val anchor: MenuAnchor,
    private val pointGap: Int,
    private val elementGap: Int,
    private val margin: Int,
    private val inset: Int,
    private val origin: MutableState<TransformOrigin>,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        // The popup carries room for the panel's shadow on every side ([inset]); the rules are for the panel's own edges.
        val w = popupContentSize.width
        val h = popupContentSize.height
        fun left(x: Int) = x.coerceAtMost(windowSize.width - w + inset - margin).coerceAtLeast(margin - inset)
        fun top(y: Int) = y.coerceAtMost(windowSize.height - h + inset - margin).coerceAtLeast(margin - inset)
        return when (anchor) {
            is MenuAnchor.At -> {
                val x = anchorBounds.left + anchor.point.x
                val y = anchorBounds.top + anchor.point.y
                val below = y < windowSize.height * 0.6f
                origin.value = TransformOrigin(0f, if (below) 0f else 1f)
                if (below) IntOffset(left(x - inset), top(y + pointGap - inset))
                else IntOffset(left(x - inset), top(y - pointGap - h + inset))
            }
            MenuAnchor.BelowEnd -> {
                val x = anchorBounds.right - w + inset
                val roomBelow = windowSize.height - anchorBounds.bottom
                val below = roomBelow >= h - 2 * inset + elementGap + margin || roomBelow >= anchorBounds.top
                origin.value = TransformOrigin(1f, if (below) 0f else 1f)
                if (below) IntOffset(left(x), top(anchorBounds.bottom + elementGap - inset))
                else IntOffset(left(x), top(anchorBounds.top - elementGap - h + inset))
            }
        }
    }
}

/**
 * One item (Menu.tsx's MenuItem): at least 28 tall, 8px in from the panel's sides, a 20px slot
 * for its icon, 13px text; the faint fill under the pointer or the keyboard. [checked] shows a
 * tick in a slot of its own when not null; [destructive] is red; [trailing] sits at the end.
 */
@Composable
internal fun WebMenuItem(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    checked: Boolean? = null,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val close = LocalMenuClose.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val ink = when {
        !enabled -> WebInk.tertiary
        destructive -> MaterialTheme.colorScheme.error
        else -> WebInk.primary
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp).clip(RoundedCornerShape(5.dp))
            .background(if ((hovered || focused) && enabled) WebInk.quinary else Color.Transparent)
            .hoverable(source, enabled)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button) { close(); onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (checked != null) Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Rounded.Check, "Chosen", tint = WebInk.key, modifier = Modifier.size(14.dp))
        }
        if (icon != null) Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (destructive && enabled) ink else WebInk.secondary.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(15.dp))
        }
        Text(label, style = WebType.body, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = WebType.body, color = WebInk.secondary, maxLines = 1)
    }
}

/** The hairline between groups of items: 8px in from the sides, 5px above and below. */
@Composable
internal fun WebMenuSeparator() {
    Box(Modifier.padding(horizontal = 8.dp, vertical = 5.dp).fillMaxWidth().height(0.5.dp).background(WebInk.vibrantDivider))
}

/** A heading over some of a menu's items, which can't be chosen. */
@Composable
internal fun WebMenuHeader(text: String) {
    Text(text, style = WebType.subheadEmph, color = WebInk.secondary, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp))
}
