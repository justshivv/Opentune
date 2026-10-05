package com.opentune.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.artworkAt
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.pressable
import java.util.Calendar
import java.util.concurrent.TimeUnit

private val TILE = 160.dp

/** Where Library's tiles lead. */
class LibraryNav(
    val downloads: () -> Unit,
    val local: () -> Unit,
    val replay: () -> Unit,
    val settings: () -> Unit,
    val liked: () -> Unit,
    val playlist: (String) -> Unit,
    val browse: (String) -> Unit,
)

@Composable
fun LibraryScreen(contentPadding: PaddingValues, actions: SongActions, nav: LibraryNav) {
    val records by History.records.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val downloads by Downloads.entries.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val recents = remember(records) { History.recents(records, 30) }
    val yearStart = remember {
        Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val summary = remember(records) { History.replay(records, yearStart) }
    var ytPlaylists by remember { mutableStateOf<List<ShelfItem>>(emptyList()) }
    LaunchedEffect(signedIn) {
        ytPlaylists = if (signedIn) runCatching { MusicRepository.libraryPlaylists() }.getOrDefault(emptyList()) else emptyList()
    }
    var naming by remember { mutableStateOf(false) }
    if (naming) NameDialog("New playlist", "", onDismiss = { naming = false }) { name -> naming = false; nav.playlist(LibraryStore.createPlaylist(name)) }

    val who = account?.name?.substringBefore(' ')?.uppercase() ?: "YOU"
    val doneCount = downloads.values.count { it.state == DownloadState.DONE }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader("Library", actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIconButton(Icons.Rounded.History, "Replay", nav.replay)
                    GlassIconButton(Icons.Rounded.Settings, "Settings", nav.settings) {
                        val photo = account?.thumbnailUrl
                        if (photo != null) Artwork(photo, Modifier.size(44.dp), androidx.compose.foundation.shape.CircleShape)
                        else Icon(Icons.Rounded.Settings, "Settings", Modifier.size(26.dp))
                    }
                }
            })
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    ListeningCard(
                        big = "${TimeUnit.MILLISECONDS.toMinutes(summary.listenedMs)}",
                        caption = "MINUTES LISTENED",
                        who = who,
                        footer = "${summary.totalPlays} plays · $year",
                        colors = listOf(Color(0xFF4A1A5C), Color(0xFF241030)),
                        onClick = nav.replay,
                    )
                }
                summary.topSongs.firstOrNull()?.let { top ->
                    item {
                        ListeningCard(
                            big = top.title,
                            caption = "TOP SONG",
                            who = who,
                            footer = top.subtitle,
                            colors = listOf(Color(0xFF5C1A3E), Color(0xFF2A1022)),
                            onClick = nav.replay,
                        )
                    }
                }
                summary.topArtists.firstOrNull()?.let { top ->
                    item {
                        ListeningCard(
                            big = top.title,
                            caption = "TOP ARTIST",
                            who = who,
                            footer = "${top.plays} plays",
                            colors = listOf(Color(0xFF1A3A5C), Color(0xFF101C2E)),
                            onClick = nav.replay,
                        )
                    }
                }
            }
        }
        item { SectionHeader("On Device") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    GradientTile(Icons.Rounded.Download, "Downloads", if (doneCount == 0) "Downloaded songs" else "$doneCount songs",
                        listOf(Color(0xFF1C2A7A), Color(0xFF8A2340), Color(0xFF2A1260)), nav.downloads)
                }
                item {
                    GradientTile(Icons.Rounded.LibraryMusic, "Local Music", "Audio files on device",
                        listOf(Color(0xFF1C5A55), Color(0xFF2A2A6A), Color(0xFF5A1E5C)), nav.local)
                }
            }
        }
        item { SectionHeader("Playlists") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Tile("New playlist", "Made on this device", { naming = true }) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(56.dp))
                        }
                    }
                }
                item {
                    Tile("Liked Music", "${liked.size} songs", nav.liked) {
                        Box(
                            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF8E6CF0), Color(0xFFD45BA8)))),
                            Alignment.Center,
                        ) { Icon(Icons.Rounded.Favorite, null, Modifier.size(64.dp), tint = Color.White) }
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Tile(p.name, "${p.songs.size} songs", { nav.playlist(p.id) }, Modifier.animateItem()) {
                        val art = p.songs.firstOrNull()?.thumbnailUrl
                        if (art != null) Artwork(art.artworkAt(CARD_ART_PX), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(48.dp))
                        }
                    }
                }
                items(ytPlaylists, key = { "yt:${it.browseId}" }) { p ->
                    Tile(p.title, p.subtitle, { p.browseId?.let(nav.browse) }, Modifier.animateItem()) {
                        Artwork(p.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                    }
                }
            }
        }
        if (recents.isNotEmpty()) {
            item {
                SectionHeader("Recently played", action = {
                    PlayButtons(onPlay = { actions.playAll(recents, 0, false, "Recently played") }, onShuffle = { actions.playAll(recents, 0, true, "Recently played") }, compact = true)
                })
            }
            itemsIndexed(recents, key = { i, s -> "r$i:${s.videoId}" }) { i, song ->
                SongListItem(
                    song = song,
                    onClick = { actions.playAll(recents, i, false, "Recently played") },
                    isCurrent = song.videoId == actions.currentVideoId,
                    isPlaying = actions.isPlaying,
                    onPlayNext = { actions.playNext(song) },
                    onAddToQueue = { actions.addToQueue(song) },
                )
            }
        }
    }
}

@Composable
private fun ListeningCard(big: String, caption: String, who: String, footer: String, colors: List<Color>, onClick: () -> Unit) {
    Column(
        Modifier
            .width(320.dp)
            .height(200.dp)
            .pressable(onClick)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(colors))
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR LISTENING EXPERIENCE", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.6.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(alpha = 0.9f))
        }
        Spacer(Modifier.weight(1f))
        Text(big, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(caption, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.6.sp, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(14.dp))
        Text(who, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(footer, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GradientTile(icon: ImageVector, title: String, subtitle: String, colors: List<Color>, onClick: () -> Unit) {
    Tile(title, subtitle, onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors)), Alignment.Center) {
            Icon(icon, null, Modifier.size(52.dp), tint = Color.White)
        }
    }
}

@Composable
private fun Tile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier, art: @Composable () -> Unit) {
    Column(modifier.width(TILE).pressable(onClick)) {
        Box(Modifier.size(TILE).clip(RoundedCornerShape(22.dp))) { art() }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Songs saved for offline listening, newest first, including ones still on their way. */
@Composable
fun DownloadsScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by Downloads.entries.collectAsState()
    val list = remember(entries) { entries.values.sortedByDescending { it.addedAt } }
    val ready = remember(list) { list.filter { it.state == DownloadState.DONE }.map { it.song.toSong() } }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Downloads", onBack = onBack) }
        if (list.isEmpty()) {
            item {
                MessageState(Icons.Rounded.Download, "No downloads yet", Modifier.padding(top = 32.dp), message = "Pick Download from any song's menu to keep it for offline listening.")
            }
            return@LazyColumn
        }
        item {
            PlayButtons(
                onPlay = { actions.playAll(ready, 0, false, "Downloads") },
                onShuffle = { actions.playAll(ready, 0, true, "Downloads") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(list, key = { it.song.videoId }) { e ->
            val song = e.song.toSong()
            SongListItem(
                song = if (e.state == DownloadState.FAILED) song.copy(durationText = "Failed · tap to retry") else song,
                onClick = {
                    when (e.state) {
                        DownloadState.DONE -> actions.playAll(ready, ready.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0), false, "Downloads")
                        DownloadState.FAILED -> Downloads.retry(context, song.videoId)
                        else -> Unit
                    }
                },
                modifier = Modifier.animateItem(),
                isCurrent = song.videoId == actions.currentVideoId,
                isPlaying = actions.isPlaying,
                onPlayNext = { actions.playNext(song) },
                onAddToQueue = { actions.addToQueue(song) },
            )
        }
    }
}

/** Songs liked in the app (and on YouTube Music, when signed in). */
@Composable
fun LikedScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenYouTubeLikes: () -> Unit) {
    val liked by LibraryStore.liked.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val songs = remember(liked) { liked.map { it.toSong() } }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Liked Music", onBack = onBack) }
        if (signedIn) {
            item {
                TextButton(onClick = onOpenYouTubeLikes, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Liked on YouTube Music") }
            }
        }
        if (songs.isEmpty()) {
            item {
                MessageState(Icons.Rounded.Favorite, "No liked songs", Modifier.padding(top = 32.dp), message = "Tap the heart in the player, or Like in a song's menu.")
            }
            return@LazyColumn
        }
        item {
            PlayButtons(
                onPlay = { actions.playAll(songs, 0, false, "Liked Music") },
                onShuffle = { actions.playAll(songs, 0, true, "Liked Music") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        itemsIndexed(songs, key = { _, s -> s.videoId }) { i, song ->
            SongListItem(
                song = song,
                onClick = { actions.playAll(songs, i, false, "Liked Music") },
                modifier = Modifier.animateItem(),
                isCurrent = song.videoId == actions.currentVideoId,
                isPlaying = actions.isPlaying,
                onPlayNext = { actions.playNext(song) },
                onAddToQueue = { actions.addToQueue(song) },
            )
        }
    }
}

/** A playlist made in the app: play, rename, delete, remove songs. */
@Composable
fun LocalPlaylistScreen(id: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val playlists by LibraryStore.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val songs = remember(playlist) { playlist.songs.map { it.toSong() } }
    if (renaming) NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) { LibraryStore.renamePlaylist(id, it); renaming = false }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("The songs stay where they are; only this list goes.") },
            confirmButton = { TextButton(onClick = { deleting = false; LibraryStore.deletePlaylist(id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader(playlist.name, onBack = onBack, actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIconButton(Icons.Rounded.Edit, "Rename", { renaming = true })
                    GlassIconButton(Icons.Rounded.DeleteOutline, "Delete", { deleting = true })
                }
            })
        }
        if (songs.isEmpty()) {
            item {
                MessageState(Icons.AutoMirrored.Rounded.QueueMusic, "Nothing here yet", Modifier.padding(top = 32.dp), message = "Use Add to playlist in any song's menu.")
            }
            return@LazyColumn
        }
        item {
            PlayButtons(
                onPlay = { actions.playAll(songs, 0, false, playlist.name) },
                onShuffle = { actions.playAll(songs, 0, true, playlist.name) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            SongListItem(
                song = song,
                onClick = { actions.playAll(songs, i, false, playlist.name) },
                modifier = Modifier.animateItem(),
                isCurrent = song.videoId == actions.currentVideoId,
                isPlaying = actions.isPlaying,
                onPlayNext = { actions.playNext(song) },
                onAddToQueue = { actions.addToQueue(song) },
                trailing = {
                    IconButton(onClick = { LibraryStore.removeFromPlaylist(id, i) }) { Icon(Icons.Rounded.Close, "Remove from playlist") }
                },
            )
        }
    }
}
