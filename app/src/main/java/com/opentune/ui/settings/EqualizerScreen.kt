package com.opentune.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.settings.EqGroup
import com.opentune.data.settings.EqPreset
import com.opentune.data.settings.EqualizerSettings
import com.opentune.playback.dsp.DspParams
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.components.NavRow
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SettingRow
import com.opentune.ui.components.ToggleRow
import com.opentune.ui.components.rememberHaptics
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MAX_DB = 12f
/** The graph's frequency span, on a log axis. */
private const val LOW_HZ = 20.0
private const val HIGH_HZ = 20_000.0

/**
 * The equalizer: a large response graph you can drag the bands on directly,
 * faders for each band, presets as cards that show their own curve, and the
 * tone controls. Everything glides to a new curve rather than jumping.
 */
@Composable
fun EqualizerScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val eq by AppSettings.equalizer.collectAsState()
    val preset = EqPreset.entries.firstOrNull { it.bands == eq.bands }
    var picking by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val phoneEq = remember { com.opentune.playback.PhoneEqualizer.available(context) }
    var group by remember { mutableStateOf(preset?.group ?: EqGroup.SIGNATURE) }
    if (picking) HeadphonePickerDialog(onDismiss = { picking = false })
    // Each band's shown value springs to the saved one, so a preset morphs into place.
    val shown = eq.bands.mapIndexed { i, g ->
        animateFloatAsState(g, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "band$i").value
    }
    val setBand: (Int, Float) -> Unit = { i, v -> AppSettings.updateEqualizer { s -> s.copy(enabled = true, bands = s.bands.toMutableList().also { it[i] = v }) } }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader(com.opentune.ui.theme.tr("Equalizer"), onBack = onBack) }
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
        item {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(top = 16.dp, bottom = 8.dp),
            ) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset?.label ?: "Custom", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            preset?.summary?.takeIf { it.isNotBlank() } ?: "Drag the points on the curve, or the faders below",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    if (eq.bands.any { it != 0f } || eq.bassDb != 0f || eq.trebleDb != 0f || eq.preampDb != 0f) {
                        TextButton(onClick = { AppSettings.updateEqualizer { it.copy(bands = List(EQ_BANDS_HZ.size) { 0f }, bassDb = 0f, trebleDb = 0f, preampDb = 0f) } }) {
                            Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Reset")
                        }
                    }
                }
                ResponseGraph(eq, shown, onBand = setBand, Modifier.fillMaxWidth().height(230.dp).padding(horizontal = 12.dp, vertical = 12.dp))
                // Fifteen faders don't fit across a phone, so the row scrolls.
                Row(
                    Modifier.fillMaxWidth().height(210.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    shown.forEachIndexed { i, gain ->
                        Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(formatDb(eq.bands[i]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Fader(
                                value = gain,
                                enabled = eq.enabled,
                                onChange = { v -> setBand(i, v) },
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
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqGroup.entries.forEach { g -> FilterChip(selected = g == group, onClick = { group = g }, label = { Text(g.label) }) }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(EqPreset.entries.filter { it.group == group }, key = { it.name }) { p ->
                    PresetCard(p, selected = p == preset) { AppSettings.updateEqualizer { it.copy(enabled = true, bands = p.bands) } }
                }
            }
        }
        item { GroupLabel("Headphones") }
        item {
            GroupCard {
                val hp = eq.headphone
                NavRow(
                    hp?.name ?: "Correct for your headphones",
                    { picking = true },
                    summary = hp?.let { "AutoEq correction measured by ${it.source} · ${it.filters.size} filters, ${formatDb(it.preampDb)} preamp" }
                        ?: "Pick your model from AutoEq's thousands of measurements",
                    icon = com.opentune.ui.player.PlayerIcons.Headphones,
                )
                if (hp != null) {
                    SettingRow("Turn off headphone correction", icon = Icons.Rounded.Close, onClick = { AppSettings.updateEqualizer { it.copy(headphone = null) } })
                }
            }
        }
        if (phoneEq) {
            item { GroupLabel("Phone") }
            item {
                GroupCard {
                    val effectsOn = AppSettings.playback.collectAsState().value.systemEffects
                    NavRow(
                        "Phone's equalizer",
                        {
                            // It only reaches playback while the phone's effects are let in.
                            if (!effectsOn) AppSettings.updatePlayback { it.copy(systemEffects = true) }
                            com.opentune.playback.PhoneEqualizer.open(context)
                        },
                        summary = if (effectsOn) "The equalizer that came with your phone, on top of this one"
                        else "The equalizer that came with your phone. Opening it turns on System audio effects",
                        icon = Icons.Rounded.PhoneAndroid,
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
                    AppSettings.updateEqualizer { it.copy(balance = if (abs(v) < 0.04f) 0f else v) }
                }
            }
        }
    }
}

/** Where [hz] sits across a graph [width] wide, on the log axis. */
private fun xOf(hz: Double, width: Float): Float =
    ((log10(hz) - log10(LOW_HZ)) / (log10(HIGH_HZ) - log10(LOW_HZ))).toFloat() * width

/** The combined response in dB at [hz], from the same filters the DSP runs. */
private fun responseAt(stages: List<com.opentune.playback.dsp.Biquad>, preamp: Float, hz: Double): Double =
    preamp + stages.sumOf { it.magnitudeDb(hz, 48_000.0) }

/**
 * The curve every active filter makes together, over a grid of dB and
 * frequency, with a glow under it, and a point on each band to drag up or
 * down; sliding sideways while dragging moves to the next band.
 */
@Composable
private fun ResponseGraph(eq: EqualizerSettings, shown: List<Float>, onBand: (Int, Float) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.primary.copy(alpha = if (eq.enabled) 1f else 0.45f)
    val grid = scheme.onSurface.copy(alpha = 0.08f)
    val label = scheme.onSurfaceVariant.copy(alpha = 0.8f)
    val measurer = rememberTextMeasurer()
    val stages = remember(shown, eq.bassDb, eq.trebleDb, eq.headphone) {
        DspParams(equalizer = eq.copy(enabled = true, bands = shown, balance = 0f)).stages(48_000).map { it() }
    }
    val haptics = rememberHaptics()
    val change by rememberUpdatedState(onBand)
    var held by remember { mutableIntStateOf(-1) }
    val range = MAX_DB * 1.25f
    fun dbAt(y: Float, h: Float) = ((0.5f - y / h) * 2 * range).coerceIn(-MAX_DB, MAX_DB).let { (it * 2).roundToInt() / 2f }
    fun bandAt(x: Float, w: Float) = EQ_BANDS_HZ.indices.minBy { abs(xOf(EQ_BANDS_HZ[it].toDouble(), w) - x) }
    Canvas(
        modifier
            .pointerInput(eq.enabled) {
                detectTapGestures(onDoubleTap = { p -> change(bandAt(p.x, size.width.toFloat()), 0f) })
            }
            .pointerInput(eq.enabled) {
                detectDragGestures(
                    onDragStart = { p -> held = bandAt(p.x, size.width.toFloat()); haptics.tick() },
                    onDragEnd = { held = -1 },
                    onDragCancel = { held = -1 },
                ) { c, _ ->
                    c.consume()
                    val band = bandAt(c.position.x, size.width.toFloat())
                    if (band != held) { held = band; haptics.tick() }
                    change(band, dbAt(c.position.y, size.height.toFloat()))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        fun yOf(db: Double) = (h / 2 - (db / range) * h / 2).toFloat().coerceIn(0f, h)
        // Grid: 0, ±6 and ±12 dB across; 100 Hz, 1 kHz and 10 kHz down.
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { db ->
            drawLine(if (db == 0.0) grid.copy(alpha = 0.2f) else grid, Offset(0f, yOf(db)), Offset(w, yOf(db)), 1.dp.toPx())
        }
        listOf(30.0 to "30", 100.0 to "100", 300.0 to "300", 1_000.0 to "1k", 3_000.0 to "3k", 10_000.0 to "10k").forEach { (hz, text) ->
            val x = xOf(hz, w)
            drawLine(grid, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
            val laid = measurer.measure(text, androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = label))
            drawText(laid, topLeft = Offset(x + 3.dp.toPx(), h - laid.size.height))
        }
        // The curve, and the glow from it down to the 0 dB line.
        val path = Path()
        val steps = 160
        for (s in 0..steps) {
            val hz = 10.0.pow(log10(LOW_HZ) + (log10(HIGH_HZ) - log10(LOW_HZ)) * s / steps)
            val x = w * s / steps
            val y = yOf(responseAt(stages, eq.preampDb, hz))
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply { addPath(path); lineTo(w, h / 2); lineTo(0f, h / 2); close() }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.32f), line.copy(alpha = 0.02f), line.copy(alpha = 0.32f)), 0f, h))
        drawPath(path, line.copy(alpha = 0.25f), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        // A point on each band, larger on the one being dragged.
        EQ_BANDS_HZ.forEachIndexed { i, hz ->
            val x = xOf(hz.toDouble(), w)
            val y = yOf(responseAt(stages, eq.preampDb, hz.toDouble()))
            band(i == held, Offset(x, y), line, scheme.surfaceContainerHigh)
        }
    }
}

private fun DrawScope.band(held: Boolean, at: Offset, colour: Color, hole: Color) {
    if (held) drawCircle(colour.copy(alpha = 0.22f), 18.dp.toPx(), at)
    drawCircle(colour, (if (held) 8 else 5).dp.toPx(), at)
    drawCircle(hole, (if (held) 3.5f else 2f).dp.toPx(), at)
}

/** A preset as a card: its own curve, small, over its name and what it does. */
@Composable
private fun PresetCard(p: EqPreset, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "presetSelected")
    val stages = remember(p) { DspParams(equalizer = EqualizerSettings(enabled = true, bands = p.bands)).stages(48_000).map { it() } }
    Column(
        Modifier.width(150.dp).clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh)
            .border(BorderStroke((1 + glow).dp, scheme.primary.copy(alpha = 0.15f + 0.75f * glow)), RoundedCornerShape(20.dp))
            .clickable { haptics.tick(); onClick() }
            .padding(12.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            val w = size.width
            val h = size.height
            drawLine(scheme.onSurface.copy(alpha = 0.12f), Offset(0f, h / 2), Offset(w, h / 2), 1.dp.toPx())
            val path = Path()
            for (s in 0..60) {
                val hz = 10.0.pow(log10(LOW_HZ) + (log10(HIGH_HZ) - log10(LOW_HZ)) * s / 60)
                val y = (h / 2 - (responseAt(stages, 0f, hz) / 8.0) * h / 2).toFloat().coerceIn(0f, h)
                if (s == 0) path.moveTo(w * s / 60, y) else path.lineTo(w * s / 60, y)
            }
            drawPath(path, scheme.primary.copy(alpha = 0.55f + 0.45f * glow), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(8.dp))
        Text(p.label, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1)
        Text(p.summary, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(48.dp))
    }
}

/**
 * A slim vertical fader with a glowing fill from 0 dB: drag or tap to set,
 * double-tap to reset. It holds at 0 dB for a moment on the way through,
 * with a tick, so flat is easy to find.
 */
@Composable
private fun Fader(value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
    val thumb = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
    val haptics = rememberHaptics()
    val current by rememberUpdatedState(onChange)
    val now by rememberUpdatedState(value)
    fun valueAt(y: Float, height: Float): Float {
        val v = ((0.5f - y / height) * 2 * MAX_DB).coerceIn(-MAX_DB, MAX_DB)
        // A small detent at flat.
        return if (abs(v) < 0.6f) 0f else (v * 2).roundToInt() / 2f
    }
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
                    val v = valueAt(change.position.y, size.height.toFloat())
                    if (v == 0f && now != 0f) haptics.tick()
                    current(v)
                }
            },
    ) {
        val w = 6.dp.toPx()
        val x = (size.width - w) / 2
        drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
        val mid = size.height / 2
        drawLine(track.copy(alpha = 0.3f), Offset(x - 5.dp.toPx(), mid), Offset(x + w + 5.dp.toPx(), mid), 1.dp.toPx())
        val y = mid - value / MAX_DB * mid
        val top = minOf(mid, y)
        val height = abs(mid - y)
        if (height > 0.5f) {
            drawRoundRect(
                Brush.verticalGradient(if (y < mid) listOf(fill, fill.copy(alpha = 0.35f)) else listOf(fill.copy(alpha = 0.35f), fill), top, top + height),
                Offset(x, top), Size(w, height), CornerRadius(w / 2),
            )
        }
        drawCircle(fill.copy(alpha = 0.18f), 14.dp.toPx(), Offset(size.width / 2, y))
        drawCircle(thumb, 8.dp.toPx(), Offset(size.width / 2, y))
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
