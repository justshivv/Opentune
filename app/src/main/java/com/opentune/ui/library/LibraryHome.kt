package com.opentune.ui.library

import com.opentune.ui.components.pinnedToTop
import com.opentune.ui.components.menuClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.opentune.ui.components.glass
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.foundation.layout.aspectRatio
import com.opentune.data.settings.AppSettings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ThumbUp
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
import androidx.compose.material.icons.rounded.Search
import com.opentune.ui.browse.playlistSearchResults
import com.opentune.ui.browse.coverWash
import com.opentune.ui.browse.dismissPlaylistSearch
import androidx.compose.material.icons.rounded.Settings
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.opentune.ui.theme.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
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
    val history: () -> Unit = {},
    /** Every playlist, from Show all. */
    val allPlaylists: () -> Unit = {},
    /** The playlists brought over from Spotify, on their own page. */
    val spotify: () -> Unit = {},
    /** An album or playlist downloaded as a whole, by its id. */
    val downloaded: (String) -> Unit = {},
    /** A smart playlist, by its id. */
    val smart: (String) -> Unit = {},
    /** Making a new smart playlist. */
    val newSmart: () -> Unit = {},
)

private data class LibraryPlaylistRow(
    val key: String, val title: String, val subtitle: String, val url: String?,
    val local: com.opentune.data.library.LocalPlaylist? = null,
    /** What it's pinned by: a local playlist's id, or its YouTube Music browse id. */
    val pin: String,
    /** Where it lives: "Spotify", "YouTube Music" or "On this phone". */
    val source: String,
    /** How often its songs were played; only known for the ones kept here. */
    val plays: Int = 0,
    val onClick: () -> Unit,
)

/** YouTube Music's Liked Music playlist, by its browse id ("VLLM") or playlist id ("LM"). */
internal fun isYouTubeLikes(browseId: String?): Boolean = browseId == "VLLM" || browseId == "LM"

/** Pinning playlists to the top of Library. */
internal object LibraryPins {
    fun pinned(key: String) = key in AppSettings.ui.value.pinnedPlaylists
    /** As many as the row on Library shows. */
    const val MAX = 5

    fun toggle(key: String) {
        val pins = AppSettings.ui.value.pinnedPlaylists
        if (key !in pins && pins.size >= MAX) {
            com.opentune.data.Pills.info(com.opentune.i18n.Tr.f("Up to {0} playlists can be pinned. Unpin one first", MAX))
            return
        }
        AppSettings.updateUi { ui ->
            ui.copy(pinnedPlaylists = if (key in ui.pinnedPlaylists) ui.pinnedPlaylists - key else ui.pinnedPlaylists + key)
        }
    }
}

private const val YT_PLAYLISTS = "library:ytPlaylists"

/** Wide enough that two and a bit show across a phone, as in the other music apps. */
private val TILE_WIDTH = 164.dp

/**
 * Every playlist in Library, the pinned ones first in the order they were pinned,
 * then those made on Spotify, those on YouTube Music and those only on this phone.
 */
@Composable
private fun rememberPlaylistRows(nav: LibraryNav): List<LibraryPlaylistRow> {
    val records by History.records.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val downloadEntries by Downloads.entries.collectAsState()
    val hearted by LibraryStore.savedLists.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val pinned = AppSettings.ui.collectAsState().value.pinnedPlaylists
    val cachedPlaylists by com.opentune.data.library.LibraryCache.playlists.collectAsState()
    val spotifyConnected by com.opentune.ui.spotify.SpotifyConnection.connected.collectAsState()
    // One linked to a playlist here is listed once, as that one.
    val ytPlaylists = if (signedIn) remember(cachedPlaylists, playlists) { PlaylistLinks.unlinked(cachedPlaylists, playlists) } else emptyList()
    val playCounts = remember(records) { records.groupingBy { it.videoId }.eachCount() }
    val rows = mutableListOf<LibraryPlaylistRow>()
    for (p in playlists) {
        val source = when {
            p.spotifyId != null && spotifyConnected -> "Spotify"
            p.spotifyId == null && p.synced -> "YouTube Music"
            else -> "On this phone"
        }
        val saved = p.songs.count { downloadEntries[it.videoId]?.state == DownloadState.DONE }
        rows += LibraryPlaylistRow(p.id, p.name,
            (if (p.songs.size == 1) "1 song" else com.opentune.i18n.Tr.f("{0} songs", p.songs.size)) + if (saved > 0) com.opentune.i18n.Tr.f(" · {0} downloaded", saved) else "",
            p.coverUrl, p, pin = p.id, source = source,
            plays = p.songs.sumOf { playCounts[it.videoId] ?: 0 }, onClick = { nav.playlist(p.id) })
    }
    // YouTube Music's own Liked Music is the app's Liked tile (which opens it too), so it isn't listed twice.
    for (h in hearted.filterNot { isYouTubeLikes(it.browseId) }) {
        rows += LibraryPlaylistRow("saved:${h.browseId}", h.title, h.subtitle, h.thumbnailUrl, pin = h.browseId, source = "YouTube Music", onClick = { nav.browse(h.browseId) })
    }
    for (p in ytPlaylists.filter { p -> hearted.none { it.browseId == p.browseId } && !isYouTubeLikes(p.browseId) }) {
        val browseId = p.browseId ?: continue
        rows += LibraryPlaylistRow("yt:$browseId", p.title, p.subtitle, p.thumbnailUrl, pin = browseId, source = "YouTube Music", onClick = { nav.browse(browseId) })
    }
    val order = listOf("Spotify", "YouTube Music", "On this phone")
    val pinnedRows = pinned.mapNotNull { key -> rows.firstOrNull { it.pin == key } }
    return pinnedRows + rows.filter { it !in pinnedRows }.sortedBy { order.indexOf(it.source) }
}

@Composable
fun LibraryScreen(contentPadding: PaddingValues, actions: SongActions, nav: LibraryNav) {
    val records by History.records.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val finished by Downloads.done.collectAsState(Downloads.doneNow())
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val libraryUi by AppSettings.ui.collectAsState()
    val yearStart = remember {
        Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    }
    val summary = remember(records) { History.replay(records, yearStart) }
    LaunchedEffect(signedIn, account?.email) {
        if (signedIn) runCatching { MusicRepository.libraryPlaylists() }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    // The mark at the top shows only for a pull: started from the button further down the
    // page, it sat over the tiles while the button showed its own spinner.
    var pulled by remember { mutableStateOf(false) }
    // Playlists made on YouTube Music since, and changes to linked ones both ways.
    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                try {
                    val sync = PlaylistSync.refresh()
                    runCatching { MusicRepository.libraryPlaylists() }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        com.opentune.data.Pills.error(
                            if (it is com.opentune.data.LibrarySignedOut) "YouTube Music says this phone isn't signed in. Sign in again from Settings"
                            else "Couldn't refresh your YouTube Music playlists",
                            context,
                        )
                    }
                    sync.join()
                } finally {
                    refreshing = false
                    pulled = false
                }
            }
        }
    }
    var naming by remember { mutableStateOf(false) }
    if (naming) NewPlaylistDialog(signedIn, onDismiss = { naming = false }) { name, privacy -> naming = false; nav.playlist(LibraryStore.createPlaylist(name, privacy = privacy)) }
    val doneCount = finished.size
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rows = rememberPlaylistRows(nav)
    // In the list, which sources show all their playlists rather than the first few.
    val openGroups = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // The pinned ones, then the most played; Show all has the rest. Spotify's have their own
    // page (the Spotify row above), so they show here only when pinned.
    val shownRows = remember(rows, libraryUi.pinnedPlaylists) { rows.filter { it.source != "Spotify" || it.pin in libraryUi.pinnedPlaylists } }
    val top = remember(shownRows, libraryUi.pinnedPlaylists) {
        val pinned = shownRows.filter { it.pin in libraryUi.pinnedPlaylists }
        (pinned + shownRows.filter { it !in pinned }.sortedByDescending { it.plays }).take(5)
    }

    val page: @Composable () -> Unit = { LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader(com.opentune.ui.theme.tr("Library"), actions = {
                // History and the account side by side in one glass pill.
                Row(
                    Modifier.height(54.dp).glass(androidx.compose.foundation.shape.CircleShape).padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = nav.history) { Icon(Icons.Rounded.History, "Listening history") }
                    com.opentune.ui.components.AccountButton(nav.settings, plain = true)
                }
            })
        }
        item {
            // This year's listening, a card each for the minutes and the top song, artist and album; opens Replay.
            val since = remember(records) { memberSince(records.lastOrNull()?.playedAt) }
            ReplayCards(summary, ReplayRange.YEAR, account?.name ?: com.opentune.data.together.Together.savedName.takeIf { it.isNotBlank() } ?: "You",
                since, onClick = nav.replay, modifier = Modifier.padding(vertical = 8.dp))
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(Icons.Rounded.Favorite, "Liked", com.opentune.i18n.Tr.f("{0} songs", liked.size), nav.liked, Modifier.weight(1f))
                    Shortcut(Icons.Rounded.Download, "Downloads", if (doneCount == 0) "Offline songs" else com.opentune.i18n.Tr.f("{0} songs", doneCount), nav.downloads, Modifier.weight(1f))
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
                        room?.let { if (it.hosting) com.opentune.i18n.Tr.f("Your room · {0} in", it.members.size) else com.opentune.i18n.Tr.f("In {0}'s room", it.hostName ?: com.opentune.i18n.Tr.t("a friend")) } ?: com.opentune.i18n.Tr.t("Rooms with friends, in sync"),
                        nav.together,
                        Modifier.weight(1f),
                    )
                }
                // Spotify: a row of its own, its playlists on their own page.
                val spotifyOn by com.opentune.ui.spotify.SpotifyConnection.connected.collectAsState()
                val spotifyLists by LibraryStore.playlists.collectAsState()
                val fromSpotify = remember(spotifyLists) { spotifyLists.count { it.spotifyId != null } }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).pressable(nav.spotify, 0.98f)
                        .padding(start = 4.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), null, Modifier.size(40.dp))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text("Spotify", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                fromSpotify > 0 -> if (fromSpotify == 1) "1 playlist" else com.opentune.i18n.Tr.f("{0} playlists", fromSpotify)
                                spotifyOn -> "Your playlists"
                                else -> "Connect or import"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SectionHeader("Playlists", action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (signedIn) {
                        IconButton(onClick = { pulled = false; refresh() }, enabled = !refreshing) {
                            if (refreshing) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, "Refresh YouTube Music playlists", Modifier.size(20.dp))
                        }
                    }
                    TextButton(onClick = nav.allPlaylists) { Text(if (libraryUi.playlistList) "View all" else "Show all", fontWeight = FontWeight.SemiBold) }
                }
            })
        }
        if (libraryUi.playlistList) {
            // The list, as Library had it before the tiles: new and import first, then the
            // pinned ones and each source's own, five of each until "Show more".
            item(key = "playlist-new") { ActionLine(Icons.Rounded.Add, "New playlist", if (signedIn) "Here and on YouTube Music" else "On this phone") { naming = true } }
            item(key = "playlist-import") {
                ActionLine(null, "Import a playlist", "From a Spotify or YouTube Music link", onClick = nav.importPlaylist, picture = {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), null, Modifier.size(30.dp))
                })
            }
            playlistGroups(shownRows, libraryUi.pinnedPlaylists).forEach { (group, inGroup) ->
                item(key = "group:$group") { GroupLabel(group) }
                val open = group == PINNED || openGroups[group] == true
                val visible = if (open) inGroup else inGroup.take(LIST_FOLD)
                items(visible, key = { "$group:${it.key}" }) { row -> PlaylistLine(row, Modifier.animateItem()) }
                if (group != PINNED && inGroup.size > LIST_FOLD) item(key = "fold:$group") {
                    TextButton(onClick = { openGroups[group] = !open }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (open) "Show less" else com.opentune.i18n.Tr.f("Show more ({0})", inGroup.size - LIST_FOLD))
                    }
                }
            }
        } else item(key = "playlist-tiles") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                item(key = "new") { NewPlaylistTile(signedIn, { naming = true }, Modifier.width(TILE_WIDTH)) }
                item(key = "import") {
                    ActionTile("Import Spotify", "From a playlist link", nav.importPlaylist, Modifier.width(TILE_WIDTH)) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), null, Modifier.size(64.dp))
                    }
                }
                item(key = "liked") { LikedTile(liked.size, nav.liked, Modifier.width(TILE_WIDTH)) }
                items(top, key = { it.key }) { row -> PlaylistTile(row, Modifier.width(TILE_WIDTH).animateItem()) }
            }
        }
        item(key = "smart-playlists") { SmartPlaylistsRow(nav.smart, nav.newSmart, TILE_WIDTH) }
    } }

    // Pull to refresh only when there's an account to refresh from.
    if (signedIn) {
        com.opentune.ui.components.MarkRefreshBox(isRefreshing = refreshing && pulled, onRefresh = { pulled = true; refresh() }, modifier = Modifier.fillMaxSize()) { page() }
    } else {
        page()
    }
}

/** The orders "Show all" can put playlists in. Pinned ones stay first in each. */
private enum class PlaylistOrder(val label: String) {
    LIBRARY("Where they're from"), PLAYED("Most played"), NEWEST("Newest first"), NAME("A to Z"), SONGS("Most songs");

    fun sort(rows: List<LibraryPlaylistRow>): List<LibraryPlaylistRow> = when (this) {
        LIBRARY -> rows
        PLAYED -> rows.sortedByDescending { it.plays }
        NEWEST -> rows.sortedByDescending { it.local?.createdAt ?: 0L }
        NAME -> rows.sortedBy { it.title.lowercase() }
        SONGS -> rows.sortedByDescending { it.local?.songs?.size ?: -1 }
    }
}

/** Every playlist, two to a row, from "Show all" on Library. */
@Composable
fun AllPlaylistsScreen(
    contentPadding: PaddingValues,
    nav: LibraryNav,
    onBack: () -> Unit,
    /** Only Spotify's playlists, on the Spotify page; otherwise every playlist but Spotify's, which have that page. */
    spotify: Boolean = false,
) {
    val liked by LibraryStore.liked.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val pinned = AppSettings.ui.collectAsState().value.pinnedPlaylists
    var order by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(PlaylistOrder.LIBRARY) }
    var sorting by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    if (naming) NewPlaylistDialog(signedIn, onDismiss = { naming = false }) { name, privacy -> naming = false; nav.playlist(LibraryStore.createPlaylist(name, privacy = privacy)) }
    // The Spotify page: Spotify's playlists. Show all: the rest, and a Spotify one only when pinned.
    val rows = rememberPlaylistRows(nav).filter { if (spotify) it.source == "Spotify" else it.source != "Spotify" || it.pin in pinned }
    val spotifyOn by com.opentune.ui.spotify.SpotifyConnection.connected.collectAsState()
    // Show all brings the account's YouTube Music playlists up to date as it opens, behind what's
    // already here, and says so plainly when it can't, rather than leaving the page looking empty.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var library by remember { mutableStateOf<YouTubeLoad>(YouTubeLoad.Idle) }
    val loadLibrary: () -> Unit = {
        if (signedIn && !spotify && library != YouTubeLoad.Loading) scope.launch {
            library = YouTubeLoad.Loading
            library = try {
                MusicRepository.libraryPlaylists()
                YouTubeLoad.Done
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.opentune.data.LibrarySignedOut) {
                YouTubeLoad.Failed("YouTube Music says this phone isn't signed in any more. Sign in again from Settings.")
            } catch (_: Exception) {
                YouTubeLoad.Failed("Couldn't reach YouTube Music just now. Check the connection and try again.")
            }
        }
    }
    LaunchedEffect(signedIn, spotify) { loadLibrary() }
    // The Spotify page brings Spotify's latest over once as it opens, behind the list already
    // here, so it shows at once and changes only if Spotify has something new.
    val spotifySync by com.opentune.data.spotify.SpotifySync.status.collectAsState()
    if (spotify) LaunchedEffect(spotifyOn) {
        if (spotifyOn) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.opentune.ui.spotify.SpotifyConnection.syncPlaylists(force = true)
        }
    }
    val shown = remember(rows, order, pinned) {
        val top = rows.filter { it.pin in pinned }
        top + order.sort(rows.filter { it !in top })
    }
    val asList = AppSettings.ui.collectAsState().value.playlistList
    val full = androidx.compose.foundation.lazy.grid.GridItemSpan(if (asList) 1 else 2)
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Behind it all, the cover of the first playlist in view, blurred across the top and running
    // down into its colour; as the page scrolls, both follow the playlists on screen.
    val byKey = remember(shown) { shown.associateBy { it.key } }
    val coverUrl by remember(byKey) {
        androidx.compose.runtime.derivedStateOf {
            grid.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
                byKey[item.key]?.let { it.url ?: it.local?.songs?.firstOrNull()?.thumbnailUrl }
            }?.artworkAt(CARD_ART_PX)
        }
    }
    val seed = com.opentune.ui.theme.rememberArtworkSeed(coverUrl)
    val background = MaterialTheme.colorScheme.background
    val accent by androidx.compose.animation.animateColorAsState(
        seed?.let { androidx.compose.ui.graphics.lerp(background, it, 0.24f) } ?: background,
        androidx.compose.animation.core.tween(800), label = "playlistsAccent",
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to accent, 0.6f to accent,
        1f to androidx.compose.ui.graphics.lerp(background, accent, 0.45f)))) {
    coverUrl?.let { CoverWash(it, accent, Modifier.fillMaxWidth().height(440.dp)) }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        state = grid,
        // Two tiles to a row, or one playlist to a line when Library shows them as a list.
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (asList) 1 else 2),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(if (asList) 0.dp else 10.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = if (asList) 0.dp else 10.dp),
    ) {
        item(key = "header", span = { full }) {
            PageHeader(if (spotify) "Spotify" else com.opentune.ui.theme.tr("Playlists"), onBack = onBack, actions = {
                // Sorting and the account in one glass pill, as on Library.
                Row(
                    Modifier.height(54.dp).glass(androidx.compose.foundation.shape.CircleShape).padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort playlists") }
                        androidx.compose.material3.DropdownMenu(sorting, { sorting = false }, shape = RoundedCornerShape(20.dp)) {
                            PlaylistOrder.entries.forEach { o ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(o.label, fontWeight = if (o == order) FontWeight.SemiBold else null) },
                                    trailingIcon = if (o == order) ({ Icon(Icons.Rounded.Check, null) }) else null,
                                    onClick = { order = o; sorting = false },
                                )
                            }
                        }
                    }
                    com.opentune.ui.components.AccountButton(nav.settings, plain = true)
                }
            })
        }
        // Bringing Spotify's latest over: a thin bar under the title while it runs.
        if (spotify && spotifySync.running) item(key = "syncing", span = { full }) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp))
        }
        // The same for YouTube Music's: a bar while it loads, and a line with a way to try again when it didn't.
        if (!spotify && library == YouTubeLoad.Loading) item(key = "yt-loading", span = { full }) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp))
        }
        if (!spotify && signedIn && library != YouTubeLoad.Loading) {
            val ytRows = shown.count { it.source == "YouTube Music" }
            val problem = (library as? YouTubeLoad.Failed)?.reason
                ?: if (ytRows == 0 && library == YouTubeLoad.Done) "No YouTube Music playlists were found on this account." else null
            if (problem != null) item(key = "yt-problem", span = { full }) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(problem, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.TextButton(onClick = {
                        loadLibrary()
                        if (library is YouTubeLoad.Failed && (library as YouTubeLoad.Failed).reason.contains("signed in")) nav.settings()
                    }) { Text(if ((library as? YouTubeLoad.Failed)?.reason?.contains("signed in") == true) "Settings" else "Try again") }
                }
            }
        }
        if (spotify) {
            if (shown.isEmpty()) item(key = "spotify-empty", span = { full }) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), null, Modifier.size(72.dp))
                    Text(
                        if (spotifyOn) "Your Spotify playlists appear here after the next sync." else "Connect Spotify to bring your playlists here, or import one from a link.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    androidx.compose.material3.Button(onClick = nav.importPlaylist) { Text(if (spotifyOn) "Import a playlist" else "Connect or import") }
                }
            }
            if (asList) items(shown.size, key = { shown[it].key }) { i -> PlaylistLine(shown[i], Modifier.animateItem()) }
            else items(shown.size, key = { shown[it].key }) { i -> PlaylistTile(shown[i], Modifier.animateItem()) }
        } else if (asList) {
            item(key = "new") { ActionLine(Icons.Rounded.Add, "New playlist", if (signedIn) "Here and on YouTube Music" else "On this phone") { naming = true } }
            item(key = "liked") { ActionLine(Icons.Rounded.Favorite, "Liked songs", com.opentune.i18n.Tr.f("{0} songs", liked.size), onClick = nav.liked) }
            items(shown.size, key = { shown[it].key }) { i -> PlaylistLine(shown[i], Modifier.animateItem()) }
        } else {
            item(key = "new") { NewPlaylistTile(signedIn, { naming = true }) }
            item(key = "liked") { LikedTile(liked.size, nav.liked) }
            items(shown.size, key = { shown[it].key }) { i -> PlaylistTile(shown[i], Modifier.animateItem()) }
        }
    }
    }
}

/** Where bringing the account's YouTube Music playlists up to date has got to, for the Show all page. */
private sealed interface YouTubeLoad {
    data object Idle : YouTubeLoad
    data object Loading : YouTubeLoad
    data object Done : YouTubeLoad
    data class Failed(val reason: String) : YouTubeLoad
}

/**
 * A cover blurred and spread wide, fading down into [into]. Before Android 12, which
 * can't blur, a tiny copy of it stretched to fill gives the same soft wash.
 */
@Composable
private fun CoverWash(url: String, into: Color, modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        val blurs = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        androidx.compose.animation.Crossfade(url, animationSpec = androidx.compose.animation.core.tween(800), label = "coverWash") { u ->
            Artwork(
                if (blurs) u else u.artworkAt(24),
                Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f; alpha = 0.8f }
                    .then(if (blurs) Modifier.blur(70.dp) else Modifier),
                shape = androidx.compose.ui.graphics.RectangleShape,
            )
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to into.copy(alpha = 0.1f), 0.6f to into.copy(alpha = 0.55f), 1f to into)))
    }
}

/** A tile that does something rather than open a playlist: a picture in a dark square, a name and a line under it. */
@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Brush? = null,
    art: @Composable () -> Unit,
) {
    Column(modifier.clip(MaterialTheme.shapes.large).then(Modifier.menuClickable(onClick = onClick, onLongClick = null)).padding(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)
                .then(if (background != null) Modifier.background(background) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)),
            Alignment.Center,
        ) { art() }
        TileText(title, subtitle)
    }
}

@Composable
private fun NewPlaylistTile(signedIn: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ActionTile("New playlist", if (signedIn) "Saved to YouTube Music" else "On this phone", onClick, modifier) {
        Icon(Icons.Rounded.Add, null, Modifier.size(44.dp))
    }
}

/** Liked songs as a playlist, in purple running into pink. */
@Composable
private fun LikedTile(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ActionTile("Liked Music", if (count == 0) "Auto playlist" else com.opentune.i18n.Tr.f("Auto playlist · {0} songs", count), onClick, modifier,
        background = Brush.verticalGradient(listOf(Color(0xFF8C6AF0), Color(0xFFF95AAE)))) {
        Icon(Icons.Rounded.ThumbUp, null, Modifier.size(72.dp), tint = Color.White)
    }
}

@Composable
private fun TileText(title: String, subtitle: String) {
    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** One of the four library shortcuts: an accent badge, a name and a count. */
@Composable
private fun Shortcut(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier, logo: Int? = null) {
    Row(
        modifier
            .height(72.dp)
            .pressable(onClick, 0.96f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logo != null) {
            // A service's own mark, in its own colours.
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(logo), null, Modifier.size(40.dp))
        } else Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
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
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().then(Modifier.menuClickable(onClick = onClick, onLongClick = onLongClick)).padding(horizontal = 16.dp, vertical = 6.dp),
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

/** A playlist as a line of the list: cover, name, where it's from; held down, it can be pinned. */
@Composable
private fun PlaylistLine(row: LibraryPlaylistRow, modifier: Modifier = Modifier) {
    PinnableRow(row.pin, modifier) { onLongClick ->
        PlaylistRow(
            row.url, row.title, row.subtitle, row.onClick,
            from = row.source.takeIf { it == "Spotify" },
            onLongClick = onLongClick,
            cover = if (row.local != null && row.url == null) ({
                com.opentune.ui.components.PlaylistCover(row.local.name, row.local.songs.map { it.thumbnailUrl }, Modifier.size(56.dp))
            }) else null,
        )
    }
}

/** A line of the list that does something rather than open a playlist: an icon in a square, a name and a line under it. */
@Composable
private fun ActionLine(icon: ImageVector?, title: String, subtitle: String, picture: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    PlaylistRow(null, title, subtitle, onClick, cover = {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
            if (picture != null) picture() else if (icon != null) Icon(icon, null, Modifier.size(26.dp))
        }
    })
}

/** A source's name over its playlists in the list. */
@Composable
private fun GroupLabel(name: String) {
    Text(
        name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Playlists in the list's groups: the pinned ones first, in the order pinned, then each source's, in [rows]' order. */
private fun playlistGroups(rows: List<LibraryPlaylistRow>, pinned: List<String>): List<Pair<String, List<LibraryPlaylistRow>>> {
    val pins = pinned.mapNotNull { key -> rows.firstOrNull { it.pin == key } }
    val rest = rows.filter { it !in pins }
    return buildList {
        if (pins.isNotEmpty()) add(PINNED to pins)
        listOf("Spotify", "YouTube Music", "On this phone").forEach { source ->
            rest.filter { it.source == source }.takeIf { it.isNotEmpty() }?.let { add(source to it) }
        }
    }
}

private const val PINNED = "Pinned"
/** How many of a source's playlists the list shows before "Show more". */
private const val LIST_FOLD = 5

/** A playlist as a tile, for the sideways rows; held down, it can be pinned. */
@Composable
private fun PlaylistTile(row: LibraryPlaylistRow, modifier: Modifier = Modifier) {
    PinnableRow(row.pin, modifier) { onLongClick ->
        Column(Modifier.clip(MaterialTheme.shapes.large).then(Modifier.menuClickable(onClick = row.onClick, onLongClick = onLongClick)).padding(6.dp)) {
            if (row.local != null && row.url == null) com.opentune.ui.components.PlaylistCover(row.title,
                row.local.songs.map { it.thumbnailUrl }, Modifier.fillMaxWidth().aspectRatio(1f))
            else Artwork(row.url.artworkAt(CARD_ART_PX), Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.large)
            TileText(row.title, if (row.local != null) "${row.source} · ${row.subtitle}" else row.subtitle)
        }
    }
}

/** [content] with a long press that offers to pin playlist [pin] to the top of Library, or unpin it. */
@Composable
private fun PinnableRow(pin: String, modifier: Modifier = Modifier, content: @Composable (onLongClick: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val pinned = AppSettings.ui.collectAsState().value.pinnedPlaylists.contains(pin)
    Box(modifier) {
        content { menu = true }
        androidx.compose.material3.DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp)) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(if (pinned) "Unpin" else "Pin to the top") },
                leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                onClick = { menu = false; LibraryPins.toggle(pin) },
            )
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
                Text(com.opentune.i18n.Tr.f("Makes a copy of {0} on YouTube Music, songs and all. Changes you make here go there too.", name))
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
    var query by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove && saved != null) com.opentune.ui.components.FloatingDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove this download?") },
        text = { Text(com.opentune.i18n.Tr.f("Deletes the downloaded songs of {0} from this phone and takes it out of your library's downloads.", saved.title)) },
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
                    Text(com.opentune.i18n.Tr.f("{0} of {1} songs downloaded", ready.size, saved.songIds.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        item { com.opentune.ui.browse.ListSearchField(query, { query = it }, { query = "" }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        items(inList.filter { it.song.title.contains(query.trim(), true) || it.song.artist.contains(query.trim(), true) }, key = { it.song.videoId }) { e ->
            val song = e.song.toSong()
            SongListItem(
                song = when (e.state) {
                    DownloadState.FAILED -> song.copy(durationText = "Failed · tap to retry")
                    DownloadState.QUEUED -> song.copy(durationText = if (com.opentune.data.download.Downloads.waitingForWifi(context)) "Waiting for Wi-Fi" else "Waiting")
                    DownloadState.DOWNLOADING -> song.copy(durationText = com.opentune.i18n.Tr.f("Downloading {0}%", (e.progress * 100).toInt()))
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

/** Songs saved for offline listening, newest first, including ones still on their way; and the playlists downloaded whole. */
@Composable
fun DownloadsScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenList: (String) -> Unit = {}) {
    val context = LocalContext.current
    val entries by Downloads.entries.collectAsState()
    val downloadedLists by com.opentune.data.download.DownloadedLists.lists.collectAsState()
    val list = remember(entries) { entries.values.sortedByDescending { it.addedAt } }
    val ready = remember(list) { list.filter { it.state == DownloadState.DONE }.map { it.song.toSong() } }
    // Downloaded lists with something of theirs still downloaded, and how much.
    val lists = remember(downloadedLists, entries) {
        downloadedLists.mapNotNull { l ->
            val have = l.songIds.count { entries[it]?.state == DownloadState.DONE }
            if (l.songIds.any { it in entries }) l to have else null
        }
    }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(DownloadsTab.SONGS) }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader(com.opentune.ui.theme.tr("Downloads"), onBack = onBack) }
        if (list.isEmpty()) {
            item {
                MessageState(Icons.Rounded.Download, "No downloads yet", Modifier.padding(top = 32.dp), message = "Pick Download from any song's menu to keep it for offline listening.")
            }
            return@LazyColumn
        }
        item {
            com.opentune.ui.components.PillSegmented(DownloadsTab.entries, tab, { it.label(list.size, lists.size) }, { tab = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (tab == DownloadsTab.PLAYLISTS) {
            if (lists.isEmpty()) item {
                MessageState(Icons.AutoMirrored.Rounded.QueueMusic, "No playlists downloaded", Modifier.padding(top = 32.dp),
                    message = "Use Download on a playlist or album page to keep the whole list offline.")
            }
            items(lists, key = { "list:${it.first.id}" }) { (l, have) ->
                PlaylistRow(l.thumbnailUrl, l.title, com.opentune.i18n.Tr.f("{0} of {1} downloaded · plays offline", have, l.songIds.size), { onOpenList(l.id) }, Modifier.animateItem())
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

private enum class DownloadsTab {
    SONGS, PLAYLISTS;
    fun label(songs: Int, lists: Int) = when (this) {
        SONGS -> com.opentune.i18n.Tr.f("Songs · {0}", songs)
        PLAYLISTS -> com.opentune.i18n.Tr.f("Playlists · {0}", lists)
    }
}

/** Songs liked in the app (and on YouTube Music, when signed in). */
@Composable
fun LikedScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenYouTubeLikes: () -> Unit) {
    val liked by LibraryStore.liked.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val songs = remember(liked) { liked.map { it.toSong() } }
    val selection = com.opentune.ui.components.rememberSongSelection()
    val context = LocalContext.current
    androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) {
    Box(Modifier.fillMaxSize()) {
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
    com.opentune.ui.components.SongSelectionBar(
        selection,
        rows = songs.map { it.videoId to it },
        onAddToQueue = { picked -> picked.forEach(actions.addToQueue) },
        modifier = Modifier.align(Alignment.TopCenter),
        onRemove = { keys ->
            val gone = songs.filter { it.videoId in keys }
            gone.forEach { LibraryStore.setLiked(it, false) }
            com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Removed {0} from Liked", gone.size), context)
        },
        removeLabel = "Unlike",
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
    val scope = rememberCoroutineScope()
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
    if (adding && playlist != null && playlist.spotifyId == null) AddSongsSheet(id, onDismiss = { adding = false })
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
    val search = remember(id) { com.opentune.ui.browse.PlaylistSearch() }
    val query = search.query
    val searching = search.open
    var order by remember(id) { mutableStateOf(com.opentune.ui.browse.ListOrder.DEFAULT) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(searching, query) {
        if (searching) listState.scrollToItem(0)
    }
    val matching = remember(songs, query, order) {
        val found = songs.indices.filter { songs[it].title.contains(query.trim(), true) || songs[it].artist.contains(query.trim(), true) }
        when (order) {
            com.opentune.ui.browse.ListOrder.DEFAULT -> found
            com.opentune.ui.browse.ListOrder.A_TO_Z -> found.sortedBy { songs[it].title.lowercase() }
            com.opentune.ui.browse.ListOrder.Z_TO_A -> found.sortedByDescending { songs[it].title.lowercase() }
        }
    }
    val shown = matching.map(songs::get)
    val online = signedIn && playlist.synced
    if (renaming) NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) { LibraryStore.renamePlaylist(id, it); renaming = false }
    if (deleting) {
        // Signed out, the copy on YouTube Music can't be reached, so it's left as it is.
        val linked = signedIn && remoteId != null
        var everywhere by remember { mutableStateOf(false) }
        FloatingDialog(
            onDismissRequest = { deleting = false },
            title = { Text(com.opentune.i18n.Tr.f("Delete \"{0}\"?", playlist.name)) },
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
    // From Spotify, its Spotify link; on YouTube Music, that link; otherwise (or signed out, or not made there yet) the songs themselves.
    val share: () -> Unit = {
        val spotifyId = playlist.spotifyId
        when {
            spotifyId != null -> com.opentune.ui.share.ListShare.shareSpotify(context, playlist.name, spotifyId,
                com.opentune.ui.share.ListShare.coversFor(playlist.coverUrl, songs), com.opentune.ui.share.ListShare.countLine(songs.size))
            !signedIn || remoteId == null -> com.opentune.ui.share.ListShare.shareLocal(context, playlist.name, songs)
            playlist.privacy?.linkWorks == true -> com.opentune.ui.share.ListShare.sharePlaylist(context, playlist.name, remoteId, com.opentune.ui.share.ListShare.coversFor(null, songs), com.opentune.ui.share.ListShare.countLine(songs.size))
            else -> privateShare = true
        }
    }
    val tools: @Composable (Modifier) -> Unit = { modifier ->
        ListTools(
            songs,
            onAddSongs = if (playlist.spotifyId == null) ({ adding = true }) else null,
            onShare = share,
            modifier = modifier,
            privacy = playlist.privacy,
            onPrivacy = if (online) ({ choosingPrivacy = true }) else null,
        )
    }
    if (moreOpen) LocalListMoreSheet(playlist, songs, actions,
        onRefresh = {
            // Reading the cookie starts the WebView engine: off the main thread.
            if (playlist.spotifyId != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.opentune.ui.spotify.SpotifyConnection.syncPlaylists(force = true) }
            else if (signedIn && remoteId != null) PlaylistSync.refresh(id)
        },
        onAddSongs = { adding = true }, onRename = { renaming = true }, onDelete = { deleting = true },
        onShare = share, onDismiss = { moreOpen = false })
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
                    waiting > 0 -> com.opentune.i18n.Tr.f("On YouTube Music · {0} to send", if (waiting == 1) "1 change" else "$waiting changes")
                    else -> "On YouTube Music"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        })
    }
    // Songs picked together (long press, then Select): the bar over the top acts on them.
    val selection = com.opentune.ui.components.rememberSongSelection()
    androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) {
    com.opentune.ui.components.GlassPage(Modifier.fillMaxSize().dismissPlaylistSearch(search), overlay = {
        com.opentune.ui.browse.PlaylistSearchHeader(search) {
        com.opentune.ui.browse.CollapsingBar(playlist.name, listState, onBack, forceCollapsed = searching,
            artworkBehind = !(playlist.coverUrl ?: songs.firstOrNull()?.thumbnailUrl).isNullOrBlank()) {
            Row(Modifier.height(48.dp).glass(androidx.compose.foundation.shape.CircleShape).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (searching) search.close() else search.open = true }) {
                    Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search this list")
                }
                Box {
                    IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    androidx.compose.material3.DropdownMenu(sortOpen, { sortOpen = false }) {
                        com.opentune.ui.browse.ListOrder.entries.forEach { value ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(value.label) }, onClick = { order = value; sortOpen = false })
                        }
                    }
                }
                IconButton(onClick = { moreOpen = true }) { Icon(Icons.Rounded.MoreHoriz, "More") }
            }
        }
        }
        val selectKeys = remember(songs) { songKeys(songs) }
        com.opentune.ui.components.SongSelectionBar(
            selection,
            rows = shown.indices.map { i -> selectKeys[matching[i]] to shown[i] },
            onAddToQueue = { picked -> picked.forEach(actions.addToQueue) },
            modifier = Modifier.align(Alignment.TopCenter),
            // Out of this playlist, from the bottom up so each place still points at its song.
            onRemove = if (playlist.spotifyId == null) ({ keys ->
                val at = songs.indices.filter { selectKeys[it] in keys }.sortedDescending()
                at.forEach { LibraryStore.removeFromPlaylist(id, it) }
                com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Removed {0} from {1}", at.size, playlist.name), context)
            }) else null,
            removeLabel = "Remove",
        )
    }) {
    val tint = com.opentune.ui.browse.rememberCoverTint((playlist.coverUrl ?: songs.firstOrNull()?.thumbnailUrl).artworkAt(CARD_ART_PX))
    val band = com.opentune.ui.components.rememberRubberBand()
    LazyColumn(state = listState, contentPadding = if (searching) PaddingValues(bottom = 16.dp) else contentPadding,
        modifier = Modifier.fillMaxSize().coverWash(tint).playlistSearchResults(search), overscrollEffect = band) {
        if (!searching) item(key = "header") {
            val full = com.opentune.ui.browse.fullListCover()
            androidx.compose.foundation.layout.BoxWithConstraints {
                com.opentune.ui.browse.HeaderBackdrop((playlist.coverUrl ?: songs.firstOrNull()?.thumbnailUrl).artworkAt(HEADER_ART_PX), Modifier.matchParentSize().pinnedToTop(band), into = tint)
                if (full) com.opentune.ui.browse.FullListCover(playlist.coverUrl.artworkAt(HEADER_ART_PX), Modifier.pinnedToTop(band),
                    cover = if (playlist.coverUrl == null) ({
                        com.opentune.ui.components.PlaylistCover(playlist.name, songs.map { it.thumbnailUrl }, Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, named = false)
                    }) else null)
                Column(
                    if (full) Modifier.fillMaxWidth().padding(top = maxWidth * com.opentune.ui.browse.FULL_COVER_TITLE_AT, start = 24.dp, end = 24.dp)
                    else Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    com.opentune.ui.browse.PlaylistHeading(playlist.name,
                        if (playlist.spotifyId != null) "Spotify" else if (playlist.synced) "YouTube Music" else "On this phone",
                        playlist.coverUrl, songs, cover = if (playlist.coverUrl == null) ({
                            com.opentune.ui.components.PlaylistCover(playlist.name, songs.map { it.thumbnailUrl }, Modifier.fillMaxSize(), MaterialTheme.shapes.large)
                        }) else null, showCover = !full)
                    com.opentune.ui.browse.ListPlayRow(shown.isNotEmpty(), false,
                        onPlay = { actions.playAll(shown, 0, false, playlist.name) },
                        onShuffle = { actions.playAll(shown, 0, true, playlist.name) },
                        onSmartShuffle = { actions.smartShuffle(shown, playlist.name) },
                        onSave = null,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp))
                    // The same row of tools as a YouTube Music playlist's page.
                    tools(Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    if (status != null && playlist.spotifyId == null) status()
                }
            }
        }
        if (songs.isEmpty()) {
            item {
                MessageState(Icons.AutoMirrored.Rounded.QueueMusic, "Nothing here yet", Modifier.padding(top = 32.dp), message = if (playlist.spotifyId != null) "Songs added in Spotify appear after the next sync." else "Add songs here, or use Add to playlist in any song's menu.")
            }
            return@LazyColumn
        }
        // Keyed by the song (and which copy of it), not its place, so a row taken out pops
        // alone and the rows below slide up rather than every row after it being redrawn.
        if (shown.isEmpty()) item { Text(com.opentune.ui.theme.tr("No matching songs"), Modifier.padding(24.dp)) }
        val keys = songKeys(songs)
        itemsIndexed(shown, key = { i, _ -> keys[matching[i]] }) { i, song ->
            SongListItem(
                song = song,
                onClick = { actions.playAll(shown, i, false, playlist.name) },
                modifier = Modifier.animateItem(),
                isCurrent = song.videoId == actions.currentVideoId,
                isPlaying = actions.isPlaying,
                onPlayNext = { actions.playNext(song) },
                onAddToQueue = { actions.addToQueue(song) },
                onRemoveFromPlaylist = if (playlist.spotifyId == null) ({ removeWithUndo(id, matching[i]) }) else null,
                // Swipe right to add to the queue, left to take it out of the playlist.
                onSwipeRemove = if (playlist.spotifyId == null) ({ removeWithUndo(id, matching[i]) }) else null,
                selectKey = keys[matching[i]],
            )
        }
    }
}
}
}

/**
 * A playlist page's own menu, as a YouTube Music playlist's has it: the list's cover and
 * name, then what can be done with it. A Spotify copy follows Spotify, so it can't be
 * added to, renamed or deleted here.
 */
@Composable
private fun LocalListMoreSheet(
    playlist: com.opentune.data.library.LocalPlaylist,
    songs: List<com.opentune.data.model.Song>,
    actions: SongActions,
    onRefresh: () -> Unit,
    onAddSongs: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val spotify = playlist.spotifyId != null
    val pinned = AppSettings.ui.collectAsState().value.pinnedPlaylists.contains(playlist.id)
    com.opentune.ui.components.FloatingCard(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        startExpanded = true,
        header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (playlist.coverUrl != null) Artwork(playlist.coverUrl.artworkAt(com.opentune.data.model.ROW_ART_PX), Modifier.size(56.dp))
                else com.opentune.ui.components.PlaylistCover(playlist.name, songs.map { it.thumbnailUrl }, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (spotify) "Spotify · " else "") + com.opentune.ui.share.ListShare.countLine(songs.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        },
    ) {
        val card = com.opentune.ui.components.LocalFloatingCard.current
        val close: () -> Unit = { card?.close() ?: onDismiss() }
        Column {
            com.opentune.ui.components.MenuRow(Icons.Rounded.Refresh, "Refresh") { close(); onRefresh() }
            com.opentune.ui.components.MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { close(); songs.asReversed().forEach(actions.playNext) }
            com.opentune.ui.components.MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { close(); songs.forEach(actions.addToQueue) }
            com.opentune.ui.components.MenuRow(Icons.Rounded.Download, "Download all") {
                close()
                songs.forEach { Downloads.enqueue(context, it) }
                com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Downloading {0} songs", songs.size), context)
            }
            if (!spotify) {
                com.opentune.ui.components.MenuRow(Icons.Rounded.Add, "Add songs") { close(); onAddSongs() }
                com.opentune.ui.components.MenuRow(Icons.Rounded.Edit, "Rename") { close(); onRename() }
            }
            com.opentune.ui.components.MenuRow(Icons.Rounded.Share, "Share") { close(); onShare() }
            com.opentune.ui.components.MenuRow(Icons.Rounded.PushPin, if (pinned) "Unpin from Library" else "Pin to the top of Library") { close(); LibraryPins.toggle(playlist.id) }
            if (!spotify) com.opentune.ui.components.MenuRow(Icons.Rounded.DeleteOutline, "Delete") { close(); onDelete() }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A key for each of [songs] that stays with the song when others are taken out or put back. */
internal fun songKeys(songs: List<com.opentune.data.model.Song>): List<String> {
    val seen = HashMap<String, Int>()
    return songs.map { s ->
        val copy = seen.merge(s.videoId, 1, Int::plus)!!
        "${s.videoId}#$copy"
    }
}

/** Takes the song at [index] out of playlist [id], with a few seconds to Undo it. */
private fun removeWithUndo(id: String, index: Int) {
    val removed = LibraryStore.removeUndoably(id, index) ?: return
    com.opentune.data.Pills.withAction(com.opentune.i18n.Tr.f("Removed \"{0}\"", removed.song.title), "Undo") { LibraryStore.undoRemove(removed) }
}
