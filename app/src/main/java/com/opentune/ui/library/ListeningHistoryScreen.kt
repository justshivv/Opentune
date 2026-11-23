package com.opentune.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.history.History
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SongListItem

/**
 * Every stored listen in time order, including repeat listens; no 30-song
 * preview limit. One merged list: this app's own listens and the ones
 * synced in from the account (YouTube Music and any other app connected
 * to it) live in the same store, so the page shows them together — the
 * same merged history Recents and Replay draw from.
 */
@Composable
fun ListeningHistoryScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val records by History.records.collectAsState()
    // Each song once, at its latest play — like the account's own history —
    // instead of a row for every single play.
    // Sorted over the whole history, so off the main thread, and kept as Home keeps its rows (see
    // HomeDerived): coming back to the page has it at once, scrolled where it was. Null only the
    // first time, while it's worked out.
    val sorted = com.opentune.ui.home.rememberHomeDerived("history:ordered", records, initial = null as List<com.opentune.data.history.PlayRecord>?) {
        records.sortedByDescending { it.playedAt }.distinctBy { it.videoId }
    }
    val ordered = sorted.orEmpty()
    val songs = remember(ordered) { ordered.map { it.toSong() } }
    // One formatter for the page, not a new one for each row as it scrolls into view.
    val date = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { PageHeader("Listening history", onBack = onBack) }
        if (sorted != null && songs.isEmpty()) item { Text("Your listening history will appear here.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
        // Each song is listed once, so it is its own key: with the row's place in it, every key
        // changed when a new listen came in at the top, and the list lost its rows' places.
        itemsIndexed(ordered, key = { _, record -> record.videoId }) { i, record ->
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
