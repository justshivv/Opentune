package com.opentune.ui.lossless

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.opentune.data.lossless.*
import com.opentune.data.settings.AppSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.PageHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LosslessScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val entries by LosslessDownloads.entries.collectAsState()
    val installed by ProviderPackages.installed.collectAsState()
    var chosenProvider by rememberSaveable { mutableStateOf("amazon") }
    val provider = chosenProvider.takeIf { it in installed } ?: installed.keys.firstOrNull() ?: "amazon"
    val pkg = installed[provider]
    val status by ExtensionSessions.forProvider(provider).status.collectAsState()
    val network by DownloadNetwork.state.collectAsState()
    val workers by LosslessDownloads.workStates.collectAsState()
    var providerMenu by remember { mutableStateOf(false) }
    var quality by rememberSaveable(provider, pkg?.version) { mutableStateOf(pkg?.qualities?.firstOrNull()?.first ?: "best") }
    var details by remember { mutableStateOf<String?>(null) }
    val library by AppSettings.library.collectAsState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<LosslessTrack>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var connection by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<LosslessTrack?>(null) }
    var deleting by remember { mutableStateOf<LosslessEntry?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
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
    LaunchedEffect(submitted, attempt, provider) {
        if (submitted.isBlank()) return@LaunchedEffect
        busy = true; error = null; results = emptyList()
        try { results = withContext(Dispatchers.IO) { ProviderRuntime.search(provider, submitted) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = losslessError("Catalog search", e) }
        finally { busy = false }
    }
    fun search() { keyboard?.hide(); submitted = query.trim(); attempt++ }
    fun enqueue(track: LosslessTrack, selectedQuality: String = quality) {
        selected = null; tab = 1
        scope.launch {
            try { LosslessDownloads.enqueue(track, selectedQuality); if (installed[track.provider] == null) tab = 2 else if (ExtensionSessions.forProvider(track.provider).current() == null) connection = track.provider }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "Could not queue the download. Check free space and try again." }
        }
    }
    fun remove(entry: LosslessEntry) { scope.launch {
        try { LosslessDownloads.remove(entry.track.key) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "Could not remove this download. Try again." }
    } }
    val saved = entries.values.filter { it.state == LosslessState.DONE }.sortedByDescending { it.addedAt }
    val queue = entries.values.filter { it.state != LosslessState.DONE }.sortedBy { it.addedAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Lossless", onBack = onBack) }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer))).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(32.dp))
                    Text("Keep every detail.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Download the best available FLAC. Check it, save it, and listen offline.")
                    Text("${saved.size} saved · ${sizeLabel(saved.sumOf { it.bytes })}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        TextButton(onClick = { providerMenu = true }) { Text(ProviderPackages.displayName(provider) + " ▾", style = MaterialTheme.typography.titleMedium) }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            installed.values.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.name) }, onClick = { chosenProvider = candidate.id; providerMenu = false; results = emptyList(); submitted = "" }) }
                            DropdownMenuItem(text = { Text("Add providers…") }, onClick = { providerMenu = false; tab = 2 })
                        }
                    }
                    Text(if (pkg == null) "Provider package not installed" else "$status · Extension ${pkg.version}", color = MaterialTheme.colorScheme.primary)
                    Text("FLAC · best available. Actual bit depth and sample rate appear after download. Availability varies by recording.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { if (pkg == null) tab = 2 else connection = provider }) { Text(if (pkg == null) "Install provider" else if (status == "Connected") "Manage connection" else "Connect provider") }
                }
            }
        }
        notice?.let { message ->
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(message)
                    TextButton(onClick = { notice = null }) { Text("Dismiss") }
                }
            }
        }
        item { FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text("Find music") })
            FilterChip(tab == 1, { tab = 1 }, label = { Text("Downloads (${entries.size})") })
            FilterChip(tab == 2, { tab = 2 }, label = { Text("Providers") })
        } }
        if (tab == 0) {
            item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Song or artist") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                Button(onClick = { search() }, enabled = query.isNotBlank() && !busy && pkg != null, modifier = Modifier.fillMaxWidth()) { Text("Search ${ProviderPackages.displayName(provider)}") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!busy && error == null) Text(if (submitted.isBlank()) "Find a track, then choose Download FLAC." else if (results.isEmpty()) "No tracks found. Try the title and artist." else "${results.size} tracks · select a recording")
            } }
            items(results, key = { "search:${it.id}" }) { track ->
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrackHeading(track)
                        val entry = entries[track.key]
                        Button(onClick = { selected = track }, enabled = entry == null || entry.state == LosslessState.FAILED) {
                            Text(if (entry?.state == LosslessState.DONE) "Saved lossless" else if (entry != null && entry.state != LosslessState.FAILED) "In downloads" else "Download FLAC")
                        }
                    }
                }
            }
        } else if (tab == 2) {
            item { ProviderRepositoryPanel { chosenProvider = it; tab = 0; submitted = ""; results = emptyList() } }
        } else {
            item { Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Unmetered networks only"); Text(network.label, style = MaterialTheme.typography.bodySmall) }
                Switch(checked = library.downloadWifiOnly, onCheckedChange = { enabled -> AppSettings.updateLibrary { it.copy(downloadWifiOnly = enabled) } })
            } }
            item { Text(if (library.downloadWifiOnly) "Downloads wait for an unmetered network, following your download settings." else "Downloads can use Wi-Fi or mobile data.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
            if (entries.isEmpty()) item { Text("Your lossless library starts here. Find a track to save it for offline listening.", Modifier.padding(24.dp)) }
            if (queue.isNotEmpty()) item { Text("Download queue", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge) }
            items(queue, key = { it.track.key }) { e -> LosslessCard(e, onRetry = { enqueue(e.track, e.quality) }, onCancel = { remove(e) },
                onConnect = { if (installed[e.track.provider] == null) tab = 2 else connection = e.track.provider },
                waitingReason = if (e.state == LosslessState.QUEUED) DownloadNetwork.waitingReason(e, network, workers[e.workId] == androidx.work.WorkInfo.State.RUNNING) else null,
                showMobileAction = e.state == LosslessState.QUEUED && e.unmeteredOnly && network.connected && network.metered,
                onMobile = { scope.launch {
                    try { LosslessDownloads.enqueue(e.track, e.quality, allowMetered = true, replaceQueued = true) }
                    catch (c: CancellationException) { throw c }
                    catch (_: Exception) { notice = "Could not reschedule the download. Tap Retry." }
                } },
                needsInstallation = installed[e.track.provider] == null,
                onDetails = { details = e.track.key }) }
            if (saved.isNotEmpty()) item { Text("Saved lossless", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge) }
            items(saved, key = { it.track.key }) { e -> LosslessCard(e,
                onPlay = { actions.playAll(saved.map(LosslessDownloads::song), saved.indexOf(e), false, "Lossless downloads") },
                onExport = { exportId = e.track.key; exporter.launch("${e.track.artist} - ${e.track.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150) + ".flac") },
                onRemove = { deleting = e }, exportEnabled = !exporting, onDetails = { details = e.track.key }) }
            if (exporting) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    connection?.let { id -> LosslessConnectionDialog(id) { connection = null } }
    details?.let { key -> entries[key]?.let { DownloadDetailsDialog(it, network, workers[it.workId]?.name ?: "Not scheduled", onDismiss = { details = null }) } }
    selected?.let { track -> FloatingDialog(onDismissRequest = { selected = null }, title = { Text("Download lossless") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleLarge); Text(track.artist)
            Text(ProviderPackages.displayName(track.provider))
            installed[track.provider]?.qualities?.forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = quality == id, onClick = { quality = id }); TextButton(onClick = { quality = id }) { Text(label) }
                }
            }
            Text("The complete file is downloaded and checked before offline playback. No lossy fallback or upsampling.")
            Text("Saved in OpenTune. Export afterward to keep a copy in your chosen folder. Up to 512 MB per track; 1 GB free space needed.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { enqueue(track) }) { Text("Download FLAC") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } }) }
    deleting?.let { e -> FloatingDialog(onDismissRequest = { deleting = null }, title = { Text("Remove saved FLAC?") }, text = { Text("Remove ${e.track.title} from OpenTune? Copies you exported stay in their folders.") },
        confirmButton = { TextButton(onClick = { deleting = null; remove(e) }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
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
