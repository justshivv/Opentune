package com.opentune.ui.spotify

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.spotify.Spotify
import com.opentune.data.spotify.SpotifyImport
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.settings.SpotifyDialog

/** Spotify's playlists and liked songs, each with a button to bring it over as a phone playlist. */
@Composable
fun SpotifyImportScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val account by Spotify.account.collectAsState()
    val progress by SpotifyImport.progress.collectAsState()
    var signIn by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<Spotify.Playlist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    if (signIn) SpotifyDialog(onDismiss = { signIn = false })

    LaunchedEffect(account, attempt) {
        if (account == null) return@LaunchedEffect
        error = null
        runCatching { Spotify.playlists() }.fold({ playlists = it }, { error = it.message ?: "Couldn't load playlists" })
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Import from Spotify", onBack = onBack) }
        if (account == null) {
            item {
                MessageState(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    "Not signed in to Spotify",
                    message = "Sign in with Spotify's own login to bring your playlists and liked songs over.",
                    action = { Button(onClick = { signIn = true }) { Text("Sign in to Spotify") } },
                )
            }
            return@LazyColumn
        }
        item {
            Text(
                "Signed in as ${account?.name}. Each song is looked up on YouTube Music; the closest match is kept and the rest are listed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        progress?.let { p -> item { ProgressCard(p, onOpenPlaylist) } }
        item {
            ImportRow(null, "Liked songs", "Your saved tracks", Icons.Rounded.Favorite, enabled = !SpotifyImport.running) {
                SpotifyImport.start("Liked on Spotify") { Spotify.likedTracks() }
            }
        }
        when {
            error != null -> item { ErrorState(error!!, onRetry = { attempt++ }) }
            playlists == null -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            else -> items(playlists!!, key = { it.id }) { p ->
                ImportRow(p.image, p.name, listOf("${p.tracks} songs", p.owner).filter { it.isNotBlank() }.joinToString(" · "), Icons.AutoMirrored.Rounded.QueueMusic, enabled = !SpotifyImport.running) {
                    SpotifyImport.start(p.name) { Spotify.playlistTracks(p.id) }
                }
            }
        }
        item {
            TextButton(onClick = { Spotify.signOut() }, modifier = Modifier.padding(16.dp)) { Text("Sign out of Spotify") }
        }
    }
}

@Composable
private fun ImportRow(image: String?, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onImport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onImport).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(image, Modifier.size(52.dp), RoundedCornerShape(8.dp), placeholder = icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onImport, enabled = enabled) { Text("Import") }
    }
}

@Composable
private fun ProgressCard(p: SpotifyImport.Progress, onOpenPlaylist: (String) -> Unit) {
    var showMissed by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                p.error != null -> Text(p.error, color = MaterialTheme.colorScheme.error)
                p.total == 0 && !p.finished -> {
                    Text("Reading the playlist from Spotify…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                !p.finished -> {
                    Text("Finding songs on YouTube Music: ${p.done} of ${p.total}")
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                }
                else -> Text("Found ${p.matched} of ${p.total} songs.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!p.finished) TextButton(onClick = SpotifyImport::cancel) { Text("Cancel") }
                p.playlistId?.let { id -> TextButton(onClick = { onOpenPlaylist(id) }) { Text("Open playlist") } }
                if (p.missed.isNotEmpty()) TextButton(onClick = { showMissed = !showMissed }) { Text(if (showMissed) "Hide missed" else "${p.missed.size} not found") }
                if (p.finished) TextButton(onClick = SpotifyImport::dismiss) { Text("Close") }
            }
            if (showMissed) {
                p.missed.take(200).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
