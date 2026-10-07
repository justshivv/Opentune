package com.opentune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opentune.data.subsonic.Subsonic
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.launch

/** Connects a Subsonic-compatible music server: Navidrome, Gonic, Airsonic, Nextcloud Music… */
@Composable
fun ServerDialog(onDismiss: () -> Unit, onConnected: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(Subsonic.server.value?.url.orEmpty()) }
    var user by remember { mutableStateOf(Subsonic.server.value?.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect your music server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Any server that speaks the Subsonic API: Navidrome, Gonic, Airsonic, Nextcloud Music and others. " +
                        "Songs stream as they're stored, so FLAC stays lossless.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    address, { address = it },
                    label = { Text("Server address") },
                    placeholder = { Text("music.example.com or 192.168.1.10:4533") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The password isn't stored, only a sign-in token made from it. Plain http works for a server on your home network; use https anywhere else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            SheetButton(
                text = "Connect",
                loading = busy,
                enabled = !busy && address.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        Subsonic.connect(address, user, password)
                            .onSuccess { onConnected(); onDismiss() }
                            .onFailure { error = it.message ?: "Couldn't connect" }
                        busy = false
                    }
                },
            )
        },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}
