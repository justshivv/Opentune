package com.opentune.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.model.artworkAt
import com.opentune.playback.MixState
import com.opentune.playback.mix.Wave
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingCard
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val GainLine = Color(0xFFF5C542)

/**
 * How the next two songs meet: the end of the playing song over the start of
 * the next, on one time line, with the stretch where they blend boxed, its
 * beats marked, and each song's level through it drawn as a curve.
 */
@Composable
internal fun AutomixSheet(onDismiss: () -> Unit) {
    val picture by MixState.upcoming.collectAsState()
    FloatingCard(onDismiss = onDismiss, title = if (picture?.automix == false) "Crossfade" else "Automix") {
        val p = picture
        if (p == null) {
            Text(
                "Automix reads the end of this song and the start of the next shortly before they meet. Open this again then.",
                Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@FloatingCard
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SongLine(p.out)
            // Fixed colours, so the two songs never come out alike whatever the theme.
            val outColor = Color(0xFFF0559B)
            val inColor = Color(0xFF7B7BFF)
            val frame = Color(0xFF41A8F2)
            val window = MaterialTheme.colorScheme.surfaceContainerHighest
            Canvas(Modifier.fillMaxWidth().height(280.dp).padding(vertical = 10.dp)) {
                drawTransition(p, outColor, inColor, frame, window)
            }
            val bars = p.plan.beatMs?.let { (p.plan.blendMs / (it * 4)).roundToInt().coerceAtLeast(1) }
                ?: p.out.bpm?.let { (p.plan.blendMs / (60_000.0 / it * 4)).roundToInt().coerceAtLeast(1) }
            Text(
                bars?.let { "$it ${if (it == 1) "Bar" else "Bars"}" } ?: "${(p.plan.blendMs / 1000.0).let { "%.1f".format(it).removeSuffix(".0") }} s",
                Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Box(Modifier.height(12.dp))
            SongLine(p.into)
            Text(
                listOfNotNull(
                    "Blends over ${"%.1f".format(p.plan.blendMs / 1000.0)} s",
                    if (p.plan.outSpeed != 1f) "tempo matched (${"%+.1f".format((p.plan.outSpeed - 1f) * 100)}%)" else null,
                    if (p.plan.beatMs != null) "kept on the beat" else null,
                    if (p.plan.bassSwap) "the bass traded over" else null,
                    if (p.plan.inFromMs > 300) "next song's silent start skipped" else null,
                ).joinToString(", "),
                Modifier.padding(top = 10.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SongLine(side: MixState.Side) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(side.artworkUrl.artworkAt(com.opentune.data.model.ROW_ART_PX), Modifier.size(56.dp), RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f)) {
            Text(side.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(side.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            side.bpm?.let { Text("${it.roundToInt()} bpm", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The key on the Camelot wheel, in its colour there, as DJs mark it.
                side.key?.let { key ->
                    Text(key, Modifier.clip(RoundedCornerShape(6.dp)).background(camelotColor(key)).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF1B1B24))
                }
                side.durationMs?.takeIf { it > 0 }?.let {
                    val s = it / 1000
                    Text("%02d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun DrawScope.drawTransition(p: MixState.Picture, outColor: Color, inColor: Color, frame: Color, window: Color) {
    val plan = p.plan
    val blend = plan.blendMs.coerceAtLeast(1_000)
    // On the playing song's clock: a little before the blend, to a little after its end.
    val from = plan.mixAtMs - (blend * 0.8).toLong()
    val to = maxOf(plan.outEndMs, plan.mixAtMs + blend) + (blend * 0.35).toLong()
    fun x(t: Long) = ((t - from).toFloat() / (to - from)) * size.width
    // The next song's moment that sounds at the playing song's [t].
    fun inTime(t: Long) = t - plan.mixAtMs + plan.inFromMs

    val laneH = size.height / 2f - 6.dp.toPx()
    val outMid = laneH / 2f
    val inMid = size.height - laneH / 2f
    val x0 = x(plan.mixAtMs)
    val x1 = x(plan.mixAtMs + blend)

    // The blend's stretch, behind both songs.
    drawRoundRect(window, Offset(x0 - 6.dp.toPx(), 0f), Size(x1 - x0 + 12.dp.toPx(), size.height), CornerRadius(18.dp.toPx()))
    wave(p.out.wave, outColor, outMid, laneH, x0, x1, from, to) { it }
    wave(p.into.wave, inColor, inMid, laneH, x0, x1, from, to) { inTime(it) }

    // Beats through the blend.
    val beat = plan.beatMs ?: p.out.bpm?.let { 60_000.0 / it }
    if (beat != null) {
        var t = plan.outBeatMs.toDouble()
        while (t < plan.mixAtMs + blend) {
            val bx = x(t.toLong())
            if (bx in x0..x1) {
                drawLine(Color.White.copy(alpha = 0.18f), Offset(bx, 0f), Offset(bx, laneH), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.18f), Offset(bx, size.height - laneH), Offset(bx, size.height), 1.dp.toPx())
            }
            t += beat
        }
    }
    // Each lane's part of the blend, framed.
    drawRect(frame, Offset(x0, 0f), Size(x1 - x0, laneH), style = Stroke(2.dp.toPx()))
    drawRect(frame, Offset(x0, size.height - laneH), Size(x1 - x0, laneH), style = Stroke(2.dp.toPx()))

    // The levels: the playing song falling away, the next one rising, on equal-power curves.
    fun curve(top: Float, rising: Boolean): Path = Path().apply {
        val steps = 48
        for (i in 0..steps) {
            val q = i / steps.toFloat()
            val g = if (rising) sin(q * PI / 2).toFloat() else cos(q * PI / 2).toFloat()
            val cx = x0 + (x1 - x0) * q
            val cy = top + laneH * (1f - g * 0.5f) - laneH * 0.02f
            if (i == 0) moveTo(cx, cy) else lineTo(cx, cy)
        }
    }
    drawPath(curve(0f, rising = false), GainLine, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    drawPath(curve(size.height - laneH, rising = true), GainLine, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
}

/** A song's loudness as mirrored bars along its lane; brighter inside the blend, faint where there's no sound. */
private fun DrawScope.wave(
    wave: Wave?, color: Color, mid: Float, laneH: Float, x0: Float, x1: Float, from: Long, to: Long, songTime: (Long) -> Long,
) {
    val step = 3.dp.toPx()
    var x = 0f
    while (x < size.width) {
        val t = songTime(from + ((x / size.width) * (to - from)).toLong())
        val v = wave?.let { w ->
            if (t < w.startMs || t >= w.endMs) null
            else w.values[(((t - w.startMs).toFloat() / (w.endMs - w.startMs)) * w.values.size).toInt().coerceIn(0, w.values.size - 1)]
        } ?: 0.04f
        val h = (laneH * 0.92f) * v.coerceAtLeast(0.02f)
        val inside = x in x0..x1
        drawLine(
            if (inside) color else color.copy(alpha = 0.55f),
            Offset(x, mid - h / 2), Offset(x, mid + h / 2), step * 0.62f, cap = StrokeCap.Round,
        )
        x += step
    }
}

/** The Camelot wheel's colour for a key: hue round the wheel by its number, lighter for majors. */
private fun camelotColor(key: String): Color {
    val n = key.dropLast(1).toIntOrNull() ?: 1
    val major = key.endsWith("B")
    val hue = ((n - 1) * 30f + 150f) % 360f
    return Color.hsl(hue, 0.55f, if (major) 0.78f else 0.72f)
}
