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
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
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
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.MenuRow
import com.opentune.ui.components.SheetButton
import com.opentune.data.download.downloadSelection
import com.opentune.ui.components.Haptics
import com.opentune.ui.components.rememberHaptics
import kotlinx.coroutines.delay

/**
 * Download every song in a list, with how far it's got: "Download",
 * "Downloading 4 of 12", then "Downloaded". Files on the phone and songs
 * already saved are left as they are. A rising run of taps says when the
 * last one is done.
 */
/** The list a [DownloadAllButton] downloads, kept so the library can show it as a list of its downloads. */
data class DownloadedListInfo(val id: String, val title: String, val subtitle: String, val thumbnailUrl: String?)

@Composable
fun DownloadAllButton(songs: List<Song>, modifier: Modifier = Modifier, list: DownloadedListInfo? = null) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val entries by Downloads.entries.collectAsState()
    val selection = remember(songs, entries) { downloadSelection(songs, entries) }
    val wanted = selection.wanted
    val done = selection.complete.size
    val going = selection.active.size
    var options by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val all = wanted.isNotEmpty() && done == wanted.size
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(all) { if (all && started) haptics.pattern(Haptics.Pattern.DONE) }
    // A list downloaded in full before lists were kept: keep it now, so it shows in the library too.
    LaunchedEffect(list?.id, selection.stored.size, wanted.size) {
        if (list != null && wanted.isNotEmpty() && selection.stored.size == wanted.size && com.opentune.data.download.DownloadedLists.get(list.id) == null) {
            com.opentune.data.download.DownloadedLists.save(list.id, list.title, list.subtitle, list.thumbnailUrl, songs.map { it.videoId })
        }
    }
    val progress by animateFloatAsState(if (wanted.isEmpty()) 0f else done.toFloat() / wanted.size, tween(500), label = "downloaded")
    val scheme = MaterialTheme.colorScheme
    val downloadRemaining = {
        started = true
        wanted.forEach { Downloads.enqueue(context, it) }
        list?.let { com.opentune.data.download.DownloadedLists.save(it.id, it.title, it.subtitle, it.thumbnailUrl, songs.map { s -> s.videoId }) }
    }
    if (options) FloatingCard(onDismiss = { options = false }, title = "Playlist downloads", icon = Icons.Rounded.Download) {
        Text("$done of ${wanted.size} songs saved · $going pending", Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium)
        if (done + going < wanted.size) MenuRow(Icons.Rounded.Download, "Download remaining songs") {
            downloadRemaining()
            options = false
        }
        if (going > 0) MenuRow(Icons.Rounded.Close, "Cancel downloads") {
            started = false
            Downloads.cancelAll(context, wanted)
            options = false
        }
        if (selection.stored.isNotEmpty()) MenuRow(Icons.Rounded.DeleteOutline, "Remove all downloads") {
            options = false
            confirmRemove = true
        }
    }
    if (confirmRemove) FloatingDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove playlist downloads?") },
        text = { Text("Removes this list’s downloaded audio and cancels unfinished downloads. Your playlist stays in your library. Songs shared with other playlists will also need a connection to play.") },
        confirmButton = { SheetButton("Remove downloads", onClick = {
            started = false
            Downloads.removeAll(context, wanted)
            list?.let { com.opentune.data.download.DownloadedLists.remove(it.id) }
            confirmRemove = false
        }) },
        dismissButton = { SheetButton("Keep downloads", onClick = { confirmRemove = false }, closes = true) },
    )
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest)
            .clickable(enabled = wanted.isNotEmpty()) {
                haptics.press()
                if (selection.stored.isNotEmpty()) options = true else downloadRemaining()
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
                all -> "Downloaded · Manage"
                going > 0 -> "Downloading · $done/${wanted.size}"
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
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val inList = playlist?.songs?.map { it.videoId }?.toSet().orEmpty()
    val progress by com.opentune.data.spotify.SpotifyPlaylistWrites.progress.collectAsState()
    val records by History.records.collectAsState()
    val recent = remember(records) { History.recents(records, 20) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(query, attempt) {
        val q = query.trim()
        searchError = null
        if (q.isEmpty()) { results = null; searching = false; return@LaunchedEffect }
        // Wait for a pause in the typing, then ask.
        delay(350)
        searching = true
        try {
            results = MusicRepository.search(q, SearchFilter.SONGS)
                .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                .distinctBy { it.videoId }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = com.opentune.ui.friendlyError(e)
        } finally { searching = false }
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
            searchError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(10.dp))
                androidx.compose.material3.TextButton(onClick = { attempt++ }) { Text("Retry search") }
            }
            list.forEach { song ->
                AddRow(song, song.videoId in inList,
                    progress[com.opentune.data.spotify.SpotifyPlaylistWrites.SaveKey(playlistId, song.videoId)]) {
                    LibraryStore.addToPlaylist(playlistId, song)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun AddRow(song: Song, added: Boolean, progress: com.opentune.data.spotify.SpotifyPlaylistWrites.SaveProgress? = null, onAdd: () -> Unit) {
    val haptics = rememberHaptics()
    val pop by animateFloatAsState(if (added) 1f else 0f, tween(260), label = "added")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !added && progress?.busy != true) { haptics.press(); onAdd() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(48.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (added) "Added" else progress?.message ?: song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = if (!added && progress?.failed == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (progress?.failed == true) 3 else 2, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer { val s = 1f + 0.18f * kotlin.math.sin(Math.PI.toFloat() * pop); scaleX = s; scaleY = s }
                .clip(CircleShape)
                .background(if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (!added && progress?.busy == true) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else AnimatedContent(added, transitionSpec = { (fadeIn(tween(180)) + scaleIn(tween(220), 0.6f)) togetherWith fadeOut(tween(120)) }, label = "addIcon") { a ->
                Icon(
                    if (a) Icons.Rounded.Check else Icons.Rounded.Add,
                    if (a) "Added" else if (progress?.failed == true) "Retry adding to playlist" else "Add to playlist",
                    tint = if (a) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A round button the height of the others in the row. */
@Composable
private fun RoundToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp)) }
}

/** Saves the album or playlist to the library, or takes it out; a little pop when it goes in. */
@Composable
private fun ListHeart(saved: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    var bursts by remember { mutableStateOf(0) }
    LaunchedEffect(bursts) {
        if (bursts == 0) return@LaunchedEffect
        pop.snapTo(0.7f)
        pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 500f))
    }
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable {
                haptics.pattern(if (saved) Haptics.Pattern.OFF else Haptics.Pattern.LIKE)
                if (!saved) bursts++
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (saved) "Remove from library" else "Save to library",
            Modifier.size(22.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            tint = if (saved) androidx.compose.ui.graphics.Color(0xFFFF4D6D) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The row under a list's Play and Shuffle: download it all, and for the app's own playlists, add songs. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ListTools(
    songs: List<Song>,
    onAddSongs: (() -> Unit)?,
    modifier: Modifier = Modifier,
    list: DownloadedListInfo? = null,
    /** Whether the list's heart is on; null shows no heart (the app's own playlists). */
    saved: Boolean? = null,
    onSave: () -> Unit = {},
    onShare: (() -> Unit)? = null,
    /** For a playlist on the account's YouTube Music: who can see it, and changing that. */
    privacy: com.opentune.data.model.PlaylistPrivacy? = null,
    onPrivacy: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saved != null) ListHeart(saved, onSave)
        if (onPrivacy != null) com.opentune.ui.components.PrivacyButton(privacy, onPrivacy)
        if (onAddSongs != null) AddSongsButton(onAddSongs)
        if (songs.isNotEmpty()) DownloadAllButton(songs, list = list)
        if (onShare != null) RoundToolButton(Icons.Rounded.Share, "Share", onShare)
    }
}
