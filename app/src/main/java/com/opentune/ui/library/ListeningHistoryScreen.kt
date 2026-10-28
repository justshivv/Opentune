package com.opentune.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.history.History
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SongListItem

/** Every stored listen in time order, including repeat listens; no 30-song preview limit. */
@Composable
fun ListeningHistoryScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val records by History.records.collectAsState()
    val ordered = remember(records) { records.sortedByDescending { it.playedAt } }
    val songs = remember(ordered) { ordered.map { it.toSong() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { PageHeader("Listening history", onBack = onBack) }
        if (songs.isEmpty()) item { Text("Your listening history will appear here.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
        itemsIndexed(ordered, key = { i, record -> "$i:${record.playedAt}:${record.videoId}" }) { i, record ->
            val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            val day = date.format(java.util.Date(record.playedAt))
            if (i == 0 || day != date.format(java.util.Date(ordered[i - 1].playedAt))) {
                Text(day, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            }
            val song = songs[i]
            SongListItem(song, onClick = { actions.playAll(songs, i, false, "Listening history") },
                isCurrent = song.videoId == actions.currentVideoId, isPlaying = actions.isPlaying,
                onPlayNext = { actions.playNext(song) }, onAddToQueue = { actions.addToQueue(song) })
        }
    }
}
