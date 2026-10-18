package com.opentune.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.lossless.ExtensionSessions
import com.opentune.data.lossless.ProviderPackages
import com.opentune.ui.lossless.LosslessConnectionDialog
import com.opentune.ui.lossless.ProviderRepositoryPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadProvidersSheet(onDismiss: () -> Unit) {
    val installed by ProviderPackages.installed.collectAsState()
    var connection by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Download providers", style = MaterialTheme.typography.headlineSmall)
                Text("Import a repository, install the providers you want, then connect in your browser. Choose a quality from any song’s Download button.")
                installed.values.forEach { pkg ->
                    val status by ExtensionSessions.forProvider(pkg.id).status.collectAsState()
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(pkg.name, style = MaterialTheme.typography.titleMedium)
                            Text("$status · ${pkg.version}")
                            Text(pkg.qualities.joinToString(" · ") { it.second }, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { connection = pkg.id }) { Text("Manage connection") }
                        }
                    }
                }
            }
            ProviderRepositoryPanel { connection = it }
        }
    }
    connection?.let { id -> LosslessConnectionDialog(id) { connection = null } }
}
