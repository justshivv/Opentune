package com.opentune.ui.settings

import androidx.compose.runtime.getValue
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.opentune.BuildConfig
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import com.opentune.data.settings.PaletteStyleOption
import com.opentune.data.settings.PlayerBackground
import com.opentune.data.settings.SEED_COLORS
import com.opentune.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val theme by AppSettings.theme.collectAsState()
    val autoplay by AppSettings.autoplay.collectAsState()
    val quality by AppSettings.audioQuality.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        androidx.compose.material3.LargeTopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            scrollBehavior = scroll,
        )
        LazyColumn(contentPadding = contentPadding) {
            item { ThemePreview() }

            item { Group("Appearance") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Label("Theme")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = theme.mode == mode,
                                onClick = { AppSettings.updateTheme { it.copy(mode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            ) { Text(mode.label) }
                        }
                    }
                }
            }
            item {
                Toggle(
                    "Color from artwork",
                    "Tint the app with the playing song's cover",
                    theme.colorFromArtwork,
                ) { v -> AppSettings.updateTheme { it.copy(colorFromArtwork = v) } }
            }
            if (wallpaperAvailable) {
                item {
                    Toggle(
                        "Material You",
                        "Use your wallpaper's colors when nothing is playing",
                        theme.dynamicColor,
                    ) { v -> AppSettings.updateTheme { it.copy(dynamicColor = v) } }
                }
            }
            item {
                AnimatedVisibility(!theme.dynamicColor || !wallpaperAvailable) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Label("Accent color")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SEED_COLORS.forEach { argb ->
                                val selected = theme.seedColor == argb
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                        .border(
                                            width = if (selected) 3.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape,
                                        )
                                        .clickable { AppSettings.updateTheme { it.copy(seedColor = argb) } },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) Icon(Icons.Filled.Check, null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Label("Palette style")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaletteStyleOption.entries.forEach { style ->
                            FilterChip(
                                selected = theme.paletteStyle == style,
                                onClick = { AppSettings.updateTheme { it.copy(paletteStyle = style) } },
                                label = { Text(style.label) },
                            )
                        }
                    }
                }
            }
            item {
                Toggle("Pure black", "True black backgrounds in dark theme, for OLED screens", theme.pureBlack) { v ->
                    AppSettings.updateTheme { it.copy(pureBlack = v) }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Label("Player background")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PlayerBackground.entries.forEachIndexed { i, bg ->
                            SegmentedButton(
                                selected = theme.playerBackground == bg,
                                onClick = { AppSettings.updateTheme { it.copy(playerBackground = bg) } },
                                shape = SegmentedButtonDefaults.itemShape(i, PlayerBackground.entries.size),
                            ) { Text(bg.label.substringBefore(' ').replaceFirstChar { it.uppercase() }) }
                        }
                    }
                }
            }

            item { Group("Playback") }
            item {
                Toggle("Autoplay", "Keep playing similar songs when the queue ends", autoplay, AppSettings::setAutoplay)
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Label("Audio quality")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioQuality.entries.forEach { q ->
                            FilterChip(selected = quality == q, onClick = { AppSettings.setAudioQuality(q) }, label = { Text(q.label) })
                        }
                    }
                }
            }

            item { Group("About") }
            item {
                ListItem(
                    headlineContent = { Text("OpenTune ${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text("Free software under the GNU GPL v3. Lyrics from LRCLIB.") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

/** A small card showing the scheme the current choices produce. */
@Composable
private fun ThemePreview() {
    val c = MaterialTheme.colorScheme
    Surface(
        color = c.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.medium).background(c.primaryContainer), Alignment.Center) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.primary))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.onSurface.copy(alpha = 0.8f)))
                Box(Modifier.fillMaxWidth(0.45f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.onSurfaceVariant.copy(alpha = 0.5f)))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(c.primary, c.secondary, c.tertiary, c.secondaryContainer, c.tertiaryContainer).forEach {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(it))
                    }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Toggle(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
