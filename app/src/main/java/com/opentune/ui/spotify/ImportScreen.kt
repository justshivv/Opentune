package com.opentune.ui.spotify

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
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
import androidx.compose.material3.OutlinedButton
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
import androidx.core.net.toUri
import com.opentune.data.spotify.PlaylistCsv
import com.opentune.data.spotify.ExportedPlaylists
import com.opentune.data.spotify.PlaylistImport
import com.opentune.data.spotify.Spotify
import com.opentune.data.spotify.YouTubePlaylists
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.PageHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something ready to bring over, whatever it came from. */
private sealed interface Found {
    val name: String
    val subtitle: String
    val imageUrl: String?
    val label: String
    val count: Int

    data class FromSpotify(val c: Spotify.Collection) : Found {
        override val name get() = c.name
        override val subtitle get() = c.subtitle
        override val imageUrl get() = c.imageUrl
        override val label get() = when (c.link.kind) {
            Spotify.Kind.PLAYLIST -> "Spotify playlist"
            Spotify.Kind.ALBUM -> "Spotify album"
            Spotify.Kind.TRACK -> "Spotify song"
        }
        override val count get() = c.tracks.size
    }

    data class FromYouTube(val p: YouTubePlaylists.Playlist) : Found {
        override val name get() = p.name
        override val subtitle get() = p.subtitle
        override val imageUrl get() = p.thumbnailUrl
        override val label get() = "YouTube Music playlist"
        override val count get() = p.songs.size
    }

    data class FromBundle(val playlists: List<ExportedPlaylists.Exported>) : Found {
        override val name get() = "${playlists.size} playlists"
        override val subtitle get() = "From Exportify"
        override val imageUrl: String? get() = null
        override val label get() = "Exported playlists"
        override val count get() = playlists.sumOf { it.tracks.size }
    }

    data class FromCsv(override val name: String, val tracks: List<Spotify.Track>) : Found {
        override val subtitle get() = "CSV file"
        override val imageUrl: String? get() = null
        override val label get() = "Exported playlist"
        override val count get() = tracks.size
    }
}

private sealed interface Preview {
    data object Empty : Preview
    data class Loading(val songs: Int = 0) : Preview
    data class Ready(val found: Found) : Preview
    data class Failed(val message: String) : Preview
}

/**
 * Bring a playlist over: a Spotify playlist, album or song link, a YouTube
 * Music playlist link, or a CSV export (Exportify and the like, for whole
 * Spotify playlists and Liked Songs). Paste a link, share one to OpenTune,
 * or pick a file; the result is a playlist on the phone.
 */
@Composable
fun ImportScreen(
    contentPadding: PaddingValues,
    actions: SongActions,
    initialLink: String?,
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenExportify: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by PlaylistImport.progress.collectAsState()
    var text by rememberSaveable { mutableStateOf(initialLink.orEmpty()) }
    var preview by remember { mutableStateOf<Preview>(Preview.Empty) }
    var playing by remember { mutableStateOf(false) }

    // Coming back from the in-app Exportify page with what it caught.
    val pending by ExportedPlaylists.pending.collectAsState()
    LaunchedEffect(pending) {
        val caught = ExportedPlaylists.take() ?: return@LaunchedEffect
        text = ""
        preview = Preview.Ready(if (caught.size == 1) Found.FromCsv(caught.first().name, caught.first().tracks) else Found.FromBundle(caught))
    }

    LaunchedEffect(text) {
        val t = text.trim()
        if (t.isEmpty()) {
            val kept = (preview as? Preview.Ready)?.found
            if (kept !is Found.FromCsv && kept !is Found.FromBundle) preview = Preview.Empty
            return@LaunchedEffect
        }
        val youtube = YouTubePlaylists.parse(t)
        if (youtube == null && !Spotify.looksLikeSpotify(t)) {
            preview = Preview.Failed("Paste a Spotify playlist, album or song link, or a YouTube Music playlist link.")
            return@LaunchedEffect
        }
        preview = Preview.Loading()
        preview = try {
            if (youtube != null) {
                Preview.Ready(Found.FromYouTube(YouTubePlaylists.load(youtube) { n -> preview = Preview.Loading(n) }))
            } else {
                val link = Spotify.resolve(t) ?: throw java.io.IOException("Couldn't open that link.")
                Preview.Ready(Found.FromSpotify(Spotify.load(link)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Preview.Failed(e.message ?: "Couldn't load it.")
        }
    }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            preview = Preview.Loading()
            preview = runCatching {
                val (name, body) = withContext(Dispatchers.IO) { fileName(context, uri) to context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                val tracks = PlaylistCsv.parse(body)
                if (tracks.isEmpty()) error("No songs found in that file. It needs track name and artist columns.")
                text = ""
                Preview.Ready(Found.FromCsv(name.substringBeforeLast('.').ifBlank { "Imported playlist" }, tracks))
            }.getOrElse { Preview.Failed(it.message ?: "Couldn't read that file.") }
        }
    }

    fun pasteFromClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (pasted.isNullOrBlank()) Toast.makeText(context, "Nothing to paste", Toast.LENGTH_SHORT).show() else text = pasted
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Import a playlist", onBack = onBack) }
        item {
            Text(
                "Paste a Spotify or YouTube Music link, share one to OpenTune, or pick a CSV export. Songs play from YouTube Music. No sign-in needed.",
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
                placeholder = { Text("Playlist, album or song link") },
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
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = ::pasteFromClipboard) {
                    Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Paste link")
                }
                OutlinedButton(onClick = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*")) }) {
                    Icon(Icons.Rounded.Description, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("CSV file")
                }
            }
        }
        item {
            // Exportify inside the app: sign in with Spotify there, tap Export, and the file comes straight here.
            Button(onClick = onOpenExportify, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export from Spotify (whole playlists, Liked Songs)")
            }
        }
        progress?.let { p -> item { ProgressCard(p, actions, onOpenPlaylist) } }

        when (val p = preview) {
            Preview.Empty -> item { CsvHelp(onOpenExportify) }
            is Preview.Loading -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (p.songs > 0) Text("${p.songs} songs so far…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            is Preview.Failed -> item {
                Text(p.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            is Preview.Ready -> {
                val f = p.found
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(f.imageUrl, Modifier.size(112.dp), RoundedCornerShape(16.dp), placeholder = Icons.AutoMirrored.Rounded.QueueMusic)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(f.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (f.subtitle.isNotBlank()) Text(f.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${f.count} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (f is Found.FromSpotify && f.c.maybeTruncated) item { FullPlaylistNote(onOpenExportify) }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when {
                            f is Found.FromSpotify && f.c.link.kind == Spotify.Kind.TRACK -> Button(
                                enabled = !playing,
                                onClick = {
                                    playing = true
                                    scope.launch {
                                        val song = runCatching { PlaylistImport.match(f.c.tracks.first()) }.getOrNull()
                                        playing = false
                                        if (song != null) actions.playAll(listOf(song), 0, false, "From Spotify")
                                        else Toast.makeText(context, "Couldn't find this song on YouTube Music", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (playing) "Finding it…" else "Play") }
                            f is Found.FromYouTube -> {
                                Button(onClick = { PlaylistImport.saveSongs(f.p.name, f.p.songs) }, enabled = !PlaylistImport.running, modifier = Modifier.weight(1f)) { Text("Save as playlist") }
                                FilledTonalButton(onClick = { actions.playAll(f.p.songs, 0, false, f.p.name) }, modifier = Modifier.weight(1f)) { Text("Play") }
                            }
                            f is Found.FromSpotify -> Button(
                                enabled = !PlaylistImport.running && f.count > 0,
                                onClick = { PlaylistImport.start(f.c.name) { f.c.tracks } },
                                modifier = Modifier.weight(1f),
                            ) { Text("Import as playlist") }
                            f is Found.FromBundle -> Button(
                                enabled = !PlaylistImport.running,
                                onClick = { PlaylistImport.startAll(f.playlists.map { e -> e.name to suspend { e.tracks } }) },
                                modifier = Modifier.weight(1f),
                            ) { Text("Import all ${f.playlists.size}") }
                            f is Found.FromCsv -> Button(
                                enabled = !PlaylistImport.running,
                                onClick = { PlaylistImport.start(f.name) { f.tracks } },
                                modifier = Modifier.weight(1f),
                            ) { Text("Import as playlist") }
                        }
                    }
                }
                val rows: List<Pair<String, String>> = when (f) {
                    is Found.FromSpotify -> if (f.c.link.kind == Spotify.Kind.TRACK) emptyList() else f.c.tracks.map { it.title to it.artists.joinToString() }
                    is Found.FromYouTube -> f.p.songs.map { it.title to it.artist }
                    is Found.FromCsv -> f.tracks.map { it.title to it.artists.joinToString() }
                    is Found.FromBundle -> f.playlists.map { it.name to "${it.tracks.size} songs" }
                }
                items(rows.take(300)) { (title, artist) ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rows.size > 300) item {
                    Text("…and ${rows.size - 300} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
}

/** How to bring a whole Spotify playlist, or Liked Songs, over as a CSV. */
@Composable
private fun CsvHelp(onOpenExportify: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Big playlists and Liked Songs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "A Spotify link brings up to the first ${Spotify.EMBED_LIMIT} songs of a playlist, which is all Spotify shows without signing in. " +
                    "For every song, or your Liked Songs, tap Export from Spotify: Exportify opens here, you sign in with Spotify and tap Export, and the playlist comes straight back ready to import.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenExportify) { Text("Export from Spotify") }
        }
    }
}

@Composable
private fun FullPlaylistNote(onOpenExportify: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "This shows the first ${Spotify.EMBED_LIMIT} songs, Spotify's limit without signing in. Export from Spotify brings every song.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onOpenExportify) { Text("Export from Spotify") }
        }
    }
}

@Composable
private fun ProgressCard(p: PlaylistImport.Progress, actions: SongActions, onOpenPlaylist: (String) -> Unit) {
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
                p.matched == p.total -> Text("Saved all ${p.total} songs as a playlist in Library.")
                else -> Text("Found ${p.matched} of ${p.total} songs. Saved as a playlist in Library.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!p.finished) TextButton(onClick = PlaylistImport::cancel) { Text("Cancel") }
                p.playlistId?.let { id ->
                    TextButton(onClick = {
                        playlists.firstOrNull { it.id == id }?.songs?.map { it.toSong() }?.takeIf { it.isNotEmpty() }
                            ?.let { actions.playAll(it, 0, false, p.name) }
                    }) { Text("Play") }
                    TextButton(onClick = { onOpenPlaylist(id) }) { Text("Open") }
                }
                if (p.missed.isNotEmpty()) TextButton(onClick = { showMissed = !showMissed }) { Text(if (showMissed) "Hide missed" else "${p.missed.size} not found") }
                if (p.finished) TextButton(onClick = PlaylistImport::dismiss) { Text("Close") }
            }
            if (showMissed) {
                p.missed.take(200).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** A picked file's own name, for the playlist's name. */
private fun fileName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()

