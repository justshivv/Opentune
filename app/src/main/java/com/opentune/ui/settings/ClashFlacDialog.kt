package com.opentune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.opentune.data.lossless.ClashFlacSource
import com.opentune.data.lossless.ExternalStreams
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton

@Composable
internal fun ClashFlacDialog(initial: String, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val valid = value.isBlank() || ClashFlacSource.baseUrl(value) != null
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("ClashFLAC server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect your own ClashFLAC server with its Qobuz account already configured. Only verified, full-length FLAC is used. If unavailable, your current audio keeps playing.")
                OutlinedTextField(value, { value = it }, singleLine = true,
                    label = { Text("HTTPS server URL") }, isError = !valid,
                    supportingText = { Text("Leave blank to disconnect. Do not include passwords or tokens in the URL.") })
            }
        },
        confirmButton = { SheetButton("Save", enabled = valid, onClick = {
            AppSettings.updatePlayback { it.copy(clashFlacUrl = ClashFlacSource.baseUrl(value).orEmpty()) }
            ExternalStreams.retryLookups()
            onDismiss()
        }, closes = true) },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}
