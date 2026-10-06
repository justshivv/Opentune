package com.opentune.ui.spotify

import android.content.ClipboardManager
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.spotify.Spotify
import com.opentune.data.spotify.SpotifyImport
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.PageHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface Preview {
    data object Empty : Preview
    data object Loading : Preview
    data class Ready(val collection: Spotify.Collection) : Preview
    data class Failed(val message: String) : Preview
}

/**
 * Bring a public Spotify playlist, album or song over by its link: paste
 * it (or share it to OpenTune from the Spotify app), check what it is, and
 * import it as a playlist on the phone. Each song is found on YouTube Music.
 */
@Composable
fun SpotifyImportScreen(
    contentPadding: PaddingValues,
    actions: SongActions,
    initialLink: String?,
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by SpotifyImport.progress.collectAsState()
    var text by rememberSaveable { mutableStateOf(initialLink.orEmpty()) }
    var preview by remember { mutableStateOf<Preview>(Preview.Empty) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        val t = text.trim()
        if (t.isEmpty()) { preview = Preview.Empty; return@LaunchedEffect }
        if (!Spotify.looksLikeSpotify(t)) { preview = Preview.Failed("That isn't a Spotify playlist, album or song link."); return@LaunchedEffect }
        preview = Preview.Loading
        preview = try {
            val link = Spotify.resolve(t) ?: throw java.io.IOException("Couldn't open that link.")
            Preview.Ready(Spotify.load(link))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Preview.Failed(e.message ?: "Couldn't load it from Spotify.")
        }
    }

    fun pasteFromClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (pasted.isNullOrBlank()) Toast.makeText(context, "Nothing to paste", Toast.LENGTH_SHORT).show() else text = pasted
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Import from Spotify", onBack = onBack) }
        item {
            Text(
                "Paste a link to a public playlist, an album or a song, or share one to OpenTune from the Spotify app. No sign-in needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        item {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("open.spotify.com/playlist/…") },
                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                trailingIcon = {
                    if (text.isEmpty()) IconButton(onClick = ::pasteFromClipboard) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                    else IconButton(onClick = { text = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                },
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        progress?.let { p -> item { ProgressCard(p, actions, onOpenPlaylist) } }

        when (val p = preview) {
            Preview.Empty -> item {
                FilledTonalButton(onClick = ::pasteFromClipboard, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Paste link")
                }
            }
            Preview.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is Preview.Failed -> item {
                Text(p.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            is Preview.Ready -> {
                val c = p.collection
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(c.imageUrl, Modifier.size(112.dp), RoundedCornerShape(16.dp), placeholder = Icons.AutoMirrored.Rounded.QueueMusic)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                when (c.link.kind) {
                                    Spotify.Kind.PLAYLIST -> "Playlist"
                                    Spotify.Kind.ALBUM -> "Album"
                                    Spotify.Kind.TRACK -> "Song"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (c.subtitle.isNotBlank()) Text(c.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.link.kind != Spotify.Kind.TRACK) Text("${c.tracks.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (c.maybeTruncated) {
                    item {
                        Text(
                            "Spotify shows only the first ${Spotify.EMBED_LIMIT} songs of a playlist without signing in, so longer playlists come over in part.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (c.link.kind == Spotify.Kind.TRACK) {
                            Button(
                                enabled = !playing,
                                onClick = {
                                    playing = true
                                    scope.launch {
                                        val song = runCatching { SpotifyImport.match(c.tracks.first()) }.getOrNull()
                                        playing = false
                                        if (song != null) actions.playAll(listOf(song), 0, false, "From Spotify")
                                        else Toast.makeText(context, "Couldn't find this song on YouTube Music", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (playing) "Finding it…" else "Play") }
                        } else {
                            Button(
                                enabled = !SpotifyImport.running && c.tracks.isNotEmpty(),
                                onClick = { SpotifyImport.start(c.name) { c.tracks } },
                                modifier = Modifier.weight(1f),
                            ) { Text("Import as playlist") }
                        }
                    }
                }
                if (c.link.kind != Spotify.Kind.TRACK) {
                    items(c.tracks.take(200)) { t ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artists.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(p: SpotifyImport.Progress, actions: SongActions, onOpenPlaylist: (String) -> Unit) {
    var showMissed by remember { mutableStateOf(false) }
    val playlists by com.opentune.data.library.LibraryStore.playlists.collectAsState()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                p.error != null -> Text(p.error, color = MaterialTheme.colorScheme.error)
                p.total == 0 && !p.finished -> {
                    Text("Getting started…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                !p.finished -> {
                    Text("Finding songs on YouTube Music: ${p.done} of ${p.total}")
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                }
                else -> Text("Found ${p.matched} of ${p.total} songs. Saved as a playlist in Library.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!p.finished) TextButton(onClick = SpotifyImport::cancel) { Text("Cancel") }
                p.playlistId?.let { id ->
                    TextButton(onClick = {
                        playlists.firstOrNull { it.id == id }?.songs?.map { it.toSong() }?.takeIf { it.isNotEmpty() }
                            ?.let { actions.playAll(it, 0, false, p.name) }
                    }) { Text("Play") }
                    TextButton(onClick = { onOpenPlaylist(id) }) { Text("Open") }
                }
                if (p.missed.isNotEmpty()) TextButton(onClick = { showMissed = !showMissed }) { Text(if (showMissed) "Hide missed" else "${p.missed.size} not found") }
                if (p.finished) TextButton(onClick = SpotifyImport::dismiss) { Text("Close") }
            }
            if (showMissed) {
                p.missed.take(200).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
