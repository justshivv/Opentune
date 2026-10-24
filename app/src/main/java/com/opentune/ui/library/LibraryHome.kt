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
import androidx.compose.material.icons.rounded.CloudUpload
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
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.opentune.data.library.PlaylistLinks
import com.opentune.data.library.PlaylistSync
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.artworkAt
import com.opentune.data.model.HEADER_ART_PX
import com.opentune.data.subsonic.Subsonic
import com.opentune.ui.ScreenCache
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.AccountCopyOptions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.PrivacySheet
import com.opentune.ui.components.PrivateShareDialog
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.meaning
import com.opentune.ui.components.pressable
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

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
    /** An album or playlist downloaded as a whole, by its id. */
    val downloaded: (String) -> Unit = {},
)

private const val YT_PLAYLISTS = "library:ytPlaylists"

@Composable
fun LibraryScreen(contentPadding: PaddingValues, actions: SongActions, nav: LibraryNav) {
    val records by History.records.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val finished by Downloads.done.collectAsState(Downloads.doneNow())
    val downloadEntries by Downloads.entries.collectAsState()
    val downloadedLists by com.opentune.data.download.DownloadedLists.lists.collectAsState()
    val hearted by LibraryStore.savedLists.collectAsState()
    // Downloaded lists with something still downloaded in them, and how much.
    val savedLists = remember(downloadedLists, downloadEntries) {
        downloadedLists.mapNotNull { l ->
            val have = l.songIds.count { downloadEntries[it]?.state == com.opentune.data.download.DownloadState.DONE }
            val any = l.songIds.any { it in downloadEntries }
            if (any) l to have else null
        }
    }
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val recents = remember(records) { History.recents(records, 30) }
    var recentsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val yearStart = remember {
        Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val summary = remember(records) { History.replay(records, yearStart) }
    val cachedPlaylists by com.opentune.data.library.LibraryCache.playlists.collectAsState()
    // One linked to a playlist here is listed once, as that one.
    val ytPlaylists = if (signedIn) remember(cachedPlaylists, playlists) { PlaylistLinks.unlinked(cachedPlaylists, playlists) } else emptyList()
    LaunchedEffect(signedIn, account?.email) {
        if (signedIn) runCatching { MusicRepository.libraryPlaylists() }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    // Playlists made on YouTube Music since, and changes to linked ones both ways.
    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                try {
                    val sync = PlaylistSync.refresh()
                    runCatching { MusicRepository.libraryPlaylists() }.onFailure {
                        com.opentune.data.Pills.error("Couldn't refresh your YouTube Music playlists", context)
                    }
                    sync.join()
                } finally {
                    refreshing = false
                }
            }
        }
    }
    var naming by remember { mutableStateOf(false) }
    if (naming) NewPlaylistDialog(signedIn, onDismiss = { naming = false }) { name, privacy -> naming = false; nav.playlist(LibraryStore.createPlaylist(name, privacy = privacy)) }
    val doneCount = finished.size
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val page: @Composable () -> Unit = { LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader("Library", actions = { com.opentune.ui.components.AccountButton(nav.settings) })
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (signedIn) {
                        IconButton(onClick = refresh, enabled = !refreshing) {
                            if (refreshing) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, "Refresh YouTube Music playlists", Modifier.size(20.dp))
                        }
                    }
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
        if (playlists.isEmpty() && ytPlaylists.isEmpty() && savedLists.isEmpty() && hearted.isEmpty()) {
            item {
                Text(
                    "Make one with New, bring one over from Spotify or YouTube Music with Import, or add any song from its menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        // Albums and playlists downloaded whole: their songs, ready offline.
        items(savedLists, key = { "dl:${it.first.id}" }) { (l, have) ->
            PlaylistRow(
                l.thumbnailUrl, l.title,
                "$have of ${l.songIds.size} downloaded · plays offline",
                { nav.downloaded(l.id) }, Modifier.animateItem(),
            )
        }
        items(playlists, key = { it.id }) { p ->
            val saved = p.songs.count { downloadEntries[it.videoId]?.state == com.opentune.data.download.DownloadState.DONE }
            val where = when {
                saved > 0 -> "$saved downloaded"
                p.spotifyId != null -> null
                signedIn && p.synced -> "on YouTube Music"
                else -> "on this phone"
            }
            val subtitle = listOfNotNull("${p.songs.size} songs", where).joinToString(" · ")
            if (p.spotifyId != null && p.coverUrl != null) {
                PlaylistRow(p.coverUrl, p.name, subtitle, { nav.playlist(p.id) }, Modifier.animateItem(), from = "Spotify")
            } else {
                PlaylistRow(null, p.name, subtitle, { nav.playlist(p.id) }, Modifier.animateItem(), cover = {
                    com.opentune.ui.components.PlaylistCover(p.name, p.songs.map { it.thumbnailUrl }, Modifier.size(56.dp))
                }, from = if (p.spotifyId != null) "Spotify" else null)
            }
        }
        // Albums and playlists saved with their heart; one downloaded too is listed once, above.
        items(hearted.filter { h -> savedLists.none { it.first.id == h.browseId } }, key = { "saved:${it.browseId}" }) { h ->
            PlaylistRow(h.thumbnailUrl, h.title, h.subtitle.ifBlank { "Saved" }, { nav.browse(h.browseId) }, Modifier.animateItem())
        }
        items(ytPlaylists.filter { p -> savedLists.none { it.first.id == p.browseId } && hearted.none { it.browseId == p.browseId } }, key = { "yt:${it.browseId}" }) { p ->
            PlaylistRow(p.thumbnailUrl, p.title, p.subtitle.ifBlank { "YouTube Music" }, { p.browseId?.let(nav.browse) }, Modifier.animateItem())
        }
        if (recents.isNotEmpty()) {
            item {
                SectionHeader("Recently played", action = {
                    PlayButtons(onPlay = { actions.playAll(recents, 0, false, "Recently played") }, onShuffle = { actions.playAll(recents, 0, true, "Recently played") }, compact = true, onSmartShuffle = { actions.smartShuffle(recents, "Recently played") })
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
    } }

    // Pull to refresh only when there's an account to refresh from.
    if (signedIn) {
        com.opentune.ui.components.MarkRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) { page() }
    } else {
        page()
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
private fun PlaylistRow(
    art: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: (@Composable () -> Unit)? = null,
    /** Where the playlist comes from, as a small tag before [subtitle]. */
    from: String? = null,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover != null) {
            cover()
        } else if (art != null) {
            Artwork(art.artworkAt(CARD_ART_PX), Modifier.size(56.dp), RoundedCornerShape(14.dp))
        } else {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null)
            }
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (from != null) SourceTag(from, Modifier.padding(end = 6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Spotify's green, for the tag on playlists brought over from it. */
private val SpotifyGreen = androidx.compose.ui.graphics.Color(0xFF1DB954)

/** A small tag naming where a playlist comes from: "Spotify" in Spotify's green. */
@Composable
internal fun SourceTag(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        style = MaterialTheme.typography.labelMedium,
        color = SpotifyGreen,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SpotifyGreen.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
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

/**
 * Names a new playlist. Signed in, it can go on YouTube Music too (on unless
 * turned off), with a choice of who can see it there; [onCreate] gets that
 * privacy, or null for a playlist on this phone only.
 */
@Composable
internal fun NewPlaylistDialog(signedIn: Boolean, onDismiss: () -> Unit, onCreate: (String, PlaylistPrivacy?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(true) }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") })
                if (signedIn) AccountCopyOptions(online, { online = it }, privacy, { privacy = it }, Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { SheetButton("Save", onClick = { onCreate(name, privacy.takeIf { signedIn && online }) }) },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}

/** Puts a playlist that so far lives only on this phone on YouTube Music too, with who can see it there. */
@Composable
private fun SaveToAccountDialog(name: String, onDismiss: () -> Unit, onSave: (PlaylistPrivacy) -> Unit) {
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to YouTube Music") },
        text = {
            Column {
                Text("Makes a copy of $name on YouTube Music, songs and all. Changes you make here go there too.")
                com.opentune.ui.components.PillSegmented(PlaylistPrivacy.entries, privacy, { it.label }, { privacy = it }, Modifier.padding(top = 16.dp))
                Text(privacy.meaning, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { SheetButton("Save", onClick = { onSave(privacy) }) },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}

/**
 * An album or playlist downloaded as a whole: its downloaded songs in the
 * list's order, playable offline, with any still on their way.
 */
@Composable
fun DownloadedListScreen(id: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val lists by com.opentune.data.download.DownloadedLists.lists.collectAsState()
    val entries by Downloads.entries.collectAsState()
    val saved = lists.firstOrNull { it.id == id }
    val inList = remember(saved, entries) { saved?.songIds.orEmpty().mapNotNull { entries[it] } }
    val ready = remember(inList) { inList.filter { it.state == DownloadState.DONE }.map { it.song.toSong() } }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove && saved != null) com.opentune.ui.components.FloatingDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove this download?") },
        text = { Text("Deletes the downloaded songs of ${saved.title} from this phone and takes it out of your library's downloads.") },
        confirmButton = { com.opentune.ui.components.SheetButton("Remove", tone = com.opentune.ui.components.SheetTone.Danger, onClick = {
            Downloads.removeAll(context, inList.map { it.song.toSong() })
            com.opentune.data.download.DownloadedLists.remove(saved.id)
            confirmRemove = false
            onBack()
        }) },
        dismissButton = { com.opentune.ui.components.SheetButton("Keep", onClick = { confirmRemove = false }, closes = true) },
    )
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader(saved?.title ?: "Downloaded", onBack = onBack) }
        if (saved == null || inList.isEmpty()) {
            item { MessageState(Icons.Rounded.Download, "Nothing downloaded here", Modifier.padding(top = 32.dp), message = "Its songs were removed from Downloads.") }
            return@LazyColumn
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(saved.thumbnailUrl, Modifier.size(96.dp), RoundedCornerShape(16.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (saved.subtitle.isNotBlank()) Text(saved.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Text("${ready.size} of ${saved.songIds.size} songs downloaded", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { confirmRemove = true }, contentPadding = PaddingValues(0.dp)) { Text("Remove download") }
                }
            }
        }
        item {
            PlayButtons(
                onPlay = { actions.playAll(ready, 0, false, saved.title) },
                onShuffle = { actions.playAll(ready, 0, true, saved.title) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(inList, key = { it.song.videoId }) { e ->
            val song = e.song.toSong()
            SongListItem(
                song = when (e.state) {
                    DownloadState.FAILED -> song.copy(durationText = "Failed · tap to retry")
                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> song.copy(durationText = "Downloading ${(e.progress * 100).toInt()}%")
                    else -> song
                },
                onClick = {
                    when (e.state) {
                        DownloadState.DONE -> actions.playAll(ready, ready.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0), false, saved.title)
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
                onSmartShuffle = { actions.smartShuffle(songs, "Liked Music") },
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

/**
 * A playlist made in the app: play, rename, delete, remove songs. Signed in,
 * it can also be saved to YouTube Music, after which it's kept in step there
 * and has a privacy and a link to share.
 */
@Composable
fun LocalPlaylistScreen(id: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val playlists by LibraryStore.playlists.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val pending by PlaylistSync.pending.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var savingOnline by remember { mutableStateOf(false) }
    var choosingPrivacy by remember { mutableStateOf(false) }
    var privateShare by remember { mutableStateOf(false) }
    if (adding) AddSongsSheet(id, onDismiss = { adding = false })
    // Changes made on YouTube Music since, once this page's own have gone.
    val remoteId = playlist?.remoteId
    LaunchedEffect(id, signedIn, remoteId) {
        if (signedIn && remoteId != null) PlaylistSync.refresh(id)
    }
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // Tagged with the playlist, so smart shuffle can offer to keep a song it mixed in.
    val songs = remember(playlist) { playlist.songs.map { it.toSong().copy(playbackSourceId = playlist.id) } }
    val online = signedIn && playlist.synced
    if (renaming) NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) { LibraryStore.renamePlaylist(id, it); renaming = false }
    if (deleting) {
        // Signed out, the copy on YouTube Music can't be reached, so it's left as it is.
        val linked = signedIn && remoteId != null
        var everywhere by remember { mutableStateOf(false) }
        FloatingDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = {
                Column {
                    Text("The songs stay where they are; only this list goes.")
                    if (linked) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).clip(MaterialTheme.shapes.medium).clickable { everywhere = !everywhere }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Also delete it on YouTube Music", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            com.opentune.ui.components.AppSwitch(everywhere, { everywhere = it })
                        }
                    }
                }
            },
            confirmButton = { SheetButton("Delete", tone = SheetTone.Danger, onClick = { deleting = false; LibraryStore.deletePlaylist(id, alsoOnAccount = linked && everywhere) }) },
            dismissButton = { SheetButton("Cancel", onClick = { deleting = false }, closes = true) },
        )
    }
    if (savingOnline) SaveToAccountDialog(playlist.name, onDismiss = { savingOnline = false }) { privacy ->
        savingOnline = false
        LibraryStore.saveToAccount(id, privacy)
    }
    if (choosingPrivacy) PrivacySheet(playlist.privacy, { LibraryStore.setPlaylistPrivacy(id, it) }, onDismiss = { choosingPrivacy = false })
    if (privateShare && remoteId != null) PrivateShareDialog(
        onMakePublic = {
            privateShare = false
            LibraryStore.setPlaylistPrivacy(id, PlaylistPrivacy.PUBLIC)
            com.opentune.ui.share.ListShare.sharePlaylist(context, playlist.name, remoteId, com.opentune.ui.share.ListShare.coversFor(null, songs), com.opentune.ui.share.ListShare.countLine(songs.size))
        },
        onShareAnyway = {
            privateShare = false
            com.opentune.ui.share.ListShare.sharePlaylist(context, playlist.name, remoteId, com.opentune.ui.share.ListShare.coversFor(null, songs), com.opentune.ui.share.ListShare.countLine(songs.size))
        },
        onDismiss = { privateShare = false },
    )
    // On YouTube Music, the link; otherwise (or signed out, or not made there yet) the songs themselves.
    val share: () -> Unit = {
        when {
            !signedIn || remoteId == null -> com.opentune.ui.share.ListShare.shareLocal(context, playlist.name, songs)
            playlist.privacy?.linkWorks == true -> com.opentune.ui.share.ListShare.sharePlaylist(context, playlist.name, remoteId, com.opentune.ui.share.ListShare.coversFor(null, songs), com.opentune.ui.share.ListShare.countLine(songs.size))
            else -> privateShare = true
        }
    }
    val tools: @Composable (Modifier) -> Unit = { modifier ->
        ListTools(
            songs,
            onAddSongs = { adding = true },
            onShare = share,
            modifier = modifier,
            privacy = playlist.privacy,
            onPrivacy = if (online) ({ choosingPrivacy = true }) else null,
        )
    }
    // Where the playlist stands with YouTube Music, under its cover.
    val status: (@Composable () -> Unit)? = when {
        // A copy of a Spotify playlist: it follows Spotify, not YouTube Music.
        playlist.spotifyId != null -> ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceTag("Spotify", Modifier.padding(end = 8.dp))
                Text(
                    "Kept in step with your Spotify library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        })
        !signedIn -> null
        !playlist.synced -> ({
            TextButton(onClick = { savingOnline = true }) {
                Icon(Icons.Rounded.CloudUpload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save to YouTube Music")
            }
        })
        else -> ({
            val waiting = pending.count { it.playlist == id }
            Text(
                when {
                    pending.any { it.playlist == id && it.kind == com.opentune.data.library.PlaylistEdit.Kind.CREATE } -> "Saving to YouTube Music…"
                    waiting > 0 -> "On YouTube Music · ${if (waiting == 1) "1 change" else "$waiting changes"} to send"
                    else -> "On YouTube Music"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        })
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
                    com.opentune.ui.components.PlaylistCover(
                        playlist.name,
                        songs.map { it.thumbnailUrl },
                        Modifier.padding(top = 4.dp, bottom = 8.dp).size(200.dp).align(Alignment.CenterHorizontally),
                        RoundedCornerShape(24.dp),
                    )
                    if (status != null) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { status() }
                    }
                    if (songs.isNotEmpty()) {
                        PlayButtons(
                            onPlay = { actions.playAll(songs, 0, false, playlist.name) },
                            onShuffle = { actions.playAll(songs, 0, true, playlist.name) },
                            onSmartShuffle = { actions.smartShuffle(songs, playlist.name) },
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
            item { tools(Modifier.fillMaxWidth().padding(16.dp)) }
            return@LazyColumn
        }
        item { tools(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
        itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            SongListItem(
                song = song,
                onClick = { actions.playAll(songs, i, false, playlist.name) },
                modifier = Modifier.animateItem(),
                isCurrent = song.videoId == actions.currentVideoId,
                isPlaying = actions.isPlaying,
                onPlayNext = { actions.playNext(song) },
                onAddToQueue = { actions.addToQueue(song) },
                onRemoveFromPlaylist = { LibraryStore.removeFromPlaylist(id, i) },
                trailing = {
                    IconButton(onClick = { LibraryStore.removeFromPlaylist(id, i) }) { Icon(Icons.Rounded.Close, "Remove from playlist") }
                },
            )
        }
    }
}
