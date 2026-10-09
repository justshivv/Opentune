package com.opentune.ui.player

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.NerdStats
import com.opentune.playback.AudioFormatInfo
import java.util.Locale

@Composable
internal fun SourceQualityBadge(mediaId: String, format: AudioFormatInfo?, onClick: () -> Unit) {
    val reported by NerdStats.playbackSource.collectAsState()
    // Never show the previous song's provider while a new item is resolving.
    val source = reported?.takeIf { it.mediaId == mediaId }
    val label = sourceQualityLabel(source, if (source != null) format else null)
    val colors = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp).semantics {
            contentDescription = "$label. Open signal path"
        },
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = colors.surfaceContainerHigh.copy(alpha = 0.9f),
            contentColor = colors.onSurface,
        ),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun sourceQualityLabel(source: NerdStats.Source?, format: AudioFormatInfo?): String {
    if (source == null) return "Resolving audio…"
    val provider = source.provider.removePrefix("YouTube · ")
    if (source.provider.startsWith("Checking highest")) return "Checking highest quality…"
    val codec = format?.codec ?: if (source.bits != null) "FLAC" else null
    val bits = source.bits ?: format?.bitDepth
    val rate = source.sampleRate ?: format?.sampleRateHz
    val quality = if (codec?.contains("FLAC", ignoreCase = true) == true) {
        listOfNotNull("FLAC", bits?.let { "$it-bit" }, rate?.let { "${String.format(Locale.ROOT, "%.1f", it / 1000.0).removeSuffix(".0")} kHz" }).joinToString(" · ")
    } else listOfNotNull(codec, (format?.bitrateKbps ?: source.kbps)?.let { "$it kbps" }).joinToString(" · ")
    return "$provider · ${quality.ifEmpty { "quality pending" }}"
}
