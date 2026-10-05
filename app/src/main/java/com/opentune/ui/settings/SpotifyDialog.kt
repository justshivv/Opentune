package com.opentune.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opentune.data.spotify.Spotify

/**
 * Spotify sign-in: the user's own Client ID from developer.spotify.com,
 * then Spotify's login page in the browser.
 */
@Composable
fun SpotifyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var clientId by remember { mutableStateOf(Spotify.clientId.orEmpty()) }
    val valid = Regex("[0-9a-fA-F]{32}").matches(clientId.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to Spotify") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "To bring your Spotify playlists and liked songs over. Songs are found and played on YouTube Music; nothing streams from Spotify.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Spotify only lets registered apps sign in, so register one for yourself (it's free):\n" +
                        "1. Open developer.spotify.com/dashboard and create an app.\n" +
                        "2. Add this Redirect URI, and tick Web API:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(Spotify.REDIRECT_URI, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Redirect URI", Spotify.REDIRECT_URI))
                }) { Text("Copy redirect URI") }
                Text("3. Copy the app's Client ID here:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    clientId, { clientId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    isError = clientId.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://developer.spotify.com/dashboard".toUri())) }
                }) { Text("Open the Spotify dashboard") }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Spotify.authorizeUrl(clientId))) }
                onDismiss()
            }) { Text("Sign in") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
