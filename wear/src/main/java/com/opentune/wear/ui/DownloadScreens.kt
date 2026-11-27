package com.opentune.wear.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.model.Song
import com.opentune.wear.Message
import com.opentune.wear.Place
import com.opentune.wear.WatchDownloads
import com.opentune.wear.WatchWifi

/** How a song's download stands, in a few words. */
object DownloadText {
    fun of(entry: DownloadEntry?, wifi: Boolean): String? = when (entry?.state) {
        null -> null
        DownloadState.DONE -> "On the watch"
        DownloadState.DOWNLOADING -> "Downloading ${(entry.progress * 100).toInt()}%"
        DownloadState.QUEUED -> if (wifi) "Waiting to download" else "Waiting for Wi-Fi"
        DownloadState.FAILED -> entry.error?.takeIf { it.isNotBlank() && it.length < 60 } ?: "Couldn't download"
    }
}

/** "Download" for [song], or what its download is doing; tapping again stops or removes it. */
@Composable
fun DownloadPlace(song: Song) {
    val entries by Downloads.entries.collectAsState()
    val wifi by WatchWifi.network.collectAsState()
    val entry = entries[song.videoId]
    val label = when (entry?.state) {
        DownloadState.DONE -> "Remove from watch"
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> "Stop download"
        else -> "Download"
    }
    val icon = when (entry?.state) {
        DownloadState.DONE -> Icons.Rounded.Delete
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> Icons.Rounded.Downloading
        else -> Icons.Rounded.Download
    }
    Place(label, icon, secondary = DownloadText.of(entry, wifi != null)) {
        when (entry?.state) {
            DownloadState.DONE, DownloadState.QUEUED, DownloadState.DOWNLOADING -> WatchDownloads.remove(song.videoId)
            else -> WatchDownloads.download(song)
        }
    }
}

/** The songs on the watch, to play with no connection, and what's still coming. */
@Composable
fun Downloaded(onPlay: (List<Song>, Int) -> Unit) {
    val entries by Downloads.entries.collectAsState()
    val wifi by WatchWifi.network.collectAsState()
    val summary = remember(entries) { WatchDownloads.summary(entries) }
    val songs = remember(summary) { summary.done.map { it.song.toSong() } }
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Downloaded") } }
            item {
                Message(
                    if (summary.done.isEmpty() && summary.going.isEmpty()) "Nothing on the watch yet. Download a song from the player's More, or send one from your phone."
                    else "${songs.size} ${if (songs.size == 1) "song" else "songs"} · ${WatchDownloads.size(summary.bytes)}",
                )
            }
            if (summary.going.isNotEmpty()) {
                item { Message(if (wifi != null) "Downloading ${summary.going.size} over Wi-Fi" else "${summary.going.size} waiting for Wi-Fi") }
                summary.going.forEach { e -> item { EntryRow(e, wifi != null) { WatchDownloads.remove(e.song.videoId) } } }
            }
            summary.failed.forEach { e -> item { EntryRow(e, wifi != null) { WatchDownloads.download(e.song.toSong()) } } }
            songs.forEachIndexed { i, s ->
                item { EntryRow(summary.done[i], wifi != null, onLongClick = { WatchDownloads.remove(s.videoId) }) { onPlay(songs, i) } }
            }
            if (songs.isNotEmpty()) {
                item { Message("Hold a song to remove it.") }
                item { Place("Remove all", Icons.Rounded.Delete) { WatchDownloads.removeAll() } }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: DownloadEntry, wifi: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val done = entry.state == DownloadState.DONE
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = {
            AsyncImage(
                model = entry.artPath ?: entry.song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ButtonDefaults.LargeIconSize).clip(CircleShape),
            )
        },
        secondaryLabel = {
            Text(if (done) entry.song.artist else DownloadText.of(entry, wifi).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        label = { Text(entry.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** A small mark by a song that's on the watch, or on its way. */
@Composable
fun DownloadMark(videoId: String) {
    val entries by Downloads.entries.collectAsState()
    val icon = when (entries[videoId]?.state) {
        DownloadState.DONE -> Icons.Rounded.DownloadDone
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> Icons.Rounded.Downloading
        DownloadState.FAILED -> Icons.Rounded.ErrorOutline
        null -> return
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.primary)
}

/** A song's artist with its download mark in front. */
@Composable
fun ArtistWithMark(song: Song) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DownloadMark(song.videoId)
        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
