package com.opentune.ui.lossless

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentune.data.download.Downloads
import com.opentune.data.lossless.*
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DownloadQualitySheet(songs: List<Song>, onDismiss: () -> Unit, onProviders: () -> Unit, onDownloads: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by ProviderPackages.installed.collectAsState()
    val entries by LosslessDownloads.entries.collectAsState()
    val library by AppSettings.library.collectAsState()
    var provider by remember { mutableStateOf<String?>(null) }
    val pkg = installed[provider]
    var quality by remember(provider, pkg?.version) { mutableStateOf(pkg?.qualities?.firstOrNull()?.first.orEmpty()) }
    var standard by remember { mutableStateOf(library.downloadQuality) }
    var matches by remember { mutableStateOf<List<LosslessTrack>>(emptyList()) }
    var searched by remember { mutableIntStateOf(0) }
    var missed by remember { mutableIntStateOf(0) }
    var errors by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var queueing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val status by ExtensionSessions.forProvider(pkg?.id ?: "amazon").status.collectAsState()
    val wanted = remember(songs) { songs.distinctBy { it.videoId } }
    LaunchedEffect(provider, pkg?.version, status, attempt, wanted) {
        matches = emptyList(); searched = 0; missed = 0; errors = 0; message = null
        busy = false
        val selected = provider ?: return@LaunchedEffect
        if (pkg == null || status != "Connected") return@LaunchedEffect
        busy = true
        try {
            for (song in wanted) {
                try {
                    val found = withContext(Dispatchers.IO) {
                        matchingDownloads(song, ProviderRuntime.search(selected, "${song.title} ${song.artist}"))
                    }
                    // Multiple catalog editions with the same identity are acceptable;
                    // title, all credited artists and duration must still match.
                    found.firstOrNull()?.let { matches = (matches + it).groupBy { track -> track.key }.values.map { group -> group.first().copy(sourceIds = group.flatMap { track -> track.sourceIds }.toSet()) } } ?: run { missed++ }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { errors++ }
                searched++
            }
        } finally { busy = false }
    }
    val pending = matches.filter { entries[it.key]?.state.let { state -> state == null || state == LosslessState.FAILED } }
    ModalBottomSheet(onDismissRequest = { if (!queueing) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Download quality", style = MaterialTheme.typography.headlineSmall)
            Text(if (wanted.size == 1) wanted.first().title else "${wanted.size} songs", style = MaterialTheme.typography.titleMedium)
            if (wanted.size == 1) Text(wanted.first().artist, style = MaterialTheme.typography.bodyMedium)
            Text("Download from", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(provider == null, { if (!queueing) provider = null }, label = { Text("Standard") })
                installed.values.filter { it.qualities.isNotEmpty() }.forEach { candidate ->
                    FilterChip(provider == candidate.id, { if (!queueing) provider = candidate.id }, label = { Text(candidate.name) })
                }
            }
            if (provider == null) {
                Text("Compressed audio · smaller files. The available source determines the final bitrate.", style = MaterialTheme.typography.bodySmall)
                AudioQuality.entries.forEach { value ->
                    QualityOption(value.label, if (value == AudioQuality.MAX) "Best available compressed audio" else value.summary, standard == value, !queueing) { standard = value }
                }
            } else if (pkg == null) {
                Text("This provider is no longer installed. Open Download providers in Settings to install it again.")
            } else if (status != "Connected") {
                Text("${pkg.name} · $status")
                Text("Connect this provider in Settings to check its catalog and download qualities.")
            } else {
                Text("FLAC is lossless. Resolution depends on the recording and is checked after download.", style = MaterialTheme.typography.bodySmall)
                pkg.qualities.forEach { (id, title) ->
                    QualityOption(title, pkg.qualityDescription(id), quality == id, !queueing) { quality = id }
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking recordings · $searched of ${wanted.size}") }
                else {
                    Text(if (matches.isEmpty()) "Could not verify the same recording. A matching title, artist and duration are required." else "${matches.size} matching recording${if (matches.size == 1) "" else "s"} · ${matches.size - pending.size} already saved or queued")
                    if (matches.size > pending.size) Text("Existing copies stay unchanged. To change their quality, remove them from Downloads first.", style = MaterialTheme.typography.bodySmall)
                    if (missed > 0) Text("$missed recording(s) did not match and will be skipped.")
                    if (errors > 0) Text("$errors catalog request(s) failed. Retry or choose another provider.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }, enabled = !queueing) { Text("Check again") }
                }
            }
            TextButton(onClick = onProviders, enabled = !queueing) { Text("Download providers in Settings") }
            Text(if (library.downloadWifiOnly) "Downloads wait for Wi-Fi or another unmetered network." else "Downloads can use Wi-Fi or mobile data.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !queueing && (provider == null || (pkg != null && status == "Connected" && !busy && pending.isNotEmpty() && pkg.qualities.any { it.first == quality })), onClick = {
                if (provider == null) {
                    wanted.forEach { Downloads.enqueue(context, it, standard.maxKbps) }
                    onDismiss()
                } else scope.launch {
                    queueing = true
                    try {
                        for (track in matches) LosslessDownloads.enqueue(track, quality)
                        onDismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Some downloads could not be queued. Check Library → Downloads, then retry the remaining songs." }
                    finally { queueing = false }
                }
            }) { Text(if (queueing) "Adding downloads…" else if (provider == null) "Download" else "Download FLAC${if (wanted.size > 1) " · ${pending.size} songs" else ""}") }
            TextButton(onClick = onDownloads, enabled = !queueing) { Text("View downloads") }
        }
    }
}

@Composable
private fun QualityOption(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    OutlinedCard(onClick = onSelect, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
