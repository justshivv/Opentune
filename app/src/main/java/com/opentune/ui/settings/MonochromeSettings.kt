package com.opentune.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.addons.*
import com.opentune.ui.components.AppSwitch
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private enum class Health { Unknown, Checking, Working, Unavailable }
private sealed interface Check {
    data object Server : Check
    data class Search(val query: String) : Check
    data class Track(val track: AddonTrack) : Check
}

@Composable
internal fun MonochromeSettings(source: AudioAddon?) {
    var open by rememberSaveable { mutableStateOf(false) }
    val enabled = source?.enabled == true && !source.ownerOff
    val paused = source?.let { AddonResolver.paused(it.key) } == true
    val canCheck = enabled && !paused && source != null && AddonStore.usable(source)
    var action by remember(source?.base, canCheck) { mutableStateOf<Check>(Check.Server) }
    var attempt by remember { mutableIntStateOf(0) }
    var health by remember(source?.base, canCheck) { mutableStateOf(Health.Unknown) }
    var result by remember(source?.base, canCheck) { mutableStateOf("") }
    var tracks by remember(source?.base, canCheck) { mutableStateOf(emptyList<AddonTrack>()) }
    var query by rememberSaveable { mutableStateOf("") }
    fun run(check: Check) { action = check; attempt++ }

    LaunchedEffect(source?.base, canCheck, attempt) {
        if (source == null || !canCheck) return@LaunchedEffect
        health = Health.Checking
        result = ""
        try {
            val completed = withTimeoutOrNull(25_000) {
                when (val check = action) {
                    is Check.Search -> {
                        tracks = MonochromeCheck.search(source, check.query)
                        result = if (tracks.isEmpty()) "No matching songs" else "Choose a song to test its stream"
                        health = Health.Unknown
                    }
                    else -> {
                        if (check == Check.Server) {
                            val manifest = AddonClient.forBase(source.base).manifest(fresh = true)
                            AddonStore.refreshed(source.key, manifest, expectedBase = source.base)
                        }
                        val track = (check as? Check.Track)?.track ?: MonochromeCheck.search(source, "Wo Noor AP Dhillon").firstOrNull()
                        val format = track?.let { MonochromeCheck.verify(source, it) }
                        health = if (format != null) Health.Working else Health.Unknown
                        result = when {
                            track == null -> "Catalogue reachable. Try another song to check a stream."
                            format == null -> "Couldn’t verify FLAC for this song."
                            else -> "${track.title} · ${track.artist}\n$format"
                        }
                    }
                }
                true
            }
            if (completed == null) { health = Health.Unavailable; result = "Server check timed out" }
        } catch (e: CancellationException) { throw e }
        catch (e: AddonHttp.Failure) {
            AddonResolver.notePaused(source.key, e)
            health = Health.Unavailable
            result = when (e.status) {
                401, 403 -> "The server declined this device’s request."
                429 -> "The server is rate limiting requests. Try again later."
                else -> "Server returned HTTP ${e.status}"
            }
        } catch (_: Exception) {
            health = Health.Unavailable
            result = "Couldn’t reach or verify the stream from this device."
        }
        if (health == Health.Working) {
            delay(5 * 60_000L)
            health = Health.Unknown
        }
    }
    val label = when {
        source?.ownerOff == true || paused -> "Paused by its owner"
        !enabled -> "Off"
        !canCheck -> "Waiting for a supported output"
        health == Health.Checking -> "Checking…"
        health == Health.Working -> "Working · FLAC verified"
        health == Health.Unavailable -> "Unavailable"
        else -> "On · stream unverified"
    }
    val colors = MaterialTheme.colorScheme
    val dot = when {
        !canCheck -> colors.outline
        health == Health.Working -> Color(0xFF30B85A)
        health == Health.Unavailable -> colors.error
        health == Health.Checking -> colors.tertiary
        else -> colors.outline
    }
    GroupLabel("Built-in modules")
    GroupCard {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Canvas(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black).padding(10.dp)) {
                val half = size.minDimension / 2
                drawRect(Color.White, Offset(half, 0f), Size(half, half))
                drawRect(Color.White, Offset(0f, half), Size(half, half))
            }
            Column(Modifier.weight(1f)) {
                Text("Monochrome", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ModuleStatus(label, dot)
            }
            AppSwitch(enabled, { AddonStore.setMonochromeEnabled(it) }, enabled = source?.ownerOff != true)
        }
    }
    if (!open) return
    Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                IconButton(onClick = { open = false }) {
                    Icon(ImageVector.vectorResource(AppIcons.ArrowBack), contentDescription = "Back")
                }
                Text("Monochrome", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                ModuleStatus(label, dot)
                Text("Find matching songs in the Monochrome FLAC catalogue.", color = colors.onSurfaceVariant)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            AppSwitch(enabled, { AddonStore.setMonochromeEnabled(it) }, enabled = source?.ownerOff != true)
                        }
                        Text("Built into OpenTune · No music account needed")
                        source?.manifest?.version?.takeIf { it.isNotBlank() }?.let { Text("Adapter version $it") }
                        Text("Server · ${AddonProtocol.url(source?.base ?: MonochromeModule.BASE).host}")
                    }
                }
                val ready = canCheck && health != Health.Checking
                Button(onClick = { run(Check.Server) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Check server") }
                Text("Test a song", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = query, onValueChange = { query = it.take(200); tracks = emptyList() },
                    enabled = ready, singleLine = true, label = { Text("Song or artist") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { run(Check.Search(query.trim())) }, enabled = ready && query.isNotBlank()) { Text("Search songs") }
                if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodyMedium)
                tracks.forEach { track ->
                    TextButton(onClick = { run(Check.Track(track)) }, enabled = ready) { Text("${track.title} · ${track.artist}") }
                }
            }
        }
    }
}

@Composable
private fun ModuleStatus(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.size(9.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
