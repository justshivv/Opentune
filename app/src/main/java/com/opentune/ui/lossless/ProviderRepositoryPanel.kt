package com.opentune.ui.lossless

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.lossless.*
import com.opentune.ui.components.FloatingDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProviderRepositoryPanel(onInstalled: (String) -> Unit) {
    val listings by ProviderPackages.listings.collectAsState()
    val installed by ProviderPackages.installed.collectAsState()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(ProviderPackages.DEFAULT_REPOSITORY) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<ProviderListing?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Provider repository", style = MaterialTheme.typography.titleLarge)
        Text("Import a repository, choose a provider, then install its package. Each provider has its own connection and catalog.")
        OutlinedTextField(url, { url = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text("Repository URL") }, minLines = 2, maxLines = 4)
        Button(enabled = busy == null, onClick = { scope.launch {
            busy = "repository"; error = null
            try { ProviderPackages.importRepository(url) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not import this repository. Check the URL and connection." }
            finally { busy = null }
        } }) { Text(if (busy == "repository") "Loading repository…" else "Import repository") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (listings.isNotEmpty()) Text("${listings.size} providers · ${installed.size} installed", style = MaterialTheme.typography.labelLarge)
        (listings + installed.values.map { it.listing }.filter { p -> listings.none { it.id == p.id } }).forEach { p ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text("v${p.version}" + if (installed[p.id] != null) " · Installed v${installed[p.id]!!.version}" else "", style = MaterialTheme.typography.labelMedium)
                    Text(p.description, style = MaterialTheme.typography.bodySmall)
                    when {
                        !p.supported -> Text(if (!p.lossless) "Metadata or lossy provider · unavailable in Lossless" else "Not yet compatible with OpenTune's lossless runtime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        installed[p.id]?.listing?.sha256 == p.sha256 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onInstalled(p.id) }) { Text("Use provider") }
                            TextButton(enabled = busy == null, onClick = { removing = p.id }) { Text("Uninstall") }
                        }
                        else -> Button(enabled = busy == null, onClick = { installing = p }) {
                            Text(if (busy == p.id) "Downloading and checking…" else if (installed[p.id] != null) "Update provider" else "Install provider")
                        }
                    }
                    if (busy == p.id) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
    installing?.let { p -> FloatingDialog(onDismissRequest = { installing = null }, title = { Text("Install ${p.name}?") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This downloads and runs the provider's JavaScript package from the repository. It can contact the network hosts in its manifest and write inside its own download folder.")
            Text("OpenTune checks the package checksum and blocks access to Java, other app files, and undeclared network hosts.")
            Text("Version ${p.version}\nSHA-256 ${p.sha256}", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = {
        installing = null; scope.launch {
            busy = p.id; error = null
            try { ProviderPackages.install(p); onInstalled(p.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is IllegalArgumentException) e.message?.take(250) ?: "Package verification failed." else "Installation failed. Re-import the repository and try again." }
            finally { busy = null }
        }
    }) { Text("Install") } }, dismissButton = { TextButton(onClick = { installing = null }) { Text("Cancel") } }) }
    removing?.let { id -> FloatingDialog(onDismissRequest = { removing = null }, title = { Text("Uninstall provider?") },
        text = { Text("Saved audio stays on your phone. New downloads from this provider will need it installed again.") },
        confirmButton = { TextButton(onClick = { scope.launch { ProviderPackages.uninstall(id) }; removing = null }) { Text("Uninstall") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } }) }
}
