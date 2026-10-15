package com.opentune.ui.settings

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
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
    val pending by ExtensionSessions.pending.collectAsState()
    val canRetry by ExtensionSessions.canRetry.collectAsState()
    var registry by remember { mutableStateOf(ExtensionRegistry.OFFICIAL) }
    var entries by remember { mutableStateOf<List<ExtensionRegistry.Entry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        while (pending) { kotlinx.coroutines.delay(1_000); ExtensionSessions.refreshStatus() }
    }
    LaunchedEffect(status) { if (status == "Connected") message = "Connected. Your current track will be checked again." }
    fun openBrowser(url: String, external: Boolean = false) {
        val uri = Uri.parse(url)
        if (!external) {
            try {
                CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                    .build().launchUrl(context, uri)
                return
            } catch (_: android.content.ActivityNotFoundException) { /* Try the normal browser. */ }
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }
    FloatingDialog(onDismissRequest = onDismiss, title = { Text("SpotiFLAC providers") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Connect Amazon Music through the SpotiFLAC extension service. Verification opens in your browser and returns here. Availability depends on the provider.")
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Amazon Music", style = MaterialTheme.typography.titleMedium)
                    Text(status, color = MaterialTheme.colorScheme.primary)
                    Text("FLAC · best available\nUse Max quality or enable Lossless below Max. Audio keeps playing while an upgrade is prepared.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && status != "Connected" && status != "Saving verified session…", onClick = {
                        busy = true; message = null
                        scope.launch {
                            try {
                                val url = ExtensionSessions.connect()
                                if (url != null) openBrowser(url)
                                else message = "Connected. Your current track will be checked again."
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { message = "Could not open verification. Check your connection and try again." }
                            finally { busy = false }
                        }
                    }) { Text(if (status == "Connected") "Connected" else if (pending) "Continue verification" else "Connect in browser") }
                    if (pending) {
                        Text("Complete verification, then tap the browser’s return-to-app button. If it stays in the browser, copy its completed callback link and tap Paste browser result below.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = {
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                message = if (ExtensionSessions.receiveCallback(text, pasted = true)) "Verification received. Saving the session…"
                                    else "No matching completed callback found. Copy the result from this verification, not the original verification link."
                            } catch (_: Exception) { message = "Could not read or save the browser result. Try again." }
                        }) { Text("Paste browser result") }
                        TextButton(onClick = {
                            try { ExtensionSessions.pendingUrl()?.let { openBrowser(it, external = true) } }
                            catch (_: Exception) { message = "No browser could open verification." }
                        }) { Text("Open in external browser") }
                    }
                    if (canRetry) TextButton(onClick = { ExtensionSessions.retryCompletion() }) { Text("Retry saving session") }
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
