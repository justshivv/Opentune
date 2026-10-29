package com.opentune.ui.player

import android.content.ClipData
import android.media.AudioManager
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.NerdStats
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioFormatInfo
import com.opentune.playback.BitPerfectUsb
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The audio details as cards: the format the song is in, where it's going and
 * how (exclusive and bit-perfect, or through Android's mixer), and where it
 * came from. The pipeline view is a tap away at the foot.
 */
@Composable
internal fun FormatDetailsDialog(song: Song?, format: AudioFormatInfo?, onDismiss: () -> Unit, onPipeline: () -> Unit) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val source by NerdStats.playbackSource.collectAsState()
    val pipe by NerdStats.pipeline.collectAsState()
    val pb by AppSettings.playback.collectAsState()
    val bitPerfect by BitPerfectUsb.status.collectAsState()
    val device = rememberOutputDeviceName()
    val route = rememberOutputRoute()
    val mixerRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

    val lossless = isHighestQuality(source, format)
    val bits = format?.bitDepth ?: source?.bits
    val rate = format?.sampleRateHz ?: source?.sampleRate
    val kbps = format?.bitrateKbps ?: source?.kbps
    val channels = format?.channels
    val trackRate = pipe.outputSampleRateHz ?: rate
    val quality = when {
        lossless && (bits ?: 0) >= 24 -> "HI-RES LOSSLESS"
        lossless -> "LOSSLESS"
        (kbps ?: 0) >= 256 -> "HIGH QUALITY"
        format == null -> "WAITING FOR THE STREAM"
        else -> "STANDARD QUALITY"
    }
    // A song from this phone's music: its size and where it lives.
    val local = song?.videoId?.takeIf { it.startsWith(LocalMusic.PREFIX) }?.let(LocalMusic::contentUri)
    val size by produceState<Long?>(null, local) {
        value = local?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                }.getOrNull()
            }
        }
    }

    FloatingCard(
        onDismiss = onDismiss,
        expandable = true,
        startExpanded = true,
        header = {
            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Audio Format Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(quality, style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.8.sp), fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        actions = {
            Box(Modifier.weight(1f)) { SheetButton("Pipeline view", onClick = onPipeline, tone = SheetTone.Neutral, icon = Icons.Rounded.Timeline) }
            Box(Modifier.weight(1f)) { SheetButton("Close", onClick = onDismiss, tone = SheetTone.Primary, closes = true) }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (song != null) DetailsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            DetailsCard("Audio specifications") {
                DetailRow(Icons.Rounded.AudioFile, "Codec / Format", format?.codec ?: "—")
                DetailRow(Icons.Rounded.GraphicEq, "Resolution", listOfNotNull(bits?.takeIf { lossless }?.let { "$it-bit" }, rate?.let(::kiloHertz)).joinToString(" • ").ifEmpty { "—" })
                DetailRow(Icons.Rounded.Speed, "Bitrate", kbps?.takeIf { it > 0 }?.let { "$it kbps" } ?: "—")
                DetailRow(Icons.Rounded.CheckCircle, "Channels", when (channels) {
                    null -> "—"
                    1 -> "Mono (1 Channel)"
                    2 -> "Stereo (2 Channels)"
                    else -> "$channels Channels"
                })
            }
            DetailsCard("Output & routing") {
                DetailRow(if (route == OutputRoute.PHONE) Icons.Rounded.VolumeUp else route.icon, "Active Device", device)
                DetailRow(Icons.Rounded.Speed, "Hi-Fi Mode", when (val bp = bitPerfect) {
                    is BitPerfectUsb.Status.Active -> "Exclusive USB · ${bp.format.ifEmpty { "${bp.bits}-bit" }}"
                    is BitPerfectUsb.Status.NoMatch -> "On · DAC has no bit-perfect ${kiloHertz(bp.sampleRate)}"
                    BitPerfectUsb.Status.NoDac -> "On · waiting for a USB DAC"
                    BitPerfectUsb.Status.Unsupported -> "Needs Android 14"
                    BitPerfectUsb.Status.Off -> if (BitPerfectUsb.supported && route == OutputRoute.USB) "Supported on device · off" else "Off"
                })
                DetailRow(Icons.Rounded.Info, "Signal Path", when {
                    bitPerfect is BitPerfectUsb.Status.Active -> "Direct bit-perfect (${kiloHertz((bitPerfect as BitPerfectUsb.Status.Active).sampleRate)})"
                    trackRate != null && mixerRate != null && trackRate != mixerRate -> "Resampled by Android to ${kiloHertz(mixerRate)}"
                    trackRate != null -> "Native rate (${kiloHertz(trackRate)})"
                    else -> "—"
                })
                DetailRow(Icons.Rounded.GraphicEq, "Output Format", pipe.outputEncoding ?: if (pb.floatOutput) "Float32" else "16-bit PCM")
            }
            DetailsCard("Source & storage") {
                DetailRow(Icons.Rounded.Info, "Provider", when {
                    local != null -> "Local Storage"
                    else -> source?.provider?.removePrefix("YouTube · ") ?: "—"
                })
                size?.let { DetailRow(Icons.Rounded.Storage, "File Size", String.format(Locale.ROOT, "%.1f MB", it / 1_048_576.0)) }
                local?.let { uri -> LocationRow(uri.toString()) }
            }
        }
    }
}

/** A rounded card with a small spaced-out heading, as the screenshot lays them out. */
@Composable
private fun DetailsCard(title: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (title != null) Text(title.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        // The label keeps its width; the value takes the rest and wraps only when it must.
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, Modifier.padding(start = 16.dp).weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

/** Where a file lives, with a button that copies it. */
@Composable
private fun LocationRow(location: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Location", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(location, style = MaterialTheme.typography.bodyMedium)
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Copy location") {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(location))
                com.opentune.data.Pills.done("Location copied", context)
            },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ContentCopy, "Copy location", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
    }
}

private fun kiloHertz(hz: Int): String = "${String.format(Locale.ROOT, "%.1f", hz / 1000.0).removeSuffix(".0")} kHz"
