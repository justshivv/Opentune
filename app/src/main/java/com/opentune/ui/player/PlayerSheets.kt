package com.opentune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.RemixPreset
import com.opentune.data.settings.ReverbLevel
import com.opentune.playback.SoundEffects
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import kotlin.math.roundToInt

/**
 * Speed, pitch, reverb and bass, with one-tap presets. Changes apply live;
 * they're kept across restarts, and the player shows a pill while any are on.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemixSheet(onDismiss: () -> Unit) {
    val sound by AppSettings.sound.collectAsState()
    val reverbAvailable by SoundEffects.reverbAvailable.collectAsState()
    var linked by rememberSaveable { mutableStateOf(sound.speed == sound.pitch) }
    val preset = RemixPreset.matching(sound)

    FloatingCard(
        onDismiss = onDismiss,
        title = "Remix",
        icon = Icons.Rounded.GraphicEq,
        subtitle = preset?.label ?: "Custom",
        contentPadding = PaddingValues(0.dp),
        actions = {
            SheetButton("Reset", onClick = { AppSettings.updateSound { RemixPreset.NORMAL.sound } }, modifier = Modifier.weight(1f), enabled = !sound.isDefault)
            SheetButton("Done", onClick = onDismiss, modifier = Modifier.weight(1f), tone = SheetTone.Primary, closes = true)
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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

            Text("8D audio", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text("The sound circles slowly around your head. Best on headphones.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.opentune.data.settings.EightD.entries.forEach { e ->
                    FilterChip(
                        selected = sound.eightD == e,
                        onClick = { AppSettings.updateSound { it.copy(eightD = e) } },
                        label = { Text(e.label) },
                    )
                }
            }
            Text(
                "Speed, pitch and bass work on every phone. Reverb uses Android's built-in effect, which some phones don't have.",
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
