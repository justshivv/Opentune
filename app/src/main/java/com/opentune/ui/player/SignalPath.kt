package com.opentune.ui.player

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentune.data.NerdStats
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioFormatInfo
import com.opentune.playback.BitPerfectUsb
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton

/**
 * Every stage the sound passes through, from the stream to the speaker, with
 * what each one is doing right now. Green is untouched, amber is changing
 * the sound on purpose (a setting you chose), grey is information.
 */
@Composable
fun SignalPathDialog(format: AudioFormatInfo?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val eq by AppSettings.equalizer.collectAsState()
    val sound by AppSettings.sound.collectAsState()
    val pb by AppSettings.playback.collectAsState()
    val source by NerdStats.playbackSource.collectAsState()
    val lossless by NerdStats.externalSource.collectAsState()
    val gain by NerdStats.loudnessGainDb.collectAsState()
    val speakerHold by NerdStats.loudnessOffOnSpeaker.collectAsState()
    val device = rememberOutputDeviceName()
    val bitPerfect by BitPerfectUsb.status.collectAsState()
    val mixerRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    val volume = am?.getStreamVolume(AudioManager.STREAM_MUSIC)
    val maxVolume = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val dspStages = buildList {
        if (eq.enabled) add("equalizer")
        if (sound.bassBoost > 0) add("bass boost")
        if (pb.spatialAudio) add("stereo widening")
        if (sound.space != com.opentune.data.settings.Space3DSpeed.OFF) add("3D sound")
        else if (sound.eightD != com.opentune.data.settings.EightD.OFF) add("8D")
        if (pb.clarity) add("clarity")
        if (eq.balance != 0f) add("balance")
    }
    val rate = format?.sampleRateHz ?: source?.sampleRate

    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signal path") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stage(Tone.INFO, "Source", source?.provider ?: "Waiting for the stream")
                Stage(Tone.INFO, "Audio quality", signalQuality(format, source))
                Stage(
                    if (dspStages.isEmpty()) Tone.CLEAN else Tone.CHANGED,
                    "App DSP",
                    if (dspStages.isEmpty()) "Bypassed: samples pass through unchanged" else dspStages.joinToString(", ") + ", with headroom so boosts don't clip",
                )
                Stage(
                    if (sound.speed == 1f && sound.pitch == 1f) Tone.CLEAN else Tone.CHANGED,
                    "Tempo",
                    if (sound.speed == 1f && sound.pitch == 1f) "1.00x, no time-stretching" else "%.2fx speed, %.2fx pitch".format(sound.speed, sound.pitch),
                )
                Stage(
                    if (gain == null) Tone.CLEAN else Tone.CHANGED,
                    "Loudness",
                    when {
                        !pb.loudnessNormalization -> "Off: every song at its own level"
                        lossless != null -> "No loudness measurement for this external master"
                        speakerHold && (gain ?: 0f) > 0.05f -> "%+.1f dB on the phone speaker: a quiet song lifted, never turned down".format(gain)
                        speakerHold -> "Full level on the phone speaker: songs aren't turned down there"
                        gain == null -> "No figure for this song yet"
                        else -> "%+.1f dB to YouTube's reference level".format(gain)
                    },
                )
                Stage(
                    if (pb.systemEffects) Tone.CHANGED else Tone.CLEAN,
                    "System effects",
                    if (pb.systemEffects) "Open: the phone's effects may process the sound" else "Closed: no device effects",
                )
                Stage(
                    if (rate != null && mixerRate != null && rate != mixerRate) Tone.CHANGED else Tone.CLEAN,
                    "Android mixer",
                    when {
                        mixerRate == null -> "Rate unknown"
                        rate != null && rate != mixerRate -> "$mixerRate Hz: Android resamples from $rate Hz"
                        else -> "$mixerRate Hz, no resampling"
                    } + if (pb.floatOutput) " · 32-bit float" else " · 16-bit",
                )
                when (val bp = bitPerfect) {
                    is BitPerfectUsb.Status.Active -> Stage(Tone.CLEAN, "Bit-perfect", "On: ${bp.device} gets ${bp.sampleRate} Hz ${bp.bits}-bit untouched by Android")
                    is BitPerfectUsb.Status.NoMatch -> Stage(Tone.CHANGED, "Bit-perfect", "The DAC doesn't offer ${bp.sampleRate} Hz bit-perfect; using the normal mixer")
                    BitPerfectUsb.Status.NoDac -> Stage(Tone.INFO, "Bit-perfect", "Waiting for a USB DAC")
                    BitPerfectUsb.Status.Unsupported -> Stage(Tone.INFO, "Bit-perfect", "Needs Android 14 or newer")
                    BitPerfectUsb.Status.Off -> Unit
                }
                Stage(
                    if (bitPerfect is BitPerfectUsb.Status.Active && volume != maxVolume) Tone.CHANGED else Tone.INFO,
                    "System volume",
                    (if (volume != null && maxVolume != null) "$volume of $maxVolume" else "Unknown") +
                        if (bitPerfect is BitPerfectUsb.Status.Active) " · applied by the app; full volume is bit-exact" else "",
                )
                Stage(Tone.INFO, "Output", device)
            }
        },
        confirmButton = { SheetButton("Close", onClick = onDismiss, closes = true) },
    )
}

private enum class Tone(val color: Color) {
    CLEAN(Color(0xFF4CAF50)),
    CHANGED(Color(0xFFFFB300)),
    INFO(Color(0xFF9E9E9E)),
}

/** Reports the selected media format; output mixer precision is shown separately. */
internal fun signalQuality(format: AudioFormatInfo?, source: NerdStats.Source?): String {
    val codec = format?.codec ?: if (source?.bits != null) "FLAC" else null
    val bits = source?.bits ?: format?.bitDepth
    val rate = format?.sampleRateHz ?: source?.sampleRate
    val kbps = format?.bitrateKbps ?: source?.kbps
    return listOfNotNull(
        codec,
        bits?.let { "$it-bit" },
        rate?.let { "$it Hz" },
        format?.channels?.let { if (it == 2) "stereo" else "$it ch" },
        kbps?.let { "$it kbps" },
        if (codec?.contains("FLAC", true) == true) "lossless" else null,
        if (codec != null && kbps == null) "bitrate not reported" else null,
    ).joinToString(" · ").ifEmpty { "Waiting for the actual stream format" }
}

@Composable
private fun Stage(tone: Tone, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(tone.color, CircleShape))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
