package com.opentune.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.settings.EqPreset
import com.opentune.data.settings.EqualizerSettings
import com.opentune.playback.dsp.DspParams
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.ToggleRow
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MAX_DB = 12f

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val eq by AppSettings.equalizer.collectAsState()
    val preset = EqPreset.entries.firstOrNull { it.bands == eq.bands }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Equalizer", onBack = onBack) }
        item {
            GroupCard {
                ToggleRow(
                    "Equalizer",
                    eq.enabled,
                    { v -> AppSettings.updateEqualizer { it.copy(enabled = v) } },
                    summary = "Boosts are balanced with automatic headroom, so they don't distort",
                    icon = Icons.Rounded.Equalizer,
                )
            }
        }
        item { GroupLabel("Response") }
        item {
            GroupCard {
                ResponseCurve(eq, Modifier.fillMaxWidth().height(120.dp).padding(16.dp))
                Row(Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    eq.bands.forEachIndexed { i, gain ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(formatDb(gain), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            VerticalGainSlider(
                                value = gain,
                                enabled = eq.enabled,
                                onChange = { v -> AppSettings.updateEqualizer { s -> s.copy(bands = s.bands.toMutableList().also { it[i] = v }) } },
                                modifier = Modifier.weight(1f).width(36.dp).padding(vertical = 6.dp),
                            )
                            Text(formatHz(EQ_BANDS_HZ[i]), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        item { GroupLabel("Presets") }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqPreset.entries.forEach { p ->
                    FilterChip(
                        selected = p == preset,
                        onClick = { AppSettings.updateEqualizer { it.copy(enabled = true, bands = p.bands) } },
                        label = { Text(p.label) },
                    )
                }
            }
        }
        item { GroupLabel("Tone") }
        item {
            GroupCard {
                DbSlider("Bass", eq.bassDb, eq.enabled) { v -> AppSettings.updateEqualizer { it.copy(bassDb = v) } }
                DbSlider("Treble", eq.trebleDb, eq.enabled) { v -> AppSettings.updateEqualizer { it.copy(trebleDb = v) } }
                DbSlider("Preamp", eq.preampDb, eq.enabled) { v -> AppSettings.updateEqualizer { it.copy(preampDb = v) } }
                LabeledSlider("Balance", eq.balance, -1f..1f, eq.enabled, format = ::formatBalance) { v ->
                    AppSettings.updateEqualizer { it.copy(balance = if (kotlin.math.abs(v) < 0.04f) 0f else v) }
                }
            }
        }
    }
}

/** The combined curve of every active filter, drawn from the same maths the DSP runs. */
@Composable
private fun ResponseCurve(eq: EqualizerSettings, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val stages = remember(eq) { DspParams(equalizer = eq.copy(enabled = true)).stages(48_000).map { it() } }
    val preamp = eq.preampDb
    Canvas(modifier) {
        val minLog = log10(20.0); val maxLog = log10(20_000.0)
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        val path = Path()
        val steps = 120
        for (s in 0..steps) {
            val f = 10.0.pow(minLog + (maxLog - minLog) * s / steps)
            val db = preamp + stages.sumOf { it.magnitudeDb(f, 48_000.0) }
            val y = size.height / 2 - (db / (MAX_DB * 1.5)).toFloat().coerceIn(-1f, 1f) * size.height / 2
            val x = size.width * s / steps
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line.copy(alpha = if (eq.enabled) 1f else 0.4f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A slim vertical fader: drag or tap to set, double-tap to reset to 0 dB. */
@Composable
private fun VerticalGainSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
    val thumb = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
    val current by rememberUpdatedState(onChange)
    fun valueAt(y: Float, height: Float) = ((0.5f - y / height) * 2 * MAX_DB).coerceIn(-MAX_DB, MAX_DB).let { (it * 2).roundToInt() / 2f }
    Canvas(
        modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onDoubleTap = { current(0f) }, onTap = { current(valueAt(it.y, size.height.toFloat())) })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    current(valueAt(change.position.y, size.height.toFloat()))
                }
            },
    ) {
        val w = 6.dp.toPx()
        val x = (size.width - w) / 2
        drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
        val mid = size.height / 2
        val y = mid - value / MAX_DB * mid
        drawRoundRect(fill, Offset(x, minOf(mid, y)), Size(w, kotlin.math.abs(mid - y)), CornerRadius(w / 2))
        drawCircle(thumb, 9.dp.toPx(), Offset(size.width / 2, y))
    }
}

@Composable
private fun DbSlider(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) =
    LabeledSlider(label, value, -MAX_DB..MAX_DB, enabled, format = ::formatDb) { onChange((it * 2).roundToInt() / 2f) }

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

private fun formatDb(db: Float): String = when {
    db > 0 -> "+%.1f".format(db)
    db < 0 -> "%.1f".format(db)
    else -> "0"
}

private fun formatHz(hz: Float): String = if (hz >= 1000) "${(hz / 1000).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }}k" else hz.toInt().toString()

private fun formatBalance(b: Float): String = when {
    b < -0.04f -> "L ${(-b * 100).roundToInt()}%"
    b > 0.04f -> "R ${(b * 100).roundToInt()}%"
    else -> "Centre"
}
