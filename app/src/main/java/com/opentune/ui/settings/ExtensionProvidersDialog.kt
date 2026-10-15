package com.opentune.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentune.data.lossless.ExtensionRegistry
import com.opentune.data.lossless.ExtensionSessions
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ExtensionProvidersDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ExtensionSessions.status.collectAsState()
    var registry by remember { mutableStateOf(ExtensionRegistry.OFFICIAL) }
    var entries by remember { mutableStateOf<List<ExtensionRegistry.Entry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    FloatingDialog(onDismissRequest = onDismiss, title = { Text("SpotiFLAC providers") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Connect Amazon Music through the SpotiFLAC extension service. Verification opens in your browser and returns here. Availability depends on the provider.")
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Amazon Music", style = MaterialTheme.typography.titleMedium)
                    Text(status, color = MaterialTheme.colorScheme.primary)
                    Text("FLAC · best available\nUse Max quality or enable Lossless below Max. Audio keeps playing while an upgrade is prepared.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = {
                        busy = true; message = null
                        scope.launch {
                            try {
                                val url = ExtensionSessions.connect()
                                if (url != null) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                                else message = "Connected. Your current track will be checked again."
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { message = "Could not open verification. Check your connection and try again." }
                            finally { busy = false }
                        }
                    }) { Text(if (status == "Connected") "Renew verification" else "Connect in browser") }
                    if (ExtensionSessions.enabled()) TextButton(onClick = { ExtensionSessions.disconnect(); message = "Amazon Music disconnected" }) { Text("Disconnect / cancel verification") }
                }
            }
            HorizontalDivider()
            Text("Extension repositories", style = MaterialTheme.typography.titleMedium)
            Text("Import either repository link to inspect its providers. Amazon 2.3.11 is supported in this preview. Other packages need an OpenTune adapter; importing does not install or run their code.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(registry, { registry = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Registry URL") }, maxLines = 4)
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; message = null
                scope.launch {
                    try { entries = ExtensionRegistry.fetch(registry); message = "${entries.size} providers loaded" }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Could not load the registry. Use the spotiflacapp or zarzet registry.json link." }
                    finally { busy = false }
                }
            }) { Text("Import repository") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            entries.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    Text("v${entry.version} · ${if (entry.supported) "Supported above" else "Not supported in this preview"}", style = MaterialTheme.typography.labelMedium)
                    Text(entry.description, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) })
}
