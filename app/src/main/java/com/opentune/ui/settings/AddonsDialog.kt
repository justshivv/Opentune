package com.opentune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opentune.data.addons.Addons
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.launch

/** The guide for people making an addon. */
const val ADDON_GUIDE_URL = "https://justshivv.github.io/Opentune/addons/"

/** Adds addons by URL, and switches them on, off or away. */
@Composable
fun AddonsDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val installed by Addons.installed.collectAsState()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }

    FloatingDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Extension, null) },
        title = { Text("Addons") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "An addon is a music catalogue someone runs on their own server: a label, an artist, a library of free music. " +
                        "Its songs show up in search under its name and play like any other.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                installed.forEach { addon ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(addon.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOfNotNull(addon.version?.let { "v$it" }, addon.baseUrl.substringAfter("://")).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(addon.enabled, { Addons.setEnabled(addon.id, it) })
                                IconButton(onClick = { Addons.remove(addon.id) }) { Icon(Icons.Rounded.Delete, "Remove ${addon.name}") }
                            }
                            addon.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp, top = 4.dp)) }
                            Text(
                                addon.catalogue,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp, top = 4.dp),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    address, { address = it; error = null; added = null },
                    label = { Text("Addon address") },
                    placeholder = { Text("music.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Only add addons you trust, for music they're allowed to share. OpenTune reads what an addon sends back; it never runs its code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                added?.let { Text("Added $it.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { uri.openUri(ADDON_GUIDE_URL) }) { Text("Make your own addon") }
            }
        },
        confirmButton = {
            SheetButton(
                text = "Add",
                loading = busy,
                enabled = !busy && address.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    added = null
                    scope.launch {
                        Addons.add(address)
                            .onSuccess { added = it.name; address = "" }
                            .onFailure { error = it.message ?: "Couldn't add that addon" }
                        busy = false
                    }
                },
            )
        },
        dismissButton = { SheetButton("Done", onClick = onDismiss, closes = true) },
    )
}
