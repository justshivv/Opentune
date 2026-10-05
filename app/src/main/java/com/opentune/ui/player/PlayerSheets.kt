package com.opentune.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ReverbLevel
import com.opentune.data.settings.RemixPreset
import com.opentune.playback.SoundEffects
import com.opentune.ui.components.SongListItem
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<Song>,
    currentIndex: Int,
    upNext: List<Int>,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val autoplay by AppSettings.autoplay.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${upNext.size} songs" + if (shuffle) " • shuffled" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterChip(selected = shuffle, onClick = onShuffle, label = { Text("Shuffle") }, leadingIcon = { Icon(Icons.Rounded.Shuffle, null) })
            FilterChip(
                selected = repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onRepeat,
                label = { Text(if (repeatMode == Player.REPEAT_MODE_ONE) "One" else "Repeat") },
                leadingIcon = { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            queue.getOrNull(currentIndex)?.let { current ->
                item(key = "now") {
                    Text(
                        "Now playing",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                    )
                    SongListItem(current, onClick = {}, isCurrent = true, isPlaying = isPlaying)
                }
            }
            items(upNext, key = { "$it:${queue.getOrNull(it)?.videoId}" }) { index ->
                val song = queue.getOrNull(index) ?: return@items
                SongListItem(song, onClick = { onPlayIndex(index) }) {
                    IconButton(onClick = { onRemoveIndex(index) }) { Icon(Icons.Rounded.Close, "Remove from queue") }
                }
            }
            item(key = "autoplay") {
                ListItem(
                    headlineContent = { Text("Autoplay") },
                    supportingContent = {
                        Text(
                            when {
                                repeatMode != Player.REPEAT_MODE_OFF -> "Paused while repeat is on"
                                autoplay -> "Similar songs keep playing when the queue runs out"
                                else -> "Playback stops at the end of the queue"
                            },
                        )
                    },
                    trailingContent = { Switch(autoplay, AppSettings::setAutoplay) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * Speed, pitch, reverb and bass, with one-tap presets. Changes apply live;
 * they're kept across restarts, and the player shows a pill while any are on.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemixSheet(onDismiss: () -> Unit) {
    val sound by AppSettings.sound.collectAsState()
    val reverbAvailable by SoundEffects.reverbAvailable.collectAsState()
    val bassAvailable by SoundEffects.bassAvailable.collectAsState()
    var linked by rememberSaveable { mutableStateOf(sound.speed == sound.pitch) }
    val preset = RemixPreset.matching(sound)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remix", style = MaterialTheme.typography.titleLarge)
                    Text(
                        preset?.label ?: "Custom",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { AppSettings.updateSound { RemixPreset.NORMAL.sound } }, enabled = !sound.isDefault) { Text("Reset") }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemixPreset.entries.forEach { p ->
                    FilterChip(
                        selected = preset == p,
                        onClick = {
                            AppSettings.updateSound { p.sound }
                            linked = p.sound.speed == p.sound.pitch
                        },
                        label = { Text(p.label) },
                    )
                }
            }

            ValueSlider("Speed", sound.speed, 0.5f..2f, format = { "%.2f×".format(it) }) { v ->
                AppSettings.updateSound { if (linked) it.copy(speed = v, pitch = v) else it.copy(speed = v) }
            }
            ValueSlider("Pitch", sound.pitch, 0.5f..2f, format = { semitones(it) }) { v ->
                AppSettings.updateSound { if (linked) it.copy(speed = v, pitch = v) else it.copy(pitch = v) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Link pitch to speed", style = MaterialTheme.typography.bodyLarge)
                    Text("Like a turntable: slower sounds deeper", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(linked, onCheckedChange = { on ->
                    linked = on
                    if (on) AppSettings.updateSound { it.copy(pitch = it.speed) }
                })
            }

            Text("Reverb", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            if (!reverbAvailable) Unsupported("This device doesn't offer a reverb effect.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReverbLevel.entries.forEach { level ->
                    FilterChip(
                        selected = sound.reverb == level,
                        onClick = { AppSettings.updateSound { it.copy(reverb = level) } },
                        label = { Text(level.label) },
                    )
                }
            }

            ValueSlider("Bass boost", sound.bassBoost / 1000f, 0f..1f, format = { "${(it * 100).roundToInt()}%" }) { v ->
                AppSettings.updateSound { it.copy(bassBoost = (v * 1000).roundToInt()) }
            }
            if (!bassAvailable) Unsupported("This device doesn't offer bass boost.")
            Text(
                "Reverb and bass use Android's built-in audio effects, which vary by phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 10.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = { onChange(snap(it)) }, valueRange = range)
    }
}

/** Snap to two decimals, and onto 1.0 when close, so "normal" is easy to hit. */
private fun snap(v: Float): Float {
    val rounded = (v * 100).roundToInt() / 100f
    return if (kotlin.math.abs(rounded - 1f) < 0.03f) 1f else rounded
}

private fun semitones(pitch: Float): String {
    val st = 12 * kotlin.math.ln(pitch.toDouble()) / kotlin.math.ln(2.0)
    val r = (st * 10).roundToInt() / 10.0
    return when {
        r > 0 -> "+$r st"
        r < 0 -> "$r st"
        else -> "0 st"
    }
}

@Composable
private fun Unsupported(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
