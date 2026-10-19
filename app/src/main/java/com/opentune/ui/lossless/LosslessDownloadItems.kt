package com.opentune.ui.lossless

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.lossless.*
import com.opentune.data.settings.AppSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** Download cards are part of the ordinary Downloads page, including older saved files. */
@Composable
internal fun losslessDownloadItems(actions: SongActions, onProviders: () -> Unit): androidx.compose.foundation.lazy.LazyListScope.() -> Unit {
    val entries by LosslessDownloads.entries.collectAsState()
    val installed by ProviderPackages.installed.collectAsState()
    val network by DownloadNetwork.state.collectAsState()
    val workers by LosslessDownloads.workStates.collectAsState()
    val library by AppSettings.library.collectAsState()
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LosslessEntry?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/flac")) { uri ->
        val id = exportId; exportId = null
        if (uri != null && id != null) scope.launch {
            exporting = true
            try { LosslessDownloads.export(id, uri); notice = "FLAC exported." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "Export failed. Choose a writable folder with enough free space." }
            finally { exporting = false }
        }
    }
    fun enqueue(entry: LosslessEntry, mobile: Boolean = false) { scope.launch {
        try { LosslessDownloads.enqueue(entry.track, entry.quality, allowMetered = mobile, replaceQueued = mobile) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "Could not queue the download. Check free space and try again." }
    } }
    fun remove(entry: LosslessEntry) { scope.launch {
        try { LosslessDownloads.remove(entry.track.key) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "Could not remove this download. Try again." }
    } }
    val saved = entries.values.filter { it.state == LosslessState.DONE }.sortedByDescending { it.addedAt }
    val queue = entries.values.filter { it.state != LosslessState.DONE }.sortedBy { it.addedAt }
    details?.let { key -> entries[key]?.let { DownloadDetailsDialog(it, network, workers[it.workId]?.name ?: "Not scheduled", onDismiss = { details = null }) } }
    deleting?.let { e -> FloatingDialog(onDismissRequest = { deleting = null }, title = { Text("Remove saved FLAC?") }, text = { Text("Remove ${e.track.title} from OpenTune? Copies you exported stay in their folders.") },
        confirmButton = { TextButton(onClick = { deleting = null; remove(e) }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
    return {
        if (entries.isNotEmpty()) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Unmetered networks only"); Text(network.label, style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = library.downloadWifiOnly, onCheckedChange = { enabled -> AppSettings.updateLibrary { it.copy(downloadWifiOnly = enabled) } })
                }
            }
            notice?.let { message -> item { Text(message, Modifier.padding(20.dp)) } }
            if (queue.isNotEmpty()) item { Text("FLAC downloads", Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium) }
            items(queue, key = { "flac:${it.track.key}" }) { e ->
                LosslessCard(e, onRetry = { enqueue(e) }, onCancel = { remove(e) }, onConnect = onProviders,
                    waitingReason = if (e.state == LosslessState.QUEUED) DownloadNetwork.waitingReason(e, network, workers[e.workId] == androidx.work.WorkInfo.State.RUNNING) else null,
                    showMobileAction = e.state == LosslessState.QUEUED && e.unmeteredOnly && network.connected && network.metered,
                    onMobile = { enqueue(e, mobile = true) }, needsInstallation = installed[e.track.provider] == null, onDetails = { details = e.track.key })
                Spacer(Modifier.height(12.dp))
            }
            if (saved.isNotEmpty()) item { Text("Saved FLAC", Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium) }
            items(saved, key = { "flac:${it.track.key}" }) { e ->
                LosslessCard(e,
                    onPlay = { actions.playAll(saved.map(LosslessDownloads::song), saved.indexOf(e), false, "Downloads") },
                    onExport = { exportId = e.track.key; exporter.launch("${e.track.artist} - ${e.track.title}".replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(150) + ".flac") },
                    onRemove = { deleting = e }, exportEnabled = !exporting, onDetails = { details = e.track.key })
                Spacer(Modifier.height(12.dp))
            }
            if (exporting) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        }
    }
}
@Composable
private fun TrackHeading(track: LosslessTrack) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(track.artwork, Modifier.size(56.dp), RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium)
            Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            track.song().durationText?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LosslessCard(entry: LosslessEntry, onRetry: () -> Unit = {}, onCancel: () -> Unit = {}, onConnect: () -> Unit = {},
    onPlay: () -> Unit = {}, onExport: () -> Unit = {}, onRemove: () -> Unit = {}, exportEnabled: Boolean = true,
    waitingReason: String? = null, showMobileAction: Boolean = false, onMobile: () -> Unit = {}, needsInstallation: Boolean = false, onDetails: () -> Unit = {}) {
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TrackHeading(entry.track.copy(artwork = LosslessDownloads.artFor(entry.track.key)?.toURI()?.toString() ?: entry.track.artwork))
            Text(if (entry.state == LosslessState.DONE) "FLAC · ${entry.bits}-bit / ${entry.sampleRate / 1000.0} kHz · ${sizeLabel(entry.bytes)}" else waitingReason ?: entry.state.label,
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            if (entry.state == LosslessState.DOWNLOADING) {
                if (entry.total > 0) LinearProgressIndicator(progress = { (entry.bytes.toFloat() / entry.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (entry.total > 0) "${sizeLabel(entry.bytes)} / ${sizeLabel(entry.total)}" else sizeLabel(entry.bytes), style = MaterialTheme.typography.bodySmall)
            } else if (entry.state in setOf(LosslessState.REQUESTING, LosslessState.PREPARING, LosslessState.VERIFYING)) LinearProgressIndicator(Modifier.fillMaxWidth())
            entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (entry.state) {
                    LosslessState.DONE -> { Button(onClick = onPlay) { Text("Play") }; OutlinedButton(onClick = onExport, enabled = exportEnabled) { Text("Export") }; TextButton(onClick = onRemove) { Text("Remove") } }
                    LosslessState.WAITING -> { Button(onClick = onConnect) { Text(if (needsInstallation) "Install provider" else "Verify provider") }; TextButton(onClick = onRetry) { Text("Resume") }; TextButton(onClick = onCancel) { Text("Cancel") } }
                    LosslessState.FAILED -> { Button(onClick = onRetry) { Text("Retry") }; TextButton(onClick = onCancel) { Text("Remove") } }
                    else -> TextButton(onClick = onCancel) { Text("Cancel download") }
                }
                if (showMobileAction) OutlinedButton(onClick = onMobile) { Text("Use mobile data") }
                TextButton(onClick = onDetails) { Text("Details") }
            }
        }
    }
}
private fun sizeLabel(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
