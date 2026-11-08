package com.opentune.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.opentune.ui.theme.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.history.History
import com.opentune.data.innertube.InnertubeParser
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SongListItem

/**
 * Every stored listen in time order, including repeat listens; no 30-song
 * preview limit. The Remote tab is the signed-in account's history on
 * YouTube Music itself, read live when it's opened: nothing is stored
 * here, so a listen the app already has is never counted twice.
 */
@Composable
fun ListeningHistoryScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    var remote by remember { mutableStateOf(false) }
    val signedIn by AccountStore.signedIn.collectAsState()
    // The account's history as YouTube Music has it; null while it loads,
    // and kept once read, so flipping tabs doesn't read it again.
    var remotePage by remember { mutableStateOf<InnertubeParser.HistoryPage?>(null) }
    var remoteFailed by remember { mutableStateOf(false) }
    LaunchedEffect(remote, signedIn) {
        if (remote && signedIn && remotePage == null && !remoteFailed) {
            try {
                remotePage = MusicRepository.accountHistory()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                remoteFailed = true
            }
        }
    }
    val records by History.records.collectAsState()
    val ordered = remember(records) { records.sortedByDescending { it.playedAt } }
    val songs = remember(ordered) { ordered.map { it.toSong() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { PageHeader("Listening history", onBack = onBack) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                SegmentedButton(!remote, { remote = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Local") }
                SegmentedButton(remote, { remote = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Remote") }
            }
        }
        if (!remote) {
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
        } else if (!signedIn) {
            item { Text("Sign in with your YouTube account to see the history it keeps.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
        } else if (remoteFailed) {
            item { Text("Couldn't load your YouTube history.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
        } else {
            val page = remotePage
            if (page == null) {
                item { Text("Loading…", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
            } else if (page.sections.all { it.songs.isEmpty() }) {
                item { Text("Nothing here yet — what you play on YouTube Music will show up here.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
            } else {
                val flat = page.sections.flatMap { it.songs }
                var start = 0
                for (section in page.sections) {
                    if (section.songs.isEmpty()) continue
                    val sectionStart = start
                    start += section.songs.size
                    item { Text(section.title, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall) }
                    itemsIndexed(section.songs, key = { i, s -> "${section.title}:$i:${s.videoId}" }) { i, song ->
                        SongListItem(song, onClick = { actions.playAll(flat, sectionStart + i, false, "Listening history") },
                            isCurrent = song.videoId == actions.currentVideoId, isPlaying = actions.isPlaying,
                            onPlayNext = { actions.playNext(song) }, onAddToQueue = { actions.addToQueue(song) })
                    }
                }
            }
        }
    }
}
