package com.opentune.ui.player

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
    TextButton(onClick, Modifier.heightIn(min = 40.dp).semantics {
        contentDescription = "$label. Open signal path"
    }) {
        Text(label, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.labelMedium,
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
