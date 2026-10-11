package com.opentune.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.DesignDockVisibility
import com.opentune.ui.theme.LocalInterface
import com.opentune.ui.theme.designSurface

/** An optional dock. The original dock and all of its motion modes remain available. */
@Composable
internal fun DesignChrome(
    inline: Boolean,
    tabs: List<ChromeTab>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    mini: (@Composable (Boolean, Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val design = LocalInterface.current
    val folded = inline && design.options.dockVisibility == DesignDockVisibility.FOLLOW_SCROLL
    val height by animateDpAsState(if (folded) 60.dp else CHROME_TAB_HEIGHT, design.spec(), label = "designDockHeight")
    val destinations = tabs.take(2) + ChromeTab("Search", Icons.Rounded.Search) + tabs.drop(2)
    val active = if (searchSelected) 2 else selected?.let { if (it >= 2) it + 1 else it }
    Column(modifier.fillMaxWidth().padding(horizontal = design.gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        mini?.invoke(false, Modifier.fillMaxWidth().height(CHROME_MINI_HEIGHT))
        BoxWithConstraints(Modifier.fillMaxWidth().designSurface().padding(6.dp)) {
            val cell = maxWidth / destinations.size
            val x by animateDpAsState(cell * (active ?: 0), design.spec(), label = "designDockSelection")
            if (active != null) {
                // Dp offset is relative to layout direction, including RTL.
                Box(Modifier.offset(x = x).width(cell).height(height - 12.dp).background(
                    MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(if (design.editorial) 10.dp else 22.dp),
                ))
            }
            Row(Modifier.fillMaxWidth().height(height - 12.dp).selectableGroup()) {
                destinations.forEachIndexed { index, tab ->
                    val chosen = index == active
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .semantics { contentDescription = tab.label }
                            .selectable(chosen, role = Role.Tab, onClick = {
                                if (index == 2) onSearch() else onSelect(if (index > 2) index - 1 else index)
                            }),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(if (chosen) tab.selectedIcon else tab.icon, null, Modifier.size(22.dp), tint =
                            if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!folded) Text(tab.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
            }
        }
    }
}
