package com.opentune.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
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
import com.opentune.data.model.HEADER_ART_PX
import com.opentune.data.subsonic.Subsonic
import com.opentune.ui.ScreenCache
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.pressable
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Where Library's tiles lead. */
class LibraryNav(
    val downloads: () -> Unit,
    val local: () -> Unit,
    val replay: () -> Unit,
    val settings: () -> Unit,
    val liked: () -> Unit,
    val playlist: (String) -> Unit,
    val browse: (String) -> Unit,
    val server: () -> Unit,
    val radio: () -> Unit,
    val together: () -> Unit = {},
    val importPlaylist: () -> Unit = {},
    val wrapped: () -> Unit = {},
)

private const val YT_PLAYLISTS = "library:ytPlaylists"

@Composable
fun LibraryScreen(contentPadding: PaddingValues, actions: SongActions, nav: LibraryNav) {
    val records by History.records.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val finished by Downloads.done.collectAsState(Downloads.doneNow())
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val recents = remember(records) { History.recents(records, 30) }
    var recentsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val yearStart = remember {
        Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val summary = remember(records) { History.replay(records, yearStart) }
    // Starts from the last copy, so coming back doesn't drop the list and
    // shift everything under the restored scroll position.
    @Suppress("UNCHECKED_CAST")
    var ytPlaylists by remember { mutableStateOf(ScreenCache.get(YT_PLAYLISTS)?.first as? List<ShelfItem> ?: emptyList()) }
    LaunchedEffect(signedIn) {
        ytPlaylists = if (signedIn) {
            runCatching { MusicRepository.libraryPlaylists() }.getOrNull()
                ?.also { ScreenCache.put(YT_PLAYLISTS, it) }
                ?: ytPlaylists
        } else {
            ScreenCache.put(YT_PLAYLISTS, emptyList<ShelfItem>())
            emptyList()
        }
    }
    var naming by remember { mutableStateOf(false) }
    if (naming) NameDialog("New playlist", "", onDismiss = { naming = false }) { name -> naming = false; nav.playlist(LibraryStore.createPlaylist(name)) }
    val doneCount = finished.size

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader("Library", actions = {
                GlassIconButton(Icons.Rounded.Settings, "Settings", nav.settings) {
                    val photo = account?.thumbnailUrl
                    if (photo != null) Artwork(photo, Modifier.size(44.dp), androidx.compose.foundation.shape.CircleShape)
                    else Icon(Icons.Rounded.Settings, "Settings", Modifier.size(26.dp))
                }
            })
        }
        item {
            YearCard(
                year = year,
                minutes = TimeUnit.MILLISECONDS.toMinutes(summary.listenedMs),
                plays = summary.totalPlays,
                topArtist = summary.topArtists.firstOrNull()?.title,
                topSong = summary.topSongs.firstOrNull()?.title,
                onClick = nav.wrapped,
            )
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(Icons.Rounded.Favorite, "Liked", "${liked.size} songs", nav.liked, Modifier.weight(1f))
                    Shortcut(Icons.Rounded.Download, "Downloads", if (doneCount == 0) "Offline songs" else "$doneCount songs", nav.downloads, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(Icons.Rounded.LibraryMusic, "On this phone", "Local files", nav.local, Modifier.weight(1f))
                    Shortcut(Icons.Rounded.History, "Replay", "Your top songs", nav.replay, Modifier.weight(1f))
                }
                val server by Subsonic.server.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(Icons.Rounded.Radio, "Radio", "Live stations", nav.radio, Modifier.weight(1f))
                    Shortcut(
                        Icons.Rounded.Dns,
                        "Music server",
                        server?.let { it.url.substringAfter("://") } ?: "Subsonic, lossless",
                        nav.server,
                        Modifier.weight(1f),
                    )
                }
                val room by com.opentune.data.together.Together.room.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(
                        Icons.Rounded.Groups,
                        "Listen together",
                        room?.let { if (it.hosting) "Your room · ${it.members.size} in" else "In ${it.hostName ?: "a friend"}'s room" } ?: "Rooms with friends, in sync",
                        nav.together,
                        Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            SectionHeader("Playlists", action = {
                Row {
                    TextButton(onClick = nav.importPlaylist) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                    TextButton(onClick = { naming = true }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New")
                    }
                }
            })
        }
        if (playlists.isEmpty() && ytPlaylists.isEmpty()) {
            item {
                Text(
                    "Make one with New, bring one over from Spotify or YouTube Music with Import, or add any song from its menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        items(playlists, key = { it.id }) { p ->
            PlaylistRow(p.songs.firstOrNull()?.thumbnailUrl, p.name, "${p.songs.size} songs · on this phone", { nav.playlist(p.id) }, Modifier.animateItem())
        }
        items(ytPlaylists, key = { "yt:${it.browseId}" }) { p ->
            PlaylistRow(p.thumbnailUrl, p.title, p.subtitle.ifBlank { "YouTube Music" }, { p.browseId?.let(nav.browse) }, Modifier.animateItem())
        }
        if (recents.isNotEmpty()) {
            item {
                SectionHeader("Recently played", action = {
                    PlayButtons(onPlay = { actions.playAll(recents, 0, false, "Recently played") }, onShuffle = { actions.playAll(recents, 0, true, "Recently played") }, compact = true)
                })
            }
            // The last few, with the rest a tap away, so the list doesn't run the page long.
            val shownRecents = if (recentsOpen) recents else recents.take(RECENTS_FOLDED)
            itemsIndexed(shownRecents, key = { i, s -> "r$i:${s.videoId}" }) { i, song ->
                SongListItem(
                    song = song,
                    onClick = { actions.playAll(recents, i, false, "Recently played") },
                    isCurrent = song.videoId == actions.currentVideoId,
                    isPlaying = actions.isPlaying,
                    onPlayNext = { actions.playNext(song) },
                    onAddToQueue = { actions.addToQueue(song) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (recents.size > RECENTS_FOLDED) {
                item(key = "recentsToggle") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).animateItem(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.FilledTonalButton(onClick = { recentsOpen = !recentsOpen }) {
                            Icon(
                                if (recentsOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                null,
                                Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (recentsOpen) "Show less" else "Show all ${recents.size}")
                        }
                    }
                }
            }
        }
    }
}

/** How many recently played songs show before "Show all". */
private const val RECENTS_FOLDED = 5

/** This year in numbers on a neon card; opens the Wrapped story. */
@Composable
private fun YearCard(year: Int, minutes: Long, plays: Int, topArtist: String?, topSong: String?, onClick: () -> Unit) {
    val line = buildList {
        add("$minutes minutes · $plays plays")
        topArtist?.let { add("Top artist: $it") }
        topSong?.let { add("Top song: $it") }
    }.joinToString("\n")
    com.opentune.ui.wrapped.WrappedBanner("Your $year Wrapped", line, onClick, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** One of the four library shortcuts: an accent badge, a name and a count. */
@Composable
private fun Shortcut(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .height(72.dp)
            .pressable(onClick, 0.96f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaylistRow(art: String?, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (art != null) {
            Artwork(art.artworkAt(CARD_ART_PX), Modifier.size(56.dp), RoundedCornerShape(14.dp))
        } else {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null)
            }
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { SheetButton("Save", onClick = { onConfirm(name) }) },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
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
    var adding by remember { mutableStateOf(false) }
    if (adding) AddSongsSheet(id, onDismiss = { adding = false })
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val songs = remember(playlist) { playlist.songs.map { it.toSong() } }
    if (renaming) NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) { LibraryStore.renamePlaylist(id, it); renaming = false }
    if (deleting) {
        FloatingDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("The songs stay where they are; only this list goes.") },
            confirmButton = { SheetButton("Delete", tone = SheetTone.Danger, onClick = { deleting = false; LibraryStore.deletePlaylist(id) }) },
            dismissButton = { SheetButton("Cancel", onClick = { deleting = false }, closes = true) },
        )
    }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            // The first song's cover, blurred behind the name and the play buttons.
            Box {
                if (songs.isNotEmpty()) com.opentune.ui.browse.HeaderBackdrop(songs.first().thumbnailUrl.artworkAt(HEADER_ART_PX), Modifier.matchParentSize())
                Column {
                    PageHeader(playlist.name, onBack = onBack, actions = {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassIconButton(Icons.Rounded.Edit, "Rename", { renaming = true })
                            GlassIconButton(Icons.Rounded.DeleteOutline, "Delete", { deleting = true })
                        }
                    })
                    if (songs.isNotEmpty()) {
                        PlayButtons(
                            onPlay = { actions.playAll(songs, 0, false, playlist.name) },
                            onShuffle = { actions.playAll(songs, 0, true, playlist.name) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
        if (songs.isEmpty()) {
            item {
                MessageState(Icons.AutoMirrored.Rounded.QueueMusic, "Nothing here yet", Modifier.padding(top = 32.dp), message = "Add songs here, or use Add to playlist in any song's menu.")
            }
            item { ListTools(songs, onAddSongs = { adding = true }, Modifier.fillMaxWidth().padding(16.dp)) }
            return@LazyColumn
        }
        item { ListTools(songs, onAddSongs = { adding = true }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
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
