package com.opentune.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.NerdStats
import com.opentune.playback.AudioFormatInfo
import java.util.Locale

@Composable
internal fun SourceQualityBadge(mediaId: String, format: AudioFormatInfo?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reported by NerdStats.playbackSource.collectAsState()
    val upgrading by NerdStats.upgrading.collectAsState()
    val source = reported?.takeIf { it.mediaId == mediaId }
    val label = qualityStatusLabel(source, if (source != null) format else null, upgrading == mediaId)
    val colors = MaterialTheme.colorScheme
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    // A thin light line runs across the label, but only for lossless and Hi-Res
    // lossless: lossy streams keep a plain grey label.
    val lossless = isHighestQuality(source, format)
    val phase = if (ui.reduceAnimation || !lossless) null else rememberInfiniteTransition(label = "quality light")
        .animateFloat(-0.15f, 1.15f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "quality sweep")
    TextButton(onClick = onClick, modifier = modifier.widthIn(max = 280.dp).fillMaxWidth().heightIn(min = 40.dp).semantics { contentDescription = "$label. Open signal path" },
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
        // Lossless gets the waveform; a lossy stream the plain HQ mark (a headphone read as the output, not the quality).
        Icon(if (lossless) androidx.compose.material.icons.Icons.Rounded.GraphicEq else androidx.compose.material.icons.Icons.Rounded.HighQuality,
            null, Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithCache {
                onDrawWithContent {
                    drawContent()
                    phase?.value?.let { progress ->
                        val x = progress * size.width
                        val half = 9.dp.toPx()
                        // A narrow upright line of light, swept over the grey text only.
                        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to colors.onSurface.copy(alpha = 0f),
                            0.5f to colors.onSurface.copy(alpha = 0.85f),
                            1f to colors.onSurface.copy(alpha = 0f),
                            startX = x - half,
                            endX = x + half),
                            blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                    }
                }
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun qualityStatusLabel(source: NerdStats.Source?, format: AudioFormatInfo?, upgrading: Boolean): String {
    if (source == null || source.provider.startsWith("Checking highest")) return if (upgrading) "Upgrading quality" else "Preparing audio"
    val lossless = isHighestQuality(source, format)
    val tier = when {
        lossless && ((format?.bitDepth ?: 0) > 16 || (format?.sampleRateHz ?: 0) > 48000) -> "Hi-Res Lossless"
        lossless -> "Lossless"
        (format?.bitrateKbps ?: source.kbps ?: 0) >= 128 -> "High quality"
        else -> "Standard quality"
    }
    // The concrete signal behind the tier: bit depth and rate for lossless,
    // the stream's bitrate otherwise. So "Lossless" reads "Lossless · 24-bit/96 kHz".
    val detail = if (lossless) {
        val bits = format?.bitDepth ?: source.bits
        val rate = format?.sampleRateHz ?: source.sampleRate
        listOfNotNull(bits?.let { "$it-bit" }, rate?.let { khzLabel(it) }).joinToString("/").ifEmpty { null }
    } else {
        (format?.bitrateKbps ?: source.kbps)?.takeIf { it > 0 }?.let { "$it kbps" }
    }
    val label = listOfNotNull(tier, detail).joinToString(" · ")
    return if (upgrading) "$label · upgrading" else label
}

/** A sample rate in Hz as "44.1 kHz" or "96 kHz", trimming a trailing ".0". */
private fun khzLabel(hz: Int): String =
    "${String.format(Locale.ROOT, "%.1f", hz / 1000.0).removeSuffix(".0")} kHz"

/** Whether [source] is the best there is: lossless, rather than a lossy stream at any bitrate. */
internal fun isHighestQuality(source: NerdStats.Source?, format: AudioFormatInfo?): Boolean {
    if (source == null || source.provider.startsWith("Checking highest")) return false
    // Some streams (an add-on's DASH or HLS FLAC) name their codec in a way that
    // doesn't say FLAC; no lossy stream runs anywhere near this bitrate, though.
    if ((format?.bitrateKbps ?: 0) >= LOSSLESS_MIN_KBPS) return true
    val codec = format?.codec ?: return false
    return LOSSLESS_CODECS.any { codec.contains(it, ignoreCase = true) }
}

/** Above any lossy codec's top rate (AAC and Opus stop near 512 kbps for stereo). */
private const val LOSSLESS_MIN_KBPS = 700

private val LOSSLESS_CODECS = listOf("FLAC", "ALAC", "WAV", "AIFF", "DSD", "WavPack", "APE")

/**
 * A glowing beam that runs round a pill's border at an even pace: a soft
 * halo in [color], blurred like real light, a bright core that fades into a
 * long tail, and a faint lit rim all the way round. With [still], just the
 * lit rim.
 */
internal fun Modifier.lightBeam(on: Boolean, still: Boolean = false, color: Color = Color.White): Modifier = if (!on) this else composed {
    val phase = if (still) null else rememberInfiniteTransition(label = "beam")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "beamPhase")
    drawWithCache {
        val inset = 0.75.dp.toPx()
        val outline = Path().apply {
            addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(size.height / 2 - inset)))
        }
        val measure = PathMeasure().apply { setPath(outline, false) }
        val total = measure.length
        val beam = Path()
        val tail = total * 0.32f
        val argb = color.toArgb()
        val clear = color.copy(alpha = 0f).toArgb()
        val white = android.graphics.Color.WHITE
        fun stroke(width: Float, blur: Float = 0f) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = width
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            if (blur > 0f) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val rim = stroke(1.dp.toPx()).apply { this.color = color.copy(alpha = 0.28f).toArgb() }
        val halo = stroke(7.dp.toPx(), blur = 6.dp.toPx())
        val haze = stroke(3.dp.toPx(), blur = 2.dp.toPx())
        val core = stroke(1.6.dp.toPx())
        val spark = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = white
            maskFilter = android.graphics.BlurMaskFilter(3.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val native = outline.asAndroidPath()
        onDrawWithContent {
            drawContent()
            drawIntoCanvas { canvas ->
                val c = canvas.nativeCanvas
                c.drawPath(native, rim)
                val p = phase?.value ?: return@drawIntoCanvas
                // The beam: from its tail to its head, one piece of the outline
                // (two when it runs across the outline's start).
                val head = p * total
                val from = head - tail
                beam.reset()
                if (from >= 0f) measure.getSegment(from, head, beam, true)
                else { measure.getSegment(from + total, total, beam, true); measure.getSegment(0f, head, beam, true) }
                val t = measure.getPosition(((from % total) + total) % total)
                val h = measure.getPosition(head.coerceAtMost(total - 0.01f))
                // Light grows from nothing at the tail to full at the head.
                fun shade(start: Int, end: Int) = android.graphics.LinearGradient(t.x, t.y, h.x, h.y, start, end, android.graphics.Shader.TileMode.CLAMP)
                halo.shader = shade(clear, argb)
                haze.shader = shade(clear, argb)
                core.shader = android.graphics.LinearGradient(t.x, t.y, h.x, h.y, intArrayOf(clear, argb, white), floatArrayOf(0f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
                val path = beam.asAndroidPath()
                c.drawPath(path, halo)
                c.drawPath(path, haze)
                c.drawPath(path, core)
                c.drawCircle(h.x, h.y, 2.4.dp.toPx(), spark)
            }
        }
    }
}

internal fun sourceQualityLabel(source: NerdStats.Source?, format: AudioFormatInfo?): String {
    if (source == null) return "Resolving audio…"
    val provider = source.provider.removePrefix("YouTube · ")
    if (source.provider.startsWith("Checking highest")) return "Checking highest quality…"
    val codec = format?.codec
    val bits = format?.bitDepth ?: source.bits
    val rate = format?.sampleRateHz ?: source.sampleRate
    val quality = if (codec?.contains("FLAC", ignoreCase = true) == true) {
        listOfNotNull("FLAC", bits?.let { "$it-bit" }, rate?.let { khzLabel(it) }).joinToString(" · ")
    } else listOfNotNull(codec, (format?.bitrateKbps ?: source.kbps)?.let { "$it kbps" }).joinToString(" · ")
    return "$provider · ${quality.ifEmpty { "quality pending" }}"
}

/** Jumps to the next chorus; shown beside the source pill while one is still to come. */
@Composable
internal fun SkipToChorus(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = colors.surfaceContainerHigh.copy(alpha = 0.9f),
            contentColor = colors.onSurface,
        ),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Rounded.FastForward, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Skip to chorus", style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
