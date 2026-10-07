package com.opentune.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppearancePreset
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.MotionProfile
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.BottomChrome
import com.opentune.ui.ChromeTab

@Composable
internal fun AppearancePresets(theme: ThemeSettings, ui: InterfaceSettings, onPick: (AppearancePreset) -> Unit) {
    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Choose your look", style = MaterialTheme.typography.titleLarge)
            Text("Coordinated color, surfaces and player backgrounds.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(AppearancePreset.entries, key = { it.name }) { preset ->
                val chosen = preset.matches(theme, ui)
                Surface(
                    onClick = { onPick(preset) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.width(156.dp).semantics { selected = chosen; role = Role.RadioButton },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PresetSwatch(preset)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(preset.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (chosen) Icon(Icons.Rounded.Check, "Applied", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(preset.summary, minLines = 2, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetSwatch(preset: AppearancePreset) {
    val base = if (preset == AppearancePreset.PAPER) Color(0xFFF4EEE7) else Color(0xFF121318)
    val ink = if (preset == AppearancePreset.PAPER) Color(0xFF3A302C) else Color(0xFFF5F4F7)
    val accent = Color(preset.accent)
    Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(base)) {
        val unit = size.height
        drawRoundRect(accent, Offset(unit * 0.16f, unit * 0.16f), Size(unit * 0.48f, unit * 0.48f), CornerRadius(10.dp.toPx()))
        drawRoundRect(ink.copy(alpha = 0.8f), Offset(unit * 0.78f, unit * 0.22f), Size(size.width * 0.36f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        drawRoundRect(ink.copy(alpha = 0.3f), Offset(unit * 0.78f, unit * 0.4f), Size(size.width * 0.24f, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
        drawRoundRect(accent.copy(alpha = 0.25f), Offset(unit * 0.16f, unit * 0.78f), Size(size.width - unit * 0.32f, unit * 0.12f), CornerRadius(5.dp.toPx()))
    }
}

@Composable
internal fun MotionProfiles(ui: InterfaceSettings, onPick: (MotionProfile) -> Unit) {
    val active = MotionProfile.entries.firstOrNull { it.matches(ui) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Set the pace", style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MotionProfile.entries, key = { it.name }) { profile ->
                FilterChip(selected = active == profile, onClick = { onPick(profile) }, label = { Text(profile.label) })
            }
        }
        Text(
            if (ui.reduceAnimation) "Reduced animation is on. Your motion choices are saved for when you turn it off."
            else active?.summary ?: "Custom motion. Choose a profile or fine-tune the controls below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DockPreview(ui)
    }
}

/** The production dock, safe to tap and fold without navigating away from Settings. */
@Composable
private fun DockPreview(ui: InterfaceSettings) {
    var selected by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf(false) }
    var folded by remember { mutableStateOf(false) }
    val tabs = remember { listOf(ChromeTab("Home", Icons.Rounded.Home), ChromeTab("Explore", Icons.Rounded.Explore), ChromeTab("Library", Icons.Rounded.LibraryMusic)) }
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Try the dock", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { folded = !folded }) { Text(if (folded) "Expand" else "Fold") }
        }
        Box(Modifier.fillMaxWidth().height(100.dp).padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
            BottomChrome(
                inline = folded, tabs = tabs, selected = if (search) null else selected,
                onSelect = { selected = it; search = false }, onExpand = { folded = false },
                searchSelected = search, onSearch = { search = true }, mini = null,
                motion = ui.dockMotion, lens = ui.dockLens, reduceMotion = ui.reduceAnimation,
            )
        }
    }
}
