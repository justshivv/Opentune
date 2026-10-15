package com.opentune.ui.spotify

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentune.data.spotify.PlaylistImport
import com.opentune.data.spotify.SpotifyAccount
import com.opentune.data.spotify.SpotifyLibrary
import com.opentune.data.spotify.SpotifyOAuth
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SpotifyConnection(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by SpotifyAccount.connected.collectAsState()
    val status by SpotifyAccount.status.collectAsState()
    var id by remember { mutableStateOf(SpotifyAccount.clientId()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<SpotifyLibrary.Playlist>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) { scope.launch {
        busy = true; error = null
        try { action() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn't connect to Spotify" }
        finally { busy = false }
    } }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (connected) "Your Spotify library" else "Connect Spotify") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Import playlists and Liked Songs into OpenTune. Spotify is used for your library; audio plays from OpenTune's available sources.")
                if (!connected) {
                    OutlinedTextField(id, { id = it.trim() }, label = { Text("Spotify client ID") }, singleLine = true,
                        supportingText = { Text("Use your Spotify Developer app's public client ID. No client secret is needed.") })
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/justshivv/Opentune/blob/feat/audio-sources-spotify-preview/docs/spotify-connect.md"))) }) { Text("Set up Spotify sign-in") }
                    Button(enabled = !busy && SpotifyOAuth.validClientId(id), onClick = { run {
                        val url = SpotifyAccount.begin(id)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } }) { Text("Continue with Spotify") }
                } else {
                    Button(enabled = !busy && !PlaylistImport.running, onClick = {
                        PlaylistImport.start("Spotify Liked Songs") { SpotifyLibrary.likedSongs() }; onDismiss()
                    }) { Text("Import Liked Songs") }
                    OutlinedButton(enabled = !busy, onClick = { run { playlists = SpotifyLibrary.playlists(); loaded = true } }) {
                        Text(if (loaded) "Refresh playlists" else "Show playlists")
                    }
                    if (loaded && playlists.isEmpty()) Text("No playlists were returned by Spotify.")
                    if (playlists.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            TextButton(enabled = !PlaylistImport.running && !busy, onClick = {
                                PlaylistImport.start(playlist.name) { SpotifyLibrary.playlist(playlist.id) }; onDismiss()
                            }) { Text("Import ${playlist.name}") }
                        }
                    }
                    TextButton(enabled = !busy, onClick = { run {
                        SpotifyAccount.disconnect(); playlists = emptyList(); loaded = false
                    } }) { Text("Disconnect Spotify") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) },
    )
}
