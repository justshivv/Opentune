package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.opentune.data.model.Song

/** "Add to playlist" for [song]: each playlist, and a new one named after the song. */
@Composable
internal fun AddToPlaylistMenu(song: Song, open: Boolean, onDismiss: () -> Unit) {
    val playlists by Library.playlists.collectAsState()
    var naming by remember { mutableStateOf(false) }
    DropdownMenu(open, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("New playlist") },
            leadingIcon = { Icon(Icons.Rounded.Add, null) },
            onClick = { onDismiss(); naming = true },
        )
        if (playlists.isNotEmpty()) HorizontalDivider()
        playlists.forEach { p ->
            val has = p.songs.any { it.id == song.videoId }
            DropdownMenuItem(
                text = { Text(if (has) "${p.name} (added)" else p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                enabled = !has,
                onClick = { Library.addToPlaylist(p.id, song); onDismiss() },
            )
        }
    }
    if (naming) {
        NameDialog("New playlist", "", "Create", onDismiss = { naming = false }) { name ->
            Library.addToPlaylist(Library.createPlaylist(name), song)
            naming = false
        }
    }
}

/** Asks for a playlist's name. */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Your playlists as tiles, with a tile to start a new one. */
@Composable
internal fun PlaylistGrid(onOpen: (String) -> Unit) {
    val playlists by Library.playlists.collectAsState()
    var naming by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = LocalPageBottom.current),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.clip(MaterialTheme.shapes.medium).clickable { naming = true }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) }
                Text("New playlist", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
        items(playlists, key = { it.id }) { p ->
            Column(Modifier.clip(MaterialTheme.shapes.medium).clickable { onOpen(p.id) }) {
                Mosaic(p.covers, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(
                    if (p.songs.size == 1) "1 song" else "${p.songs.size} songs",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (naming) {
        NameDialog("New playlist", "", "Create", onDismiss = { naming = false }) { name ->
            onOpen(Library.createPlaylist(name))
            naming = false
        }
    }
}

/** One playlist: play, shuffle, rename, delete, and its songs, each removable. */
@Composable
internal fun PlaylistPage(id: String, onBack: () -> Unit) {
    val playlists by Library.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        // Deleted, here or in another window: back to the list.
        LaunchedEffect(id) { onBack() }
        return
    }
    val songs = remember(playlist) { playlist.songs.map { it.toSong() } }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    CollectionLayout(playlist.name, playlist.covers.firstOrNull(), songs, failed = false, onRemove = { Library.removeFromPlaylist(id, it.videoId) },
        empty = "No songs yet. Add songs from search, Home or the player with the playlist button.") {
        CollectionHero(
            cover = { m -> Mosaic(playlist.covers, m) },
            coverUrl = playlist.covers.firstOrNull(), kind = "Playlist", title = playlist.name, subtitle = "Made on this computer", songs = songs,
        ) {
            HoverIcon(Icons.Rounded.Edit, "Rename", size = 42.dp, iconSize = 20.dp) { renaming = true }
            HoverIcon(Icons.Rounded.Delete, "Delete playlist", size = 42.dp, iconSize = 20.dp) { deleting = true }
        }
    }
    if (renaming) {
        NameDialog("Rename playlist", playlist.name, "Rename", onDismiss = { renaming = false }) { name ->
            Library.renamePlaylist(id, name)
            renaming = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${playlist.name}?") },
            text = { Text("The playlist goes; the songs stay wherever else you keep them.") },
            confirmButton = { TextButton(onClick = { deleting = false; Library.deletePlaylist(id); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** The accent-coloured button a collection page starts with. */
@Composable
internal fun PillButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClick)
            .padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
        Text(label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Four covers in a square, as the phone draws a playlist made in the app; one fills it, none leaves it plain. */
@Composable
internal fun Mosaic(covers: List<String>, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        when {
            covers.isEmpty() -> Icon(
                Icons.AutoMirrored.Rounded.QueueMusic, null,
                Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            covers.size < 4 -> AsyncImage(covers.first(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Column(Modifier.fillMaxSize()) {
                for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0..1) AsyncImage(covers[row * 2 + col], null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}
