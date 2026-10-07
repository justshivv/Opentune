package com.opentune.ui.settings

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opentune.data.listenbrainz.ListenBrainz
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.launch

/** Connects ListenBrainz with the user token from listenbrainz.org/settings. */
@Composable
fun ListenBrainzDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect ListenBrainz") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "ListenBrainz is a free, open listening history run by the MetaBrainz Foundation. " +
                        "Sign in there, copy your user token from the settings page, and paste it here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SheetButton("Get your token", onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://listenbrainz.org/settings/".toUri())) }
                })
                OutlinedTextField(
                    token, { token = it },
                    label = { Text("User token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            SheetButton(
                text = "Connect",
                loading = busy,
                enabled = !busy && token.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        ListenBrainz.signIn(token)
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "Couldn't sign in" }
                        busy = false
                    }
                },
            )
        },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}
