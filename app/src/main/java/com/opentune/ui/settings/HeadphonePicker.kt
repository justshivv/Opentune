package com.opentune.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.autoeq.AutoEq
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * Searches AutoEq's measured headphones and applies the chosen model's
 * correction. Several people measure popular models; each measurement is
 * listed with who made it.
 */
@Composable
fun HeadphonePickerDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf<String?>(null) }
    val index by produceState<List<AutoEq.Entry>?>(null) {
        value = runCatching { AutoEq.index() }.onFailure { error = "Couldn't load AutoEq's list: ${it.message}" }.getOrNull()
    }
    val results = remember(index, query) { index?.let { AutoEq.search(it, query) }.orEmpty() }
    LaunchedEffect(query) { error = null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your headphones") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A correction from AutoEq brings your headphones to a neutral sound, measured model by model.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("e.g. HD 650, Galaxy Buds") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    index == null && error == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    query.isNotBlank() && results.isEmpty() -> Text("No measured model matches \"$query\".", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(results, key = { it.path }) { entry ->
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = applying == null) {
                                applying = entry.path
                                scope.launch {
                                    runCatching { AutoEq.load(entry) }
                                        .onSuccess { eq -> AppSettings.updateEqualizer { it.copy(headphone = eq) }; onDismiss() }
                                        .onFailure { error = "Couldn't load ${entry.name}: ${it.message}" }
                                    applying = null
                                }
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (applying == entry.path) "Loading…" else "Measured by ${entry.source}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
