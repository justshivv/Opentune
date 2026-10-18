package com.opentune.ui.lossless

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentune.BuildConfig
import com.opentune.data.lossless.*
import com.opentune.ui.components.FloatingDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DownloadDetailsDialog(entry: LosslessEntry, network: DownloadNetworkState, worker: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pkg = ProviderPackages.get(entry.track.provider)
    val source by com.opentune.data.NerdStats.playbackSource.collectAsState()
    val decoder by com.opentune.data.NerdStats.audioDecoder.collectAsState()
    val underruns by com.opentune.data.NerdStats.audioUnderruns.collectAsState()
    val sound by com.opentune.data.settings.AppSettings.sound.collectAsState()
    val playback by com.opentune.data.settings.AppSettings.playback.collectAsState()
    val equalizer by com.opentune.data.settings.AppSettings.equalizer.collectAsState()
    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    val report = buildString {
        appendLine("OpenTune ${BuildConfig.VERSION_NAME}")
        appendLine("Android API ${android.os.Build.VERSION.SDK_INT} · ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("${entry.track.title} · ${entry.track.artist}")
        appendLine("Provider: ${ProviderPackages.displayName(entry.track.provider)} ${pkg?.version ?: "not installed"}")
        pkg?.let { appendLine("Package SHA-256: ${it.listing.sha256}") }
        appendLine("Quality: ${entry.quality}")
        if (entry.state == LosslessState.DONE) appendLine("Saved FLAC: ${entry.bits}-bit / ${entry.sampleRate} Hz")
        if (source?.mediaId == entry.track.song().videoId) {
            appendLine("Current decoder: ${decoder ?: "starting"}")
            appendLine("Current track buffer underruns: $underruns")
            appendLine("Playback: speed ${sound.speed}, pitch ${sound.pitch}, float output ${playback.floatOutput}")
            appendLine("Effects: EQ ${equalizer.enabled}, bass ${sound.bassBoost}, clarity ${playback.clarity}, widening ${playback.spatialAudio}, 8D ${sound.eightD}, 3D ${sound.space}, reverb ${sound.reverb}, system effects ${playback.systemEffects}")
        }
        appendLine("Network: ${network.label}")
        appendLine("Policy: ${if (entry.unmeteredOnly) "unmetered only" else "Wi-Fi or mobile data"}")
        appendLine("Android worker: $worker")
        appendLine("State: ${entry.state.label}")
        appendLine("Bytes: ${entry.bytes} / ${entry.total.takeIf { it > 0 } ?: "unknown"}")
        entry.error?.let { appendLine(it) }
        appendLine()
        entry.events.forEach { appendLine("${time.format(Instant.ofEpochMilli(it.time))}  ${it.message}") }
        if (entry.events.isEmpty()) appendLine("No request events recorded yet.")
    }
    FloatingDialog(onDismissRequest = onDismiss, title = { Text("Download details") }, text = {
        SelectionContainer { Text(report, Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
    }, confirmButton = { TextButton(onClick = {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OpenTune download details", report))
    }) { Text("Copy diagnostics") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
