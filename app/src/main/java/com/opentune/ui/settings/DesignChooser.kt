package com.opentune.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.model.Song
import com.opentune.data.settings.*
import com.opentune.ui.BottomChrome
import com.opentune.ui.ChromeTab
import com.opentune.ui.components.DesignFeatureCard
import com.opentune.ui.player.PlayerControls
import com.opentune.ui.theme.LocalInterface
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.ui.theme.PlayerTheme

/** A local draft: closing, backing out or rotating never applies a partial preset. */
@Composable
internal fun DesignChooser(
    ui: InterfaceSettings,
    theme: ThemeSettings,
    onDismiss: () -> Unit,
    onApply: (DesignOptions) -> Unit,
) {
    var style by rememberSaveable { mutableStateOf(ui.design.style) }
    var motion by rememberSaveable { mutableStateOf(ui.design.motion) }
    var density by rememberSaveable { mutableStateOf(ui.design.density) }
    var dock by rememberSaveable { mutableStateOf(ui.design.dockVisibility) }
    var playerPreview by rememberSaveable { mutableStateOf(false) }
    val options = DesignOptions(style, motion, density, dock)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(12.dp).fillMaxHeight(.94f), shape = RoundedCornerShape(28.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Interface design", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close design preview") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text("Try a complete new look. Your colour, player styles and individual effects stay saved.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            InterfaceDesign.entries.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    pair.forEach { option ->
                                        Column(
                                            Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                                .background(if (style == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                                                .border(if (style == option) 2.dp else 1.dp,
                                                    if (style == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                                                .selectable(style == option, role = Role.RadioButton, onClick = { style = option })
                                                .padding(14.dp),
                                        ) {
                                            Text(option.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                            Spacer(Modifier.height(6.dp))
                                            Text(option.summary, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        DesignChoices(listOf(false, true), playerPreview, { if (it) "Player preview" else "Home preview" }, { playerPreview = it })
                        Spacer(Modifier.height(10.dp))
                        Text("Preview · sample music", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        DesignPreview(ui.withDesign(options), theme, playerPreview)
                    }
                    item {
                        Text("Design motion", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        DesignChoices(DesignMotion.entries, motion, { it.label }, { motion = it }, enabled = style != InterfaceDesign.CURRENT)
                        Text(if (style == InterfaceDesign.CURRENT) "Current uses your existing animation settings."
                            else if (motion == DesignMotion.INHERIT) "Existing keeps your original dock and individual effects. Choose Calm, Fluid or Expressive to try this design’s dock."
                            else motion.summary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ui.reduceAnimation) Text("Reduce animation is on and takes priority.", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Text("Layout density", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        DesignChoices(DesignDensity.entries, density, { it.label }, { density = it }, enabled = style != InterfaceDesign.CURRENT)
                    }
                    item {
                        Text("Dock visibility", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        DesignChoices(DesignDockVisibility.entries, dock, { it.label }, { dock = it }, enabled = style != InterfaceDesign.CURRENT)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                    Button(onClick = { onApply(options) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Apply design") }
                }
            }
        }
    }
}

/** Wrapping choices remain legible with large fonts and narrow windows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> DesignChoices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            FilterChip(selected == option, onClick = { onSelect(option) }, label = { Text(label(option)) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

private val previewSong = Song("preview", "After the rain", "The night garden", null)

/** The preview uses the same theme, feature card, transport buttons and dock as the app. */
@Composable
internal fun DesignPreview(ui: InterfaceSettings, theme: ThemeSettings, player: Boolean, modifier: Modifier = Modifier) {
    var playing by remember { mutableStateOf(false) }
    var destination by remember { mutableIntStateOf(0) }
    OpenTuneTheme(theme, ui = ui) {
        Surface(modifier.fillMaxWidth().clip(MaterialTheme.shapes.large), color = MaterialTheme.colorScheme.background) {
            if (player) {
                PlayerTheme(null, theme) {
                    Column(Modifier.background(MaterialTheme.colorScheme.background).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        if (LocalInterface.current.flow) {
                            Box(Modifier.fillMaxWidth().height(290.dp)) {
                                com.opentune.ui.player.FlowPlayerStage(previewSong, 0, 12,
                                    artwork = { artModifier -> Box(artModifier, contentAlignment = Alignment.Center) { PreviewArtwork(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge)) } },
                                    actions = { Icon(Icons.Outlined.FavoriteBorder, "Like"); Icon(Icons.Outlined.MoreHoriz, "More") })
                            }
                        } else {
                            PreviewArtwork(Modifier.size(156.dp).clip(MaterialTheme.shapes.extraLarge))
                            Spacer(Modifier.height(16.dp))
                            Text(previewSong.title, style = MaterialTheme.typography.titleLarge)
                            Text(previewSong.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(value = .36f, onValueChange = {}, modifier = Modifier.fillMaxWidth())
                        PlayerControls(playing, false, true, { playing = !playing }, {}, {}, style = ui.controlStyle, animate = !LocalInterface.current.reduceMotion)
                    }
                }
            } else {
                Column(Modifier.padding(vertical = 18.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        Text("Good evening", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("Your daily soundtrack", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                        if (LocalInterface.current.active) {
                            DesignFeatureCard(previewSong, { playing = !playing }, artwork = { PreviewArtwork(it.clip(MaterialTheme.shapes.medium)) })
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PreviewArtwork(Modifier.size(80.dp).clip(MaterialTheme.shapes.medium))
                                Column { Text("Recents", style = MaterialTheme.typography.titleLarge); Text(previewSong.title); Text(previewSong.artist, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        Text(if (playing) "Playing sample preview" else "Made for your mood", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    BottomChrome(false, listOf(ChromeTab("Home", Icons.Outlined.Home), ChromeTab("Explore", Icons.Outlined.Explore),
                        ChromeTab("Podcasts", Icons.Outlined.Podcasts), ChromeTab("Library", Icons.Outlined.LibraryMusic)),
                        if (destination == 4) null else destination, { destination = it }, {}, destination == 4, { destination = 4 }, null)
                }
            }
        }
    }
}

/** Local sample art; previews work offline and never make a network request. */
@Composable
private fun PreviewArtwork(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF102D46), Color(0xFF427F80), Color(0xFFF2BB84))))
        drawCircle(Color(0xFFF7D6AD), size.minDimension * .18f, Offset(size.width * .7f, size.height * .3f))
        drawCircle(Color(0xFF183D4F), size.width * .72f, Offset(size.width * .2f, size.height * 1.15f))
        drawCircle(Color(0xFF2C6470), size.width * .65f, Offset(size.width * .95f, size.height * 1.06f))
    }
}
