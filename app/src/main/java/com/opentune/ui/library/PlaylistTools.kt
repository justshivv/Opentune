package com.opentune.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.model.ROW_ART_PX
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.Haptics
import com.opentune.ui.components.rememberHaptics
import kotlinx.coroutines.delay

/**
 * Download every song in a list, with how far it's got: "Download",
 * "Downloading 4 of 12", then "Downloaded". Files on the phone and songs
 * already saved are left as they are. A rising run of taps says when the
 * last one is done.
 */
@Composable
fun DownloadAllButton(songs: List<Song>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val entries by Downloads.entries.collectAsState()
    val wanted = remember(songs) { songs.filterNot { it.videoId.startsWith("local:") }.distinctBy { it.videoId } }
    val done = wanted.count { entries[it.videoId]?.state == DownloadState.DONE }
    val going = wanted.count { entries[it.videoId]?.state.let { s -> s == DownloadState.QUEUED || s == DownloadState.DOWNLOADING } }
    val all = wanted.isNotEmpty() && done == wanted.size
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(all) { if (all && started) haptics.pattern(Haptics.Pattern.DONE) }
    val progress by animateFloatAsState(if (wanted.isEmpty()) 0f else done.toFloat() / wanted.size, tween(500), label = "downloaded")
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest)
            .clickable(enabled = !all && wanted.isNotEmpty()) {
                haptics.press()
                started = true
                wanted.forEach { s -> if (entries[s.videoId]?.state != DownloadState.DONE) Downloads.enqueue(context, s) }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                all -> Icon(Icons.Rounded.DownloadDone, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                going > 0 -> CircularProgressIndicator(progress = { progress }, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp), trackColor = scheme.onSurface.copy(alpha = 0.15f))
                else -> Icon(Icons.Rounded.Download, null, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                all -> "Downloaded"
                going > 0 -> "Downloading ${done + 1} of ${wanted.size}".takeIf { done < wanted.size } ?: "Downloaded"
                done > 0 -> "Download the other ${wanted.size - done}"
                else -> "Download all"
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Opens [AddSongsSheet]; for a playlist made in the app. */
@Composable
fun AddSongsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { haptics.tick(); onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add songs", style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Find songs and put them in the playlist [playlistId]: a search box, and
 * while it's empty, what was played lately. Each song has a + that turns
 * into a check once it's in.
 */
@Composable
fun AddSongsSheet(playlistId: String, onDismiss: () -> Unit) {
    val playlists by LibraryStore.playlists.collectAsState()
    val inList = playlists.firstOrNull { it.id == playlistId }?.songs?.map { it.videoId }?.toSet().orEmpty()
    val records by History.records.collectAsState()
    val recent = remember(records) { History.recents(records, 20) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; searching = false; return@LaunchedEffect }
        // Wait for a pause in the typing, then ask.
        delay(350)
        searching = true
        results = runCatching { MusicRepository.search(q, SearchFilter.SONGS) }.getOrDefault(emptyList())
            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            .distinctBy { it.videoId }
        searching = false
    }
    FloatingCard(
        onDismiss = onDismiss,
        title = "Add songs",
        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
        expandable = true,
        header = {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search for songs") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            )
        },
    ) {
        val list = results ?: recent
        Column(Modifier.weight(1f, fill = false).heightIn(min = 120.dp).verticalScroll(rememberScrollState())) {
            Text(
                when {
                    searching -> "Searching…"
                    results == null -> if (recent.isEmpty()) "Search for songs to add" else "Played lately"
                    list.isEmpty() -> "Nothing found"
                    else -> "Songs"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            list.forEach { song -> AddRow(song, song.videoId in inList) { LibraryStore.addToPlaylist(playlistId, song) } }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AddRow(song: Song, added: Boolean, onAdd: () -> Unit) {
    val haptics = rememberHaptics()
    val pop by animateFloatAsState(if (added) 1f else 0f, tween(260), label = "added")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !added) { haptics.press(); onAdd() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(48.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer { val s = 1f + 0.18f * kotlin.math.sin(Math.PI.toFloat() * pop); scaleX = s; scaleY = s }
                .clip(CircleShape)
                .background(if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(added, transitionSpec = { (fadeIn(tween(180)) + scaleIn(tween(220), 0.6f)) togetherWith fadeOut(tween(120)) }, label = "addIcon") { a ->
                Icon(
                    if (a) Icons.Rounded.Check else Icons.Rounded.Add,
                    if (a) "Added" else "Add to playlist",
                    tint = if (a) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The row under a list's Play and Shuffle: download it all, and for the app's own playlists, add songs. */
@Composable
fun ListTools(songs: List<Song>, onAddSongs: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        if (onAddSongs != null) AddSongsButton(onAddSongs)
        if (songs.isNotEmpty()) DownloadAllButton(songs)
    }
}
