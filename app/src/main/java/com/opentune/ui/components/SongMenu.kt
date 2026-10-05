package com.opentune.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.library.LibraryStore
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt

/** What a song's menu can do beyond the library, wired up by the app shell. */
class SongMenuActions(
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    val startRadio: (Song) -> Unit,
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
    /** Marks a song as disliked; the shell also skips it if it's playing. */
    val dislike: (Song) -> Unit = LibraryStore::dislike,
    /** Plays the music video's audio in place of the song. */
    val playVideoVersion: ((Song) -> Unit)? = null,
    /** Opens the song's artist, finding the page when the row didn't say which. */
    val viewArtist: ((Song) -> Unit)? = null,
)

val LocalSongMenu = staticCompositionLocalOf<SongMenuActions?> { null }

private typealias MenuRows = @Composable ColumnScope.(close: () -> Unit) -> Unit

/**
 * The long-press and "…" menu for one song, in the same order everywhere.
 * The player adds its own rows: [top] (Upgrade quality), [tools] (sleep
 * timer, lyrics offset) before Share, and [end] (Copy log) after it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(
    song: Song,
    onDismiss: () -> Unit,
    top: MenuRows? = null,
    tools: MenuRows? = null,
    end: MenuRows? = null,
) {
    val actions = LocalSongMenu.current
    val context = LocalContext.current
    val liked by LibraryStore.liked.collectAsState()
    val downloads by Downloads.entries.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val download = downloads[song.videoId]
    val local = LocalMusic.isLocal(song.videoId)
    var pickPlaylist by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = onDismiss

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(56.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // The four things done most often, one tap each.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Liked" else "Like", active = isLiked) {
                    LibraryStore.setLiked(song, !isLiked)
                }
                if (!local) {
                    val (icon, label) = when (download?.state) {
                        DownloadState.DONE -> Icons.Rounded.DownloadDone to "Saved"
                        DownloadState.QUEUED, DownloadState.DOWNLOADING -> Icons.Rounded.Download to "${(download.progress * 100).toInt()}%"
                        DownloadState.FAILED -> Icons.Rounded.Download to "Retry"
                        null -> Icons.Rounded.Download to "Download"
                    }
                    QuickAction(icon, label, active = download?.state == DownloadState.DONE) {
                        when (download?.state) {
                            DownloadState.DONE, DownloadState.QUEUED, DownloadState.DOWNLOADING -> Downloads.remove(context, song.videoId)
                            DownloadState.FAILED -> Downloads.retry(context, song.videoId)
                            null -> Downloads.enqueue(context, song)
                        }
                    }
                }
                QuickAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", active = false) { pickPlaylist = true }
                if (!local) {
                    QuickAction(Icons.Rounded.Share, "Share", active = false) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.videoId}")
                        context.startActivity(Intent.createChooser(send, null))
                        close()
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            if (actions != null) {
                MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { actions.playNext(song); close() }
                MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { actions.addToQueue(song); close() }
                if (!local) MenuRow(Icons.Rounded.Radio, "Start radio") { actions.startRadio(song); close() }
                when {
                    actions.viewArtist != null && !local -> MenuRow(Icons.Rounded.Person, "View artist") { close(); actions.viewArtist.invoke(song) }
                    song.artistId != null -> MenuRow(Icons.Rounded.Person, "View artist") { close(); actions.openArtist(song.artistId) }
                }
                song.albumId?.let { id -> MenuRow(Icons.Rounded.Album, "Open album") { close(); actions.openAlbum(id) } }
            }
            val videoVersion = actions?.playVideoVersion?.takeIf { !local && !song.isVideo }
            if (top != null || videoVersion != null || tools != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                top?.invoke(this, close)
                videoVersion?.let { play -> MenuRow(Icons.Rounded.Videocam, "Convert to video") { close(); play(song) } }
                tools?.invoke(this, close)
            }
            if (actions != null) MenuRow(Icons.Rounded.ThumbDownOffAlt, "Not for me (dislike)") { close(); actions.dislike(song) }
            end?.invoke(this, close)
            Spacer(Modifier.size(12.dp))
        }
    }
    if (pickPlaylist) AddToPlaylistDialog(song, onDone = { pickPlaylist = false; close() }, onDismiss = { pickPlaylist = false })
}

/** A round quick action with its label underneath; filled when [active]. */
@Composable
private fun QuickAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(18.dp))
                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Picks one of the playlists made in the app, or makes a new one with [song] in it. */
@Composable
fun AddToPlaylistDialog(song: Song, onDone: () -> Unit, onDismiss: () -> Unit) {
    val playlists by LibraryStore.playlists.collectAsState()
    var naming by remember { mutableStateOf(playlists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (naming) "New playlist" else "Add to playlist") },
        text = {
            if (naming) {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") })
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { MenuRow(Icons.Rounded.Add, "New playlist") { naming = true } }
                    items(playlists, key = { it.id }) { p ->
                        MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "${p.name} · ${p.songs.size}") {
                            LibraryStore.addToPlaylist(p.id, song)
                            onDone()
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (naming) {
                TextButton(onClick = { LibraryStore.createPlaylist(name, song); onDone() }) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
