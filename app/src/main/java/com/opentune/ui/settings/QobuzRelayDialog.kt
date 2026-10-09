package com.opentune.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun QobuzRelayDialog(initial: String, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val parsed = value.trim().toHttpUrlOrNull()
    val valid = value.isBlank() || parsed?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null } == true
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Qobuz relay") },
        text = {
            Column {
                Text("Use a server compatible with Meld's Qobuz API. Leave blank to use the community providers. Availability depends on the server.")
                OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("HTTPS server URL") }, isError = !valid)
            }
        },
        confirmButton = {
            SheetButton("Save", enabled = valid, onClick = {
                AppSettings.updatePlayback { it.copy(qobuzRelayUrl = value.trim().trimEnd('/')) }
                onDismiss()
            }, closes = true)
        },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}
