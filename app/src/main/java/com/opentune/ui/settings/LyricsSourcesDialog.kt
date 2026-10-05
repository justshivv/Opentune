package com.opentune.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.DEFAULT_LYRICS_SOURCES
import com.opentune.data.settings.LyricsSourceEntry
import com.opentune.ui.components.AppSwitch
import kotlin.math.roundToInt

private val ROW = 68.dp

/**
 * Where lyrics come from, in the order they're tried. Drag a row by its
 * handle to move it; tap it to switch that source on or off.
 */
@Composable
fun LyricsSourcesDialog(onDismiss: () -> Unit) {
    val settings by AppSettings.lyrics.collectAsState()
    val haptics = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    var dragged by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val order = settings.ordered
    val latest by rememberUpdatedState(order)

    fun save(list: List<LyricsSourceEntry>) = AppSettings.updateLyrics { it.copy(sources = list) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics sources", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(
                    "Sources are tried in this order. Drag to reorder them. The first source with lyrics wins unless word-by-word lyrics are prioritized.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                HorizontalDivider()
                order.forEachIndexed { i, entry ->
                  // Keyed by source, so a row keeps its drag as it changes place.
                  key(entry.source) {
                    val isDragged = dragged == i
                    val lift by animateFloatAsState(if (isDragged) 1.03f else 1f, label = "lift")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragged) offset else 0f
                                scaleX = lift
                                scaleY = lift
                            }
                            .clickable {
                                save(order.mapIndexed { j, e -> if (j == i) e.copy(enabled = !e.enabled) else e })
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            "Drag to reorder",
                            Modifier
                                .padding(end = 12.dp)
                                .size(24.dp)
                                .pointerInput(entry.source) {
                                    detectDragGestures(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragged = latest.indexOfFirst { it.source == entry.source }
                                            offset = 0f
                                        },
                                        onDragEnd = { dragged = null; offset = 0f },
                                        onDragCancel = { dragged = null; offset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        val at = dragged ?: return@detectDragGestures
                                        offset += amount.y
                                        val target = (at + (offset / rowPx).roundToInt()).coerceIn(0, latest.lastIndex)
                                        if (target != at) {
                                            save(latest.toMutableList().apply { add(target, removeAt(at)) })
                                            offset -= (target - at) * rowPx
                                            dragged = target
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entry.source.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                entry.source.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Rounded.Check,
                            if (entry.enabled) "On" else "Off",
                            Modifier.padding(start = 12.dp).size(24.dp),
                            tint = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                        )
                    }
                    HorizontalDivider()
                  }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { AppSettings.updateLyrics { it.copy(preferWordSynced = !it.preferWordSynced) } }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Prioritize word-by-word lyrics", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Keep searching past a line-synced match for word-by-word lyrics",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppSwitch(settings.preferWordSynced, { v -> AppSettings.updateLyrics { it.copy(preferWordSynced = v) } })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { AppSettings.updateLyrics { it.copy(sources = DEFAULT_LYRICS_SOURCES, preferWordSynced = false) } }) {
                Text("Reset to default")
            }
        },
    )
}
