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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.runtime.rememberCoroutineScope
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.launch

/**
 * "Add to playlist" for [song], in the web player's menu: a new playlist, then each of yours,
 * those it's already in marked and out of reach, then (signed in) the account's own playlists on
 * YouTube Music. It opens [at] a point inside the element it belongs to (where a right click
 * was), or under that element's right edge.
 */
@Composable
internal fun AddToPlaylistMenu(song: Song, open: Boolean, at: IntOffset? = null, withQueue: Boolean = false, onDismiss: () -> Unit) {
    val playlists by Library.playlists.collectAsState()
    val account by AccountLibrary.state.collectAsState()
    // Only a playlist that can take an edit: not an album's, a mix or Liked Music.
    val onAccount = remember(account.playlists) {
        AccountLibrary.shownPlaylists(account.playlists).filter { it.browseId?.let(InnertubeParser::isEditablePlaylist) == true }
    }
    val youTube = com.opentune.data.isYouTubeId(song.videoId)
    var naming by remember { mutableStateOf(false) }
    if (open) {
        WebMenu(at?.let { MenuAnchor.At(it) } ?: MenuAnchor.BelowEnd, onDismiss = onDismiss, label = "Add ${song.title} to a playlist") {
            // From a track row's + button: the queue first, then the playlists.
            if (withQueue) {
                WebMenuItem("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { Player.playNext(listOf(song)); Notice.show("Playing next") }
                WebMenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { Player.addToQueue(listOf(song)); Notice.show("Added to queue") }
                WebMenuSeparator()
            }
            WebMenuItem("New playlist…", Icons.Rounded.Add) { naming = true }
            if (playlists.isNotEmpty()) {
                WebMenuSeparator()
                WebMenuHeader(if (account.signedIn && youTube && onAccount.isNotEmpty()) "On this computer" else "Add to")
            }
            playlists.forEach { p ->
                val has = p.songs.any { it.id == song.videoId }
                WebMenuItem(p.name, Icons.AutoMirrored.Rounded.QueueMusic, enabled = !has, trailing = if (has) "Added" else null) {
                    Library.addToPlaylist(p.id, song)
                    Notice.show("Added to ${p.name}")
                }
            }
            // A song from your own server or a file can't go on a YouTube Music playlist.
            if (account.signedIn && youTube && onAccount.isNotEmpty()) {
                WebMenuSeparator()
                WebMenuHeader("YouTube Music")
                onAccount.forEach { p -> WebMenuItem(p.title, Icons.AutoMirrored.Rounded.QueueMusic) { AccountLibrary.addToPlaylist(p, song) } }
            }
        }
    }
    if (naming) NewPlaylistDialog(first = song, onDismiss = { naming = false }) { made ->
        naming = false
        Notice.show(if (made is MadePlaylist.OnAccount) "Added to ${made.item.title} on YouTube Music" else "Added to the new playlist")
    }
}

/** Asks for a playlist's name, in the pages' own dialog. */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) =
    PageNameDialog(title, initial, confirm, onDismiss, onDone)

/** What a new playlist became: one kept on this computer, or one on the account. */
internal sealed interface MadePlaylist {
    data class Local(val id: String) : MadePlaylist
    data class OnAccount(val item: ShelfItem) : MadePlaylist
}

/**
 * A new playlist, as the phone makes one: its name, and who can see it (Private, Unlisted or
 * Public). Signed in, it's made on the account with that choice, [first] in it when given;
 * signed out it stays on this computer, where the choice doesn't apply, so it's shown greyed
 * with a line saying why.
 */
@Composable
internal fun NewPlaylistDialog(first: Song? = null, onDismiss: () -> Unit, onMade: (MadePlaylist) -> Unit) {
    val account by AccountLibrary.state.collectAsState()
    // A song YouTube Music doesn't have (your server's, a file) can only go on one made here.
    val onAccount = account.signedIn && (first == null || com.opentune.data.isYouTubeId(first.videoId))
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    PagePlaylistDialog(
        "New playlist", "", "Create",
        privacyOn = onAccount,
        note = when {
            onAccount -> null
            account.signedIn -> "This song isn't on YouTube Music, so the playlist stays on this computer."
            else -> "Sign in to YouTube Music to make it on your account and choose who sees it. Signed out, it stays on this computer."
        },
        busy = busy,
        onDismiss = { if (!busy) onDismiss() },
    ) { name, privacy ->
        if (onAccount) {
            busy = true
            scope.launch {
                runCatching { AccountLibrary.createPlaylist(name, privacy, listOfNotNull(first)) }
                    .onSuccess { onMade(MadePlaylist.OnAccount(it)) }
                    .onFailure { busy = false; Notice.show("Couldn't make the playlist on YouTube Music") }
            }
        } else {
            val id = Library.createPlaylist(name)
            first?.let { Library.addToPlaylist(id, it) }
            onMade(MadePlaylist.Local(id))
        }
    }
}

/**
 * One of your playlists: its head (kept on this computer), Shuffle, Play, search in it, and the
 * ⋯ menu, which also renames and deletes it and, signed in, saves a copy to YouTube Music; then
 * its songs, each with a button to take it out.
 */
@Composable
internal fun PlaylistPage(id: String, onBack: () -> Unit) {
    val playlists by Library.playlists.collectAsState()
    val account by AccountLibrary.state.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        // Deleted, here or in another window: back to the list.
        LaunchedEffect(id) { onBack() }
        return
    }
    val songs = remember(playlist) { playlist.songs.map { it.toSong() } }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CollectionLayout(playlist.name, playlist.covers.firstOrNull(), songs, failed = false, onRemove = { Library.removeFromPlaylist(id, it.videoId) },
        empty = "Add songs from search, Home or the player with the + on any song.") {
        CollectionHero(
            cover = { m -> Mosaic(playlist.covers, m, RoundedCornerShape(0.dp)) },
            coverUrl = playlist.covers.firstOrNull(), kind = "Playlist", title = playlist.name, subtitle = "On this computer", songs = songs,
        ) {
            ListMoreButton(songs, link = null) {
                WebMenuItem("Rename…", Icons.Rounded.Edit) { renaming = true }
                if (account.signedIn) WebMenuItem("Save to YouTube Music…", Icons.Rounded.CloudUpload) { copying = true }
                WebMenuItem("Delete playlist…", Icons.Rounded.Delete) { deleting = true }
            }
        }
    }
    if (renaming) {
        NameDialog("Rename playlist", playlist.name, "Rename", onDismiss = { renaming = false }) { name ->
            Library.renamePlaylist(id, name)
            renaming = false
        }
    }
    if (copying) {
        // A copy on the account, with who can see it: the songs YouTube Music has go with it.
        PagePlaylistDialog("Save to YouTube Music", playlist.name, "Save", privacyOn = true, busy = busy, onDismiss = { if (!busy) copying = false }) { name, privacy ->
            busy = true
            scope.launch {
                runCatching { AccountLibrary.createPlaylist(name, privacy, songs) }
                    .onSuccess { copying = false; busy = false; Notice.show("Saved $name to YouTube Music") }
                    .onFailure { busy = false; Notice.show("Couldn't save it to YouTube Music") }
            }
        }
    }
    if (deleting) {
        PageConfirmDialog(
            "Delete ${playlist.name}?", "The playlist goes; the songs stay wherever else you keep them.", "Delete",
            onDismiss = { deleting = false },
        ) { deleting = false; Library.deletePlaylist(id); onBack() }
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

/**
 * Four covers in a square, as the phone draws a playlist made in the app; one fills it, none
 * leaves it plain. [shape] rounds it; a card that clips its art already passes a square one.
 */
@Composable
internal fun Mosaic(covers: List<String>, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Web.ArtMedium)) {
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
