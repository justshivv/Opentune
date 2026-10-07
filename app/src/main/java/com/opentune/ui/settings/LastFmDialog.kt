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
import com.opentune.data.lastfm.LastFm
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.launch

/**
 * Connects Last.fm with the listener's own API account. The password is
 * sent once to Last.fm to get a session key and isn't kept.
 */
@Composable
fun LastFmDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(LastFm.apiKey) }
    var secret by remember { mutableStateOf(LastFm.secret) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Last.fm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Create a free API account at last.fm/api, then paste its key and secret here and sign in with your Last.fm login.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SheetButton("Get an API key", onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.last.fm/api/account/create".toUri())) }
                })
                OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("Shared secret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
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
                text = "Sign in",
                loading = busy,
                enabled = !busy && key.isNotBlank() && secret.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        LastFm.signIn(key, secret, user.trim(), password)
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
