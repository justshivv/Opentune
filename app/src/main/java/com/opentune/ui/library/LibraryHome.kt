package com.opentune.ui.library

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.animation.core.animateTo
import com.opentune.ui.components.pinnedToTop
import com.opentune.ui.components.menuClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import com.opentune.ui.components.glass
import com.opentune.ui.components.iconRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.opentune.ui.browse.playlistSearchResults
import com.opentune.ui.browse.coverWash
import com.opentune.ui.browse.dismissPlaylistSearch
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
import androidx.compose.ui.draw.drawWithContent
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
import com.opentune.data.library.LikedSync
import com.opentune.data.library.YtmSync
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
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
import com.opentune.ui.components.NowPlayingBars
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.PrivacySheet
import com.opentune.ui.components.PrivateShareDialog
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.meaning
import com.opentune.ui.components.pressable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

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

/** Liked Music's own colour (the purple of its cover), for the page behind it. */
internal val LIKED_SEED = Color(0xFF8C6AF0)

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
private val TILE_WIDTH = 168.dp
private val ARTIST_TILE_WIDTH = 104.dp

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
    for (p in playlists.filterNot { isYouTubeLikes(it.remoteId) || isYouTubeLikes(it.id) }) {
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
    val liked by LibraryStore.liked.collectAsState()
    val finished by Downloads.done.collectAsState(Downloads.doneNow())
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val libraryUi by AppSettings.ui.collectAsState()
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
    // The page's faces: the page itself, its playlists, its artists, its smart playlists, and Spotify's own.
    var tab by remember { mutableStateOf(0) }
    val spotifyOn by com.opentune.ui.spotify.SpotifyConnection.connected.collectAsState()
    val spotifySync by com.opentune.data.spotify.SpotifySync.status.collectAsState()
    // Opening Spotify's face asks Spotify what changed, behind the list already here.
    LaunchedEffect(tab, spotifyOn) {
        if (tab == 4 && spotifyOn) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.opentune.ui.spotify.SpotifyConnection.checkPlaylists()
        }
    }
    val doneCount = finished.size
    // One scroll state per face, so each face keeps its place while the faces swipe.
    val libraryListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val playlistsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val spotifyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val artistsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val smartListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The faces live in a pager: a swipe turns between them, and the pills animate to them.
    val switchState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = tab) { 5 }
    LaunchedEffect(pagerState) { androidx.compose.runtime.snapshotFlow { pagerState.currentPage }.collect { tab = it } }
    // "Show all" opens no page of its own any more: it turns to the Playlists face, from the top.
    val showAllPlaylists: () -> Unit = { scope.launch { pagerState.animateScrollToPage(1, animationSpec = androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing)); playlistsListState.scrollToItem(0) } }
    val showAllArtists: () -> Unit = { scope.launch { pagerState.animateScrollToPage(2, animationSpec = androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing)); artistsListState.scrollToItem(0) } }
    val rows = rememberPlaylistRows(nav)
    var spotifyOrder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(PlaylistOrder.LIBRARY) }
    var spotifySorting by remember { mutableStateOf(false) }
    val spotifyRows = remember(rows) { rows.filter { it.source == "Spotify" } }
    var playlistsOrder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(PlaylistOrder.LIBRARY) }
    var playlistsSorting by remember { mutableStateOf(false) }
    var smartOrder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SmartOrder.CREATED) }
    var smartSorting by remember { mutableStateOf(false) }
    val allRows = remember(rows) { rows.filter { it.source != "Spotify" } }
    val shownPlaylists = remember(allRows, playlistsOrder, libraryUi.pinnedPlaylists) {
        val pinned = allRows.filter { it.pin in libraryUi.pinnedPlaylists }
        pinned + playlistsOrder.sort(allRows.filter { it !in pinned })
    }
    val ytmSync by YtmSync.status.collectAsState()
    val records by History.records.collectAsState()
    val followed by com.opentune.data.releases.NewReleases.followed.collectAsState()
    // Your Artists: the followed ones, newest follow first.
    val followedArtists = remember(followed) { followed.sortedByDescending { it.followedAt } }
    val smartPlaylists by LibraryStore.smartPlaylists.collectAsState()
    val shownSmart = remember(smartPlaylists, smartOrder) { smartOrder.sort(smartPlaylists) }
    // Library's Recently played shelf: the merged history — this app's
    // own listens and the ones synced in from the account together, each
    // track once, latest first.
    val recentSongs = remember(records) { History.recents(records, 12) }
    LaunchedEffect(tab) { if (tab == 1) YtmSync.check(); if (tab == 2) com.opentune.data.releases.ArtistSync.sync() }
    // In the list, which sources show all their playlists rather than the first few.
    val openGroups = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // The pinned ones, then the most played; Show all has the rest. Spotify's have their own
    // page (the Spotify row above), so they show here only when pinned.
    val shownRows = remember(rows, libraryUi.pinnedPlaylists) { rows.filter { it.source != "Spotify" || it.pin in libraryUi.pinnedPlaylists } }
    val top = remember(shownRows, libraryUi.pinnedPlaylists) {
        val pinned = shownRows.filter { it.pin in libraryUi.pinnedPlaylists }
        (pinned + shownRows.filter { it !in pinned }.sortedByDescending { it.plays }).take(5)
    }

    // One row above the swiping faces: the faces' pills in a glass capsule on the left, the actions in theirs on the right.
    val header: @Composable () -> Unit = {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The faces' pills, in a glass capsule matching the actions'; as they scroll inside it, they dissolve at its ends.
            Row(
                Modifier.weight(1f).height(54.dp).glass(androidx.compose.foundation.shape.CircleShape).fadingEdges(switchState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibrarySwitch(tab, switchState, { scope.launch { pagerState.animateScrollToPage(it, animationSpec = androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing)) } })
            }
            Spacer(Modifier.width(10.dp))
            // History and the account side by side in one glass pill.
            Row(
                Modifier.height(54.dp).glass(androidx.compose.foundation.shape.CircleShape).padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // On the playlists, smart and Spotify faces the pill sorts instead, as on their old pages.
                when (tab) {
                    1 -> SortPill(playlistsOrder, playlistsSorting, { playlistsSorting = it }, { playlistsOrder = it })
                    3 -> SmartSortPill(smartOrder, smartSorting, { smartSorting = it }, { smartOrder = it })
                    4 -> SortPill(spotifyOrder, spotifySorting, { spotifySorting = it }, { spotifyOrder = it })
                    else -> IconButton(onClick = nav.history) { Icon(ImageVector.vectorResource(AppIcons.History), "Listening history") }
                }
                com.opentune.ui.components.AccountButton(nav.settings, plain = true)
            }
        }
    }

    // The faces run full-bleed under the transparent header; their content starts below it.
    val facePadding = PaddingValues(
        start = contentPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        end = contentPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        top = with(androidx.compose.ui.platform.LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() } + 76.dp,
        bottom = contentPadding.calculateBottomPadding(),
    )

    val libraryFace: @Composable () -> Unit = { LazyColumn(state = libraryListState, contentPadding = facePadding, modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(ImageVector.vectorResource(AppIcons.Favorite), "Liked", com.opentune.i18n.Tr.f("{0} songs", liked.size), nav.liked, Modifier.weight(1f))
                    Shortcut(ImageVector.vectorResource(AppIcons.Download), "Downloads", if (doneCount == 0) "Offline songs" else com.opentune.i18n.Tr.f("{0} songs", doneCount), nav.downloads, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(ImageVector.vectorResource(AppIcons.LibraryMusic), "On this phone", "Local files", nav.local, Modifier.weight(1f))
                    Shortcut(ImageVector.vectorResource(AppIcons.History), "Replay", "Your top songs", nav.replay, Modifier.weight(1f))
                }
                val server by Subsonic.server.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(ImageVector.vectorResource(AppIcons.Radio), "Radio", "Live stations", nav.radio, Modifier.weight(1f))
                    Shortcut(
                        ImageVector.vectorResource(AppIcons.Dns),
                        "Music server",
                        server?.let { it.url.substringAfter("://") } ?: "Subsonic, lossless",
                        nav.server,
                        Modifier.weight(1f),
                    )
                }
                val room by com.opentune.data.together.Together.room.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(
                        ImageVector.vectorResource(AppIcons.Groups),
                        "Listen together",
                        room?.let { if (it.hosting) com.opentune.i18n.Tr.f("Your room · {0} in", it.members.size) else com.opentune.i18n.Tr.f("In {0}'s room", it.hostName ?: com.opentune.i18n.Tr.t("a friend")) } ?: com.opentune.i18n.Tr.t("Rooms with friends, in sync"),
                        nav.together,
                        Modifier.weight(1f),
                    )
                }
            }
        }
        if (recentSongs.isNotEmpty()) {
            item(key = "recent-head") {
                SectionHeader("Recently played", action = {
                    TextButton(onClick = nav.history) { Text("Show all", style = MaterialTheme.typography.labelLarge) }
                })
            }
            item(key = "recent-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    itemsIndexed(recentSongs, key = { _, song -> song.videoId }) { index, song ->
                        RecentTile(song, Modifier.width(TILE_WIDTH).animateItem(), current = song.videoId == actions.currentVideoId, playing = actions.isPlaying) {
                            actions.playAll(recentSongs, index, false, "Recently played")
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("Playlists", action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (signedIn) {
                        IconButton(onClick = { pulled = false; refresh() }, enabled = !refreshing) {
                            if (refreshing) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(ImageVector.vectorResource(AppIcons.Refresh), "Refresh YouTube Music playlists", Modifier.size(20.dp))
                        }
                    }
                    TextButton(onClick = showAllPlaylists) { Text(if (libraryUi.playlistList) "View all" else "Show all", style = MaterialTheme.typography.labelLarge) }
                }
            })
        }
        if (libraryUi.playlistList) {
            // The list, as Library had it before the tiles: the pinned ones and each
            // source's own, five of each until "Show more".
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
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                items(top, key = { it.key }) { row -> PlaylistTile(row, Modifier.width(TILE_WIDTH).animateItem()) }
            }
        }
        item(key = "smart-playlists") { SmartPlaylistsRow(nav.smart, nav.newSmart, TILE_WIDTH) }
        if (followedArtists.isNotEmpty()) {
            item(key = "artists-head") {
                SectionHeader("Artists", action = {
                    TextButton(onClick = showAllArtists) { Text("Show all", style = MaterialTheme.typography.labelLarge) }
                })
            }
            item(key = "artists-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    items(followedArtists, key = { it.browseId }) { artist ->
                        ArtistTile(artist, Modifier.width(ARTIST_TILE_WIDTH).animateItem()) { nav.browse(artist.browseId) }
                    }
                }
            }
        }
    } }

    val playlistsFace: @Composable () -> Unit = { LazyColumn(state = playlistsListState, contentPadding = facePadding, modifier = Modifier.fillMaxSize()) {
        // YouTube Music's sync pill, general here: the whole check, as Spotify's face carries its own.
        item(key = "tab-yt-sync") { YtmSyncPill(ytmSync) }
        if (signedIn && !ytmSync.running) {
            val ytRows = shownPlaylists.count { it.source == "YouTube Music" }
            val signedOut = ytmSync.error?.contains("signed in") == true
            val problem = when {
                signedOut -> ytmSync.error
                ytRows == 0 && ytmSync.lastSynced > 0 && ytmSync.error == null -> "No YouTube Music playlists were found on this account."
                else -> null
            }
            if (problem != null) item(key = "tab-yt-problem") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(problem, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.TextButton(onClick = {
                        if (signedOut) nav.settings() else YtmSync.check(force = true)
                    }) { Text(if (signedOut) "Settings" else "Try again") }
                }
            }
        }
        if (libraryUi.playlistList) {
            item(key = "tab-new") { ActionLine(ImageVector.vectorResource(AppIcons.Add), "New playlist", if (signedIn) "Here and on YouTube Music" else "On this phone") { naming = true } }
            item(key = "tab-liked") { ActionLine(ImageVector.vectorResource(AppIcons.Favorite), "Liked songs", com.opentune.i18n.Tr.f("{0} songs", liked.size), onClick = nav.liked) }
            items(shownPlaylists, key = { it.key }) { row -> PlaylistLine(row, Modifier.animateItem()) }
        } else {
            item(key = "tab-tiles-head") {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NewPlaylistTile(signedIn, { naming = true }, Modifier.weight(1f))
                    LikedTile(liked.size, nav.liked, Modifier.weight(1f))
                }
            }
            items(shownPlaylists.chunked(2), key = { it.first().key }) { pair ->
                Row(Modifier.animateItem().padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { row -> PlaylistTile(row, Modifier.weight(1f)) }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    } }

    val spotifyFace: @Composable () -> Unit = { LazyColumn(state = spotifyListState, contentPadding = facePadding, modifier = Modifier.fillMaxSize()) {
        if (!spotifyOn) item(key = "spotify-connect") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), null, Modifier.size(72.dp))
                Text(
                    "Connect Spotify to bring your playlists here, or import one from a link.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                androidx.compose.material3.Button(onClick = nav.importPlaylist) { Text("Connect or import") }
            }
        } else {
            item(key = "spotify-sync") { com.opentune.ui.spotify.SpotifySyncPill(spotifySync) }
            // Under the sync pill: bringing a playlist over from a link.
            if (libraryUi.playlistList) item(key = "spotify-import") {
                ActionLine(null, "Import a playlist", "From a Spotify or YouTube Music link", onClick = nav.importPlaylist, picture = {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), null, Modifier.size(30.dp))
                })
            }
            val pinnedSpotify = spotifyRows.filter { it.pin in libraryUi.pinnedPlaylists }
            val shownSpotify = pinnedSpotify + spotifyOrder.sort(spotifyRows.filter { it !in pinnedSpotify })
            if (shownSpotify.isEmpty()) item(key = "spotify-waiting") {
                Text(
                    "Your Spotify playlists appear here after the next sync.",
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (libraryUi.playlistList) {
                items(shownSpotify, key = { it.key }) { row -> PlaylistLine(row, Modifier.animateItem()) }
            } else {
                // The import card is the grid's first cell, flowing with the playlists.
                val cells = listOf<LibraryPlaylistRow?>(null) + shownSpotify
                items(cells.chunked(2), key = { it.first()?.key ?: "spotify-import" }) { pair ->
                    Row(Modifier.animateItem().padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { row ->
                            if (row == null) {
                                ActionTile("Import Spotify", "From a playlist link", nav.importPlaylist, Modifier.weight(1f)) {
                                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), null, Modifier.size(64.dp))
                                }
                            } else {
                                PlaylistTile(row, Modifier.weight(1f))
                            }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
    } }

    val smartFace: @Composable () -> Unit = { LazyColumn(state = smartListState, contentPadding = facePadding, modifier = Modifier.fillMaxSize()) {
        item(key = "smart-head") {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmartTile("New smart playlist", "Fills itself from rules", nav.newSmart, Modifier.weight(1f), icon = ImageVector.vectorResource(AppIcons.Add))
                val first = shownSmart.firstOrNull()
                if (first != null) SmartTile(first.name, smartSummary(first.rules), { nav.smart(first.id) }, Modifier.weight(1f)) else Box(Modifier.weight(1f))
            }
        }
        items(shownSmart.drop(1).chunked(2), key = { it.first().id }) { pair ->
            Row(Modifier.animateItem().padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { p -> SmartTile(p.name, smartSummary(p.rules), { nav.smart(p.id) }, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    } }

    val artistsFace: @Composable () -> Unit = { LazyColumn(state = artistsListState, contentPadding = facePadding, modifier = Modifier.fillMaxSize()) {
        if (followedArtists.isEmpty()) {
            item(key = "artists-empty") {
                Text("Artists you follow will appear here — follow one from its page.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            items(followedArtists.chunked(3), key = { it.first().browseId }) { pair ->
                Row(Modifier.animateItem().padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { artist -> ArtistTile(artist, Modifier.weight(1f)) { nav.browse(artist.browseId) } }
                    for (rest in pair.size until 3) Box(Modifier.weight(1f))
                }
            }
        }
    } }

    // The active face's first playlist cover washes the page, as on the old pages.
    val framed: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            if (tab == 1) TabWash(shownPlaylists, playlistsListState)
            else if (tab == 4 && spotifyOn) TabWash(spotifyRows, spotifyListState)
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { face ->
                when (face) {
                    0 -> libraryFace()
                    1 -> playlistsFace()
                    2 -> artistsFace()
                    3 -> smartFace()
                    else -> spotifyFace()
                }
            }
            // The header floats over the faces, transparent: their content scrolls under its glass.
            header()
        }
    }
    // Pull to refresh only when there's an account to refresh from.
    if (signedIn) {
        com.opentune.ui.components.MarkRefreshBox(isRefreshing = refreshing && pulled, onRefresh = { pulled = true; refresh() }, modifier = Modifier.fillMaxSize()) { framed() }
    } else {
        framed()
    }
}

/** Behind a playlist face: the cover of the first playlist in view, blurred across the top and running down into its colour, following the scroll — as on the old pages. */
@Composable
private fun TabWash(rows: List<LibraryPlaylistRow>, listState: androidx.compose.foundation.lazy.LazyListState) {
    val byKey = remember(rows) { rows.associateBy { it.key } }
    val coverUrl by remember(byKey) {
        androidx.compose.runtime.derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
                byKey[item.key]?.let { it.url ?: it.local?.songs?.firstOrNull()?.thumbnailUrl }
            }?.artworkAt(CARD_ART_PX)
        }
    }
    val seed = com.opentune.ui.theme.rememberArtworkSeed(coverUrl)
    val background = MaterialTheme.colorScheme.background
    val accent by androidx.compose.animation.animateColorAsState(
        seed?.let { androidx.compose.ui.graphics.lerp(background, it, 0.24f) } ?: background,
        androidx.compose.animation.core.tween(800), label = "tabAccent",
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to accent, 0.6f to accent, 1f to androidx.compose.ui.graphics.lerp(background, accent, 0.45f)),
        ),
    ) {
        coverUrl?.let { CoverWash(it, accent, Modifier.fillMaxWidth().height(440.dp)) }
    }
}

/** The sort button and its menu, as the playlist pages carry in their top pill. */
@Composable
private fun SortPill(order: PlaylistOrder, sorting: Boolean, onSorting: (Boolean) -> Unit, onOrder: (PlaylistOrder) -> Unit) {
    Box {
        IconButton(onClick = { onSorting(true) }) { Icon(ImageVector.vectorResource(AppIcons.Sort), "Sort playlists") }
        androidx.compose.material3.DropdownMenu(sorting, { onSorting(false) }, shape = RoundedCornerShape(20.dp)) {
            PlaylistOrder.entries.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(o.label, fontWeight = if (o == order) FontWeight.SemiBold else null) },
                    trailingIcon = if (o == order) ({ Icon(ImageVector.vectorResource(AppIcons.Check), null) }) else null,
                    onClick = { onOrder(o); onSorting(false) },
                )
            }
        }
    }
}

/** The sort button for the smart face: the same pill, over the smart playlists' own orders. */
@Composable
private fun SmartSortPill(order: SmartOrder, sorting: Boolean, onSorting: (Boolean) -> Unit, onOrder: (SmartOrder) -> Unit) {
    Box {
        IconButton(onClick = { onSorting(true) }) { Icon(ImageVector.vectorResource(AppIcons.Sort), "Sort smart playlists") }
        androidx.compose.material3.DropdownMenu(sorting, { onSorting(false) }, shape = RoundedCornerShape(20.dp)) {
            SmartOrder.entries.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(o.label, fontWeight = if (o == order) FontWeight.SemiBold else null) },
                    trailingIcon = if (o == order) ({ Icon(ImageVector.vectorResource(AppIcons.Check), null) }) else null,
                    onClick = { onOrder(o); onSorting(false) },
                )
            }
        }
    }
}

/** A lazy row's edges, dissolved while there's more to reach that way: what slides to an end fades out instead of clipping against it, and an end already reached shows no fade. */
private fun Modifier.fadingEdges(state: androidx.compose.foundation.lazy.LazyListState): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = 28.dp.toPx()
        // Each edge's fade grows and melts with how much is hidden past it, so it
        // eases in and out as you scroll instead of snapping on and off.
        val info = state.layoutInfo
        // A fade only ever covers what is actually cut off at that edge: a pill
        // sitting whole inside the capsule is never dissolved. The left cut is the
        // list's own scroll offset — the one measure that stays true while the row
        // is mid-animation (item offsets lag a frame behind an animated scroll).
        val left = (state.firstVisibleItemScrollOffset / fade).coerceIn(0f, 1f)
        val last = info.visibleItemsInfo.lastOrNull()
        val right = if (last == null) 0f else ((last.offset + last.size - info.viewportSize.width) / fade).coerceIn(0f, 1f)
        // The row itself is clipped to the container's inner round (in
        // LibrarySwitch), so a pill leaves through a round window like the
        // container's outside; this only softens that round exit.
        if (left > 0f) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = left), Color.Transparent),
                    startX = 0f,
                    endX = fade,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
            )
        }
        if (right > 0f) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = right)),
                    startX = size.width - fade,
                    endX = size.width,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
            )
        }
    }

/** The pills where Library's title was: the page itself, its playlists, and Spotify's own face. */
@Composable
private fun LibrarySwitch(tab: Int, rowState: androidx.compose.foundation.lazy.LazyListState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    // The selected pill is always kept in sight: the row scrolls it into view on its own.
    LaunchedEffect(tab) { rowState.animateScrollToItem(tab) }
    androidx.compose.foundation.lazy.LazyRow(
        state = rowState,
        modifier = modifier.fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(50)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { SwitchPill(com.opentune.ui.theme.tr("Library"), selected = tab == 0, glyph = { tint -> Icon(ImageVector.vectorResource(AppIcons.LibraryMusic), null, Modifier.size(20.dp), tint = tint) }) { onSelect(0) } }
        item { SwitchPill("Playlists", selected = tab == 1, glyph = { tint -> Icon(ImageVector.vectorResource(AppIcons.PlaylistPlay), null, Modifier.size(20.dp), tint = tint) }) { onSelect(1) } }
        item { SwitchPill("Artists", selected = tab == 2, glyph = { tint -> Icon(ImageVector.vectorResource(AppIcons.Person), null, Modifier.size(20.dp), tint = tint) }) { onSelect(2) } }
        item { SwitchPill("Smart playlists", selected = tab == 3, glyph = { tint -> Icon(ImageVector.vectorResource(AppIcons.AutoAwesome), null, Modifier.size(20.dp), tint = tint) }) { onSelect(3) } }
        item {
            SwitchPill("Spotify", selected = tab == 4, glyph = {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), null, Modifier.size(20.dp))
            }) { onSelect(4) }
        }
    }
}

@Composable
private fun SwitchPill(label: String, selected: Boolean, glyph: @Composable (Color) -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val content = if (selected) scheme.surface else scheme.onSurfaceVariant
    Row(
        modifier.clip(RoundedCornerShape(50)).background(if (selected) scheme.onSurface else Color.Transparent)
            .clickable(onClick = onClick).height(44.dp).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        glyph(content)
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = content)
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

/** The orders the smart face can put its playlists in. */
private enum class SmartOrder(val label: String) {
    CREATED("As created"), NEWEST("Newest first"), NAME("A to Z"), NAME_REVERSED("Z to A");

    fun sort(lists: List<com.opentune.data.library.SmartPlaylist>): List<com.opentune.data.library.SmartPlaylist> = when (this) {
        CREATED -> lists
        NEWEST -> lists.sortedByDescending { it.createdAt }
        NAME -> lists.sortedBy { it.name.lowercase() }
        NAME_REVERSED -> lists.sortedByDescending { it.name.lowercase() }
    }
}


/**
 * A cover blurred and spread wide, fading down into [into]. Before Android 12, which
 * can't blur, a tiny copy of it stretched to fill gives the same soft wash.
 */
@Composable
internal fun CoverWash(url: String, into: Color, modifier: Modifier) {
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
        Icon(ImageVector.vectorResource(AppIcons.Add), null, Modifier.size(44.dp))
    }
}

/** Liked songs as a playlist, in purple running into pink. */
@Composable
private fun LikedTile(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ActionTile("Liked Music", if (count == 0) "Auto playlist" else com.opentune.i18n.Tr.f("Auto playlist · {0} songs", count), onClick, modifier,
        background = Brush.verticalGradient(listOf(Color(0xFF8C6AF0), Color(0xFFF95AAE)))) {
        Icon(ImageVector.vectorResource(AppIcons.ThumbUp), null, Modifier.size(72.dp), tint = Color.White)
    }
}

@Composable
private fun TileText(title: String, subtitle: String) {
    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                Icon(ImageVector.vectorResource(AppIcons.QueueMusic), null)
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
        Column(Modifier.clip(MaterialTheme.shapes.medium).then(Modifier.menuClickable(onClick = row.onClick, onLongClick = onLongClick)).padding(6.dp)) {
            if (row.local != null && row.url == null) com.opentune.ui.components.PlaylistCover(row.title,
                row.local.songs.map { it.thumbnailUrl }, Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.medium, named = false)
            else Artwork(row.url.artworkAt(CARD_ART_PX), Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.medium)
            TileText(row.title, if (row.local != null) "${row.source} · ${row.subtitle}" else row.subtitle)
        }
    }
}

/** A followed artist: its picture in a circle, its name under it. A tap opens its page. */
@Composable
private fun ArtistTile(artist: com.opentune.data.releases.NewReleases.Followed, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(MaterialTheme.shapes.medium).then(Modifier.menuClickable(onClick = onClick, onLongClick = {})).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(artist.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.fillMaxWidth().aspectRatio(1f), androidx.compose.foundation.shape.CircleShape)
        Text(
            artist.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

/** A recently played song on Library's shelf: its cover, its name and artist under it. A tap plays the shelf from that song. The song the player has now wears a small badge of level bars over its corner: dancing while it plays, resting low while it is paused. */
@Composable
private fun RecentTile(song: Song, modifier: Modifier = Modifier, current: Boolean = false, playing: Boolean = false, onPlay: () -> Unit) {
    Column(modifier.clip(MaterialTheme.shapes.medium).then(Modifier.menuClickable(onClick = onPlay, onLongClick = {})).padding(6.dp)) {
        Box {
            Artwork(song.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.medium)
            if (current) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center,
                ) {
                    NowPlayingBars(playing = playing, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        TileText(song.title, song.artist)
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
                leadingIcon = { Icon(ImageVector.vectorResource(AppIcons.PushPin), null) },
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
    // The page's background as Settings ask for it, from the downloaded list's cover.
    com.opentune.ui.browse.ListPageTint(coverUrl = saved?.thumbnailUrl) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            com.opentune.ui.browse.ListPageWash()
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item { PageHeader(saved?.title ?: "Downloaded", onBack = onBack) }
                if (saved == null || inList.isEmpty()) {
                    item { MessageState(ImageVector.vectorResource(AppIcons.Download), "Nothing downloaded here", Modifier.padding(top = 32.dp), message = "Its songs were removed from Downloads.") }
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
    // Songs can be picked together, as on a playlist: long-press one and Select, then remove
    // the picked downloads (or add them to a playlist or the queue) at once.
    val selection = com.opentune.ui.components.rememberSongSelection()
    val selectionRows = remember(list) { list.map { it.song.videoId to it.song.toSong() } }
    LaunchedEffect(tab) { if (tab != DownloadsTab.SONGS) selection.clear() }
    androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item { PageHeader(com.opentune.ui.theme.tr("Downloads"), onBack = onBack) }
                if (list.isEmpty()) {
                    item {
                        MessageState(ImageVector.vectorResource(AppIcons.Download), "No downloads yet", Modifier.padding(top = 32.dp), message = "Pick Download from any song's menu to keep it for offline listening.")
                    }
                    return@LazyColumn
                }
                item {
                    com.opentune.ui.components.PillSegmented(DownloadsTab.entries, tab, { it.label(list.size, lists.size) }, { tab = it },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                }
                if (tab == DownloadsTab.PLAYLISTS) {
                    if (lists.isEmpty()) item {
                        MessageState(ImageVector.vectorResource(AppIcons.QueueMusic), "No playlists downloaded", Modifier.padding(top = 32.dp),
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
            com.opentune.ui.components.SongSelectionBar(
                selection,
                rows = selectionRows,
                onAddToQueue = { picked -> picked.forEach(actions.addToQueue) },
                modifier = Modifier.align(Alignment.TopCenter),
                onRemove = { ids ->
                    ids.forEach { Downloads.remove(context, it) }
                    com.opentune.data.Pills.done(
                        if (ids.size == 1) com.opentune.i18n.Tr.t("Removed 1 download") else com.opentune.i18n.Tr.f("Removed {0} downloads", ids.size),
                        context,
                    )
                },
                removeLabel = "Remove download",
                offerDownload = false,
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

/**
 * The playlist pages' top bar: transparent over a page's hero, then solid —
 * status bar included — once [solid] says the hero has scrolled away. One
 * small box fading between two colours, so it costs nothing per frame.
 */
@Composable
fun TopNavBar(
    title: String,
    solid: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    // Once the hero has scrolled away the bar frosts in: the songs under it blur and take the
    // page's colour, and the frost thins out over a short tail below the bar rather than
    // ending at a hard line. Where blur is off, a near-solid wash of the page colour instead.
    val frost by androidx.compose.animation.core.animateFloatAsState(if (solid) 1f else 0f, androidx.compose.animation.core.tween(240), label = "topNavFrost")
    val haze = com.opentune.ui.components.LocalHazeState.current
    val page = MaterialTheme.colorScheme.background
    // The bar's frost answers to the Frosted top edge setting, like the
    // strip at the root does: with it off, the bar is the plain page
    // colour and nothing blurs under it.
    val edgeFrost = AppSettings.ui.collectAsState().value.frostedTopEdge
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var tall by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val below = LocalNavFrostBelow.current
    // On a washed page the frost paints the page's own wash (see
    // LocalPageWash), mapped to the screen's height so its colours line
    // up with the backdrop's at every point; a flat colour never could.
    val wash = com.opentune.ui.components.LocalPageWash.current
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * density
    Box(modifier.fillMaxWidth()) {
        if (frost > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .layout { measurable, constraints ->
                        // Drawn taller than the bar by the tail, and by whatever sits under it as part
                        // of it (a list's search field); the bar's own size is unchanged.
                        val h = constraints.maxHeight + below.roundToPx() + NAV_FROST_TAIL.roundToPx()
                        val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                    }
                    .onSizeChanged { tall = it.height }
                    .then(
                        if (!edgeFrost) {
                            // No blur at all: the page colour, solid over the bar
                            // itself and easing out over the tail below it.
                            if (wash == null) {
                                Modifier.graphicsLayer { alpha = frost }.drawBehind {
                                    val solidTo = ((size.height - (below.value + NAV_FROST_TAIL.value) * density) / size.height).coerceIn(0f, 1f)
                                    drawRect(Brush.verticalGradient(0f to page, solidTo to page, 1f to page.copy(alpha = 0f)))
                                }
                            } else {
                                // The wash, masked out over the tail in an offscreen
                                // layer (the cover fades' own technique): an erase
                                // blend in the open would eat through the page
                                // under the bar once the frost sits at full alpha.
                                Modifier.graphicsLayer { alpha = frost; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
                                    drawContent()
                                    val solidTo = ((size.height - (below.value + NAV_FROST_TAIL.value) * density) / size.height).coerceIn(0f, 1f)
                                    drawPageWash(wash, screenHeight, 1f)
                                    drawRect(Brush.verticalGradient(0f to Color.Black, solidTo to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                                }
                            }
                        } else if (haze == null) {
                            if (wash == null) {
                                Modifier.graphicsLayer { alpha = frost }.drawBehind {
                                    drawRect(Brush.verticalGradient(0f to page.copy(alpha = 0.95f), 0.72f to page.copy(alpha = 0.88f), 1f to page.copy(alpha = 0f)))
                                }
                            } else {
                                Modifier.graphicsLayer { alpha = frost; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
                                    drawContent()
                                    drawPageWash(wash, screenHeight, 0.95f)
                                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.72f to Color.Black.copy(alpha = 0.926f), 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                                }
                            }
                        } else {
                            Modifier.hazeEffect(haze) {
                                alpha = frost
                                // The blur already samples the real backdrop; over a
                                // wash the tint only veils it, kept thin so the
                                // backdrop's own colours stay in charge.
                                backgroundColor = wash?.tint ?: page
                                tints = listOf(dev.chrisbanes.haze.HazeTint((wash?.tint ?: page).copy(alpha = if (wash == null) 0.5f else 0.22f)))
                                blurRadius = 28.dp
                                noiseFactor = 0.03f
                                progressive = dev.chrisbanes.haze.HazeProgressive.verticalGradient(
                                    easing = androidx.compose.animation.core.EaseIn,
                                    startY = tall - NAV_FROST_TAIL.value * density * 1.6f,
                                    startIntensity = 1f,
                                    endY = tall.toFloat(),
                                    endIntensity = 0f,
                                )
                            }
                        },
                    ),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp)
                        .then(if (solid) Modifier else Modifier.glass(androidx.compose.foundation.shape.CircleShape))
                        .clickable(onClickLabel = "Back", onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ImageVector.vectorResource(AppIcons.ArrowBack), "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                if (solid) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                actions()
            }
        }
    }
}

/**
 * A page's wash painted in a top bar's frost: the same gradient and glow
 * the page draws behind itself, mapped to the screen's height so the
 * colours line up with the backdrop at every point, at [alpha]. The
 * caller masks it out over the frost's tail.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPageWash(
    wash: com.opentune.ui.components.PageWash,
    screenHeight: Float,
    alpha: Float,
) {
    drawRect(Brush.verticalGradient(*com.opentune.ui.browse.pageWashStops(wash.tint, wash.base), startY = 0f, endY = screenHeight), alpha = alpha)
    drawRect(
        Brush.radialGradient(
            listOf(wash.glow.copy(alpha = 0.65f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, screenHeight * 0.3f),
            radius = maxOf(size.width, screenHeight * 0.7f).coerceAtLeast(1f),
        ),
        alpha = alpha,
    )
}

/** How far a top bar's frost reaches below it, thinning out. */
private val NAV_FROST_TAIL = 22.dp

/**
 * How far below the top bar its frost reaches before its soft tail: the height of what sits
 * under the bar as part of it, a list's search field. One frost for both, so the field doesn't
 * sit on a band of its own colour with a hard line where the bar's frost ends.
 */
internal val LocalNavFrostBelow = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** Songs liked in the app (and on YouTube Music, when signed in). */
@Composable
fun LikedScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val liked by LibraryStore.liked.collectAsState()
    val storeReady by LibraryStore.ready.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    LaunchedEffect(signedIn) { if (signedIn) LikedSync.sync() }
    val songs = remember(liked) { liked.map { it.toSong() } }
    // The library's whole length, from the same durations the rows show.
    val totalSeconds = remember(songs) {
        songs.sumOf { song ->
            val parts = song.durationText?.split(':')?.mapNotNull { it.trim().toLongOrNull() } ?: return@sumOf 0L
            when (parts.size) {
                1 -> parts[0]
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3_600 + parts[1] * 60 + parts[2]
                else -> 0L
            }
        }
    }
    // The library is revealed a page at a time: with a thousand songs, laying
    // out every row up front is what makes the page stutter. A loader at the
    // end of what's shown brings in the next page, then moves down with it.
    // Not keyed on the library: a sync landing mid-scroll re-emits it, and
    // resetting the count there would collapse the list back to one page.
    var shownCount by remember { androidx.compose.runtime.mutableIntStateOf(PAGE_SIZE) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val band = com.opentune.ui.components.rememberRubberBand()
    // The top bar turns solid once the hero's title zone has scrolled under it:
    // a derived flag, so it costs a recomposition only when it flips.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heroHeightPx = with(density) { (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * HERO_FRACTION).dp.toPx() }
    val barHeightPx = with(density) { 56.dp.toPx() }
    // Keyed on the sizes, so turning the phone works the threshold out again.
    val barSolid by remember(heroHeightPx, barHeightPx) {
        androidx.compose.runtime.derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroHeightPx - barHeightPx
        }
    }
    val selection = com.opentune.ui.components.rememberSongSelection()
    val context = LocalContext.current
    // Looking through the library, its order, and the page's own menu, as the
    // playlist pages have them.
    val search = remember { com.opentune.ui.browse.PlaylistSearch() }
    val searching = search.open
    val query = search.query
    var order by remember { mutableStateOf(com.opentune.ui.browse.ListOrder.RECENT) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    LaunchedEffect(searching, query) {
        if (searching) listState.scrollToItem(0)
    }
    val shown = remember(songs, query, order) {
        val q = query.trim()
        val found = if (q.isEmpty()) songs else songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
        when (order) {
            com.opentune.ui.browse.ListOrder.DEFAULT -> found
            // Liked is kept newest like first already.
            com.opentune.ui.browse.ListOrder.RECENT -> found
            com.opentune.ui.browse.ListOrder.A_TO_Z -> found.sortedBy { it.title.lowercase() }
            com.opentune.ui.browse.ListOrder.Z_TO_A -> found.sortedByDescending { it.title.lowercase() }
        }
    }
    val selectionRows = remember(shown) { shown.map { it.videoId to it } }

    // The page's background as Settings ask for it: from the newest liked song's cover when the
    // Liked cover setting is on, or Liked Music's own purple.
    // With the setting off it is coloured from the heart cover's own purple, as the cover is.
    val likedCoverUrl = if (AppSettings.ui.collectAsState().value.likedCoverFromLatest) {
        songs.firstOrNull()?.thumbnailUrl?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    com.opentune.ui.browse.ListPageTint(coverUrl = likedCoverUrl, seed = LIKED_SEED) {
        // The page's menu is composed inside the tint, so its sheet wears the page's colours.
        if (moreOpen) LikedMoreSheet(songs, actions) { moreOpen = false }
        androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) {
            com.opentune.ui.components.GlassPage(Modifier.fillMaxSize().dismissPlaylistSearch(search), overlay = {
                com.opentune.ui.browse.PlaylistSearchHeader(search) {
                    val returning = com.opentune.ui.browse.rememberSearchReturn(search, listState) { i, o -> i > 0 || o > heroHeightPx - barHeightPx }
                    val topSolid = searching || barSolid || returning
                    TopNavBar("Liked Music", topSolid, onBack) {
                        Row(Modifier.height(48.dp).then(if (topSolid) Modifier else Modifier.glass(androidx.compose.foundation.shape.CircleShape)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (searching) search.close() else search.open = true }) {
                                Icon(if (searching) ImageVector.vectorResource(AppIcons.Close) else ImageVector.vectorResource(AppIcons.Search), "Search this list")
                            }
                            Box {
                                IconButton(onClick = { sortOpen = true }) { Icon(ImageVector.vectorResource(AppIcons.Sort), "Sort") }
                                com.opentune.ui.browse.ListSortMenu(sortOpen, order, onPick = { order = it; sortOpen = false }, onDismiss = { sortOpen = false })
                            }
                            IconButton(onClick = { moreOpen = true }) { Icon(ImageVector.vectorResource(AppIcons.MoreHoriz), "More") }
                        }
                    }
                }
                com.opentune.ui.components.SongSelectionBar(
                    selection,
                    rows = selectionRows,
                    onAddToQueue = { picked -> picked.forEach(actions.addToQueue) },
                    modifier = Modifier.align(Alignment.TopCenter),
                    onRemove = { keys ->
                        val gone = songs.filter { it.videoId in keys }
                        gone.forEach { LibraryStore.setLiked(it, false) }
                        com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Removed {0} from Liked", gone.size), context)
                    },
                    removeLabel = "Unlike",
                )
            }) {
                // The cover's blur and fade behind the songs, when Settings ask for the cover-coloured page.
                com.opentune.ui.browse.ListPageWash()
                // The hero runs to the very top of the screen, so the list keeps only the
                // bottom inset (room for the dock); the hero carries its own top shade.
                LazyColumn(
                    state = listState,
                    overscrollEffect = band,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    modifier = Modifier.fillMaxSize().playlistSearchResults(search),
                ) {
                    if (!storeReady) {
                        item { LikedSkeleton(band, listState) }
                        items(8) { com.opentune.ui.components.SongRowPlaceholder() }
                        return@LazyColumn
                    }
                    if (!searching) item(key = "liked-hero") {
                        LikedHero(
                            songs.firstOrNull(), songs.size, totalSeconds,
                            onPlay = { actions.playAll(shown, 0, false, "Liked Music") },
                            onShuffle = { actions.playAll(shown, 0, true, "Liked Music") },
                            onSmartShuffle = { actions.smartShuffle(shown, "Liked Music") },
                            band = band,
                            listState = listState,
                        )
                    }

                    if (songs.isEmpty()) {
                        item {
                            MessageState(ImageVector.vectorResource(AppIcons.Favorite), "No liked songs", Modifier.padding(top = 32.dp), message = "Tap the heart in the player, or Like in a song's menu.")
                        }
                        return@LazyColumn
                    }
                    if (shown.isEmpty()) item { Text(com.opentune.ui.theme.tr("No matching songs"), Modifier.padding(24.dp)) }
                    itemsIndexed(shown.take(shownCount), key = { _, s -> s.videoId }) { i, song ->
                        SongListItem(
                            song = song,
                            onClick = { actions.playAll(shown, i, false, "Liked Music") },
                            modifier = Modifier.animateItem(),
                            isCurrent = song.videoId == actions.currentVideoId,
                            isPlaying = actions.isPlaying,
                            onPlayNext = { actions.playNext(song) },
                            onAddToQueue = { actions.addToQueue(song) },
                        )
                    }
                    if (shownCount < shown.size) {
                        item(key = "liked-more") {
                            // Reached the end of what's shown: the app's loader turns
                            // for a beat, the next page lands, and the loader moves
                            // down with the end (or is gone, on the last page).
                            LaunchedEffect(shownCount) {
                                kotlinx.coroutines.delay(700)
                                shownCount = minOf(shownCount + PAGE_SIZE, shown.size)
                            }
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Liked page's own menu, as a playlist page's menu: the cover and the
 * name, then what can be done with the whole library.
 */
@Composable
private fun LikedMoreSheet(
    songs: List<Song>,
    actions: SongActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    com.opentune.ui.components.FloatingCard(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        expandable = true,
        startExpanded = false,
        fullFraction = 1f,
        header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                com.opentune.ui.components.LikedPlaylistCover(Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Liked Music", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.opentune.ui.share.ListShare.countLine(songs.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        },
    ) {
        val card = com.opentune.ui.components.LocalFloatingCard.current
        val close: () -> Unit = { card?.close() ?: onDismiss() }
        Column {
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.PlaylistPlay), "Play next") {
                close()
                songs.asReversed().forEach(actions.playNext)
            }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.QueueMusic), "Add to queue") {
                close()
                songs.forEach(actions.addToQueue)
            }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Download), "Download all") {
                close()
                songs.forEach { com.opentune.data.download.Downloads.enqueue(context, it) }
                com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Downloading {0} songs", songs.size), context)
            }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Share), "Share") {
                close()
                com.opentune.ui.share.ListShare.shareLocal(context, "Liked Music", songs)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The Liked page while the stored library is still being read: the hero's
 * shape as a skeleton - the cover across the whole top, fading at its foot,
 * with the title, the counts and the two buttons as bars over the fade.
 */
@Composable
private fun LikedSkeleton(band: com.opentune.ui.components.RubberBandOverscroll? = null, listState: androidx.compose.foundation.lazy.LazyListState? = null) {
    com.opentune.ui.browse.ListHero(cover = { m -> com.opentune.ui.components.Placeholder(m, androidx.compose.ui.graphics.RectangleShape) }, band = band, listState = listState) {
        run {
            com.opentune.ui.components.Placeholder(Modifier.width(200.dp).height(26.dp))
            com.opentune.ui.components.Placeholder(Modifier.padding(top = 10.dp).width(140.dp).height(16.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                com.opentune.ui.components.Placeholder(Modifier.weight(1f).height(52.dp), androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                com.opentune.ui.components.Placeholder(Modifier.weight(1f).height(52.dp), androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** How many liked songs the page lays out at a time; see LikedScreen. */
private const val PAGE_SIZE = 50

/** How much of the screen's height the Liked hero takes. */
private const val HERO_FRACTION = 0.68f

/**
 * The Liked page's hero: its cover is Liked Music's own (the thumb on its
 * purple-pink gradient), or the newest liked song's artwork when Settings ask
 * for it, full-bleed or framed as Settings ask, with the title and the
 * library's size and the play buttons.
 */
@Composable
private fun LikedHero(
    latest: Song?, count: Int, totalSeconds: Long,
    onPlay: () -> Unit, onShuffle: () -> Unit, onSmartShuffle: () -> Unit,
    band: com.opentune.ui.components.RubberBandOverscroll? = null,
    listState: androidx.compose.foundation.lazy.LazyListState? = null,
) {
    // The newest song's art when Settings ask for it and there is one;
    // otherwise Liked Music's own heart cover, as on YouTube Music.
    val fromLatest = AppSettings.ui.collectAsState().value.likedCoverFromLatest
    val latestUrl = latest?.thumbnailUrl?.takeIf { it.isNotBlank() }
    com.opentune.ui.browse.ListHero(
        cover = { m ->
            if (fromLatest && latestUrl != null) Artwork(latestUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX), m, shape = androidx.compose.ui.graphics.RectangleShape)
            else com.opentune.ui.components.LikedPlaylistCover(m, androidx.compose.ui.graphics.RectangleShape)
        },
        footPadding = 18.dp,
        band = band,
        listState = listState,
    ) {
        run {
            Text(
                "Liked Music",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (count > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    com.opentune.i18n.Tr.f("{0} songs", count) + "  \u2022  " + formatTotalDuration(totalSeconds),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                com.opentune.ui.browse.ListPlayRow(
                    enabled = true,
                    saved = false,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onSmartShuffle = onSmartShuffle,
                    onSave = null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A library's whole length the way playlist pages show it: "3d 4h 12m", "4h 12m", "12m". */
private fun formatTotalDuration(totalSeconds: Long): String {
    val days = totalSeconds / 86_400
    val hours = totalSeconds % 86_400 / 3_600
    val minutes = totalSeconds % 3_600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h ${minutes}m"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

/**
 * A playlist's hero, in the Liked page's shape: the cover across the whole
 * top, the name and counts over its fade, then every action the page has —
 * the play row, the tools and the status line — inside it.
 */
@Composable
private fun PlaylistHero(
    name: String,
    source: String,
    meta: String,
    coverUrl: String?,
    songs: List<Song>,
    buttons: @Composable () -> Unit,
    tools: @Composable (Modifier) -> Unit,
    status: (@Composable () -> Unit)?,
    band: com.opentune.ui.components.RubberBandOverscroll? = null,
    listState: androidx.compose.foundation.lazy.LazyListState? = null,
) {
    com.opentune.ui.browse.ListHero(
        cover = { m ->
            if (coverUrl != null) Artwork(coverUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX), m, shape = androidx.compose.ui.graphics.RectangleShape)
            else com.opentune.ui.components.PlaylistCover(name, songs.map { it.thumbnailUrl }, m, androidx.compose.ui.graphics.RectangleShape, named = false)
        },
        band = band,
        listState = listState,
    ) {
        run {
            Text(name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (source.isNotBlank()) Text(source, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Text(meta, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            buttons()
            tools(Modifier.fillMaxWidth().padding(top = 12.dp))
            if (status != null) {
                Spacer(Modifier.height(10.dp))
                status()
            }
            Spacer(Modifier.height(16.dp))
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
    var order by remember(id) { mutableStateOf(com.opentune.ui.browse.ListOrder.RECENT) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val band = com.opentune.ui.components.rememberRubberBand()
    LaunchedEffect(searching, query) {
        if (searching) listState.scrollToItem(0)
    }
    val matching = remember(songs, query, order) {
        val found = songs.indices.filter { songs[it].title.contains(query.trim(), true) || songs[it].artist.contains(query.trim(), true) }
        when (order) {
            com.opentune.ui.browse.ListOrder.DEFAULT -> found
            // A Spotify list's songs carry when they were added there: newest first by that stamp.
            // A local playlist's songs join at the end: newest first is the list from the bottom up.
            com.opentune.ui.browse.ListOrder.RECENT ->
                if (playlist.spotifyId != null) found.sortedByDescending { playlist.songs[it].addedAt }
                else found.asReversed()
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
            onShare = null,
            modifier = modifier,
            showDownload = false,
            privacy = playlist.privacy,
            onPrivacy = null,
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
                Icon(ImageVector.vectorResource(AppIcons.CloudUpload), null, Modifier.size(18.dp))
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
    // The Liked cover setting covers Spotify's Liked Songs too: with it on, the page is
    // covered by the newest song added there (the latest added stamp), not Spotify's heart cover.
    val pageCoverUrl = if (playlist.spotifyId == com.opentune.data.spotify.SpotifyLibrary.LIKED_ID && AppSettings.ui.collectAsState().value.likedCoverFromLatest) {
        playlist.songs.maxByOrNull { it.addedAt }?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: playlist.coverUrl
    } else {
        playlist.coverUrl
    }
    // The page's background as Settings ask for it, from the playlist's cover or its first song's.
    com.opentune.ui.browse.ListPageTint(coverUrl = pageCoverUrl ?: playlist.songs.firstOrNull()?.thumbnailUrl) {
        // The page's menu is composed inside the tint, so its sheet wears the page's colours.
        if (moreOpen) LocalListMoreSheet(playlist, songs, actions,
            onRefresh = {
                // Reading the cookie starts the WebView engine: off the main thread.
                if (playlist.spotifyId != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.opentune.ui.spotify.SpotifyConnection.checkPlaylists() }
                else if (signedIn && remoteId != null) PlaylistSync.refresh(id)
            },
            onAddSongs = { adding = true }, onRename = { renaming = true }, onDelete = { deleting = true },
            onShare = share, onPrivacy = if (online) ({ choosingPrivacy = true }) else null, onDismiss = { moreOpen = false })
        androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) {
            com.opentune.ui.components.GlassPage(Modifier.fillMaxSize().dismissPlaylistSearch(search), overlay = {
                com.opentune.ui.browse.PlaylistSearchHeader(search) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val heroPx = with(density) { (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * HERO_FRACTION).dp.roundToPx() }
                    val pastHero by remember(listState, heroPx) {
                        androidx.compose.runtime.derivedStateOf {
                            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroPx - with(density) { 56.dp.roundToPx() }
                        }
                    }
                    val returning = com.opentune.ui.browse.rememberSearchReturn(search, listState) { i, o -> i > 0 || o > heroPx - with(density) { 56.dp.roundToPx() } }
                    val barSolid = searching || pastHero || returning
                    TopNavBar(playlist.name, barSolid, onBack) {
                        Row(Modifier.height(48.dp).then(if (barSolid) Modifier else Modifier.glass(androidx.compose.foundation.shape.CircleShape)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (searching) search.close() else search.open = true }) {
                                Icon(if (searching) ImageVector.vectorResource(AppIcons.Close) else ImageVector.vectorResource(AppIcons.Search), "Search this list")
                            }
                            Box {
                                IconButton(onClick = { sortOpen = true }) { Icon(ImageVector.vectorResource(AppIcons.Sort), "Sort") }
                                com.opentune.ui.browse.ListSortMenu(sortOpen, order, onPick = { order = it; sortOpen = false }, onDismiss = { sortOpen = false })
                            }
                            IconButton(onClick = { moreOpen = true }) { Icon(ImageVector.vectorResource(AppIcons.MoreHoriz), "More") }
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
                // The cover's blur and fade behind the songs, when Settings ask for the cover-coloured page.
                com.opentune.ui.browse.ListPageWash()
                var shownCount by remember { androidx.compose.runtime.mutableIntStateOf(PAGE_SIZE) }
                val paged = shown.take(shownCount)
                LazyColumn(state = listState, overscrollEffect = band, contentPadding = if (searching) PaddingValues(bottom = 16.dp) else PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    modifier = Modifier.fillMaxSize().playlistSearchResults(search)) {
                    if (!searching) item(key = "header") {
                        PlaylistHero(
                            name = playlist.name,
                            source = if (playlist.spotifyId != null) "" else if (playlist.synced) "YouTube Music" else "On this phone",
                            meta = (if (playlist.spotifyId != null) "Spotify · " else "") + com.opentune.ui.browse.playlistMeta(songs),
                            coverUrl = pageCoverUrl,
                            songs = songs,
                            buttons = {
                                com.opentune.ui.browse.ListPlayRow(
                                    enabled = shown.isNotEmpty(),
                                    saved = false,
                                    onPlay = { actions.playAll(shown, 0, false, playlist.name) },
                                    onShuffle = { actions.playAll(shown, 0, true, playlist.name) },
                                    onSmartShuffle = { actions.smartShuffle(shown, playlist.name) },
                                    onSave = if (playlist.spotifyId == null) ({ adding = true }) else null,
                                    modifier = Modifier.fillMaxWidth(),
                                    actionLabel = "Add songs",
                                )
                            },
                            tools = tools,
                            status = if (playlist.spotifyId == null) status else null,
                            band = band,
                            listState = listState,
                        )
                    }
                    if (songs.isEmpty()) {
                        item {
                            MessageState(ImageVector.vectorResource(AppIcons.QueueMusic), "Nothing here yet", Modifier.padding(top = 32.dp), message = if (playlist.spotifyId != null) "Songs added in Spotify appear after the next sync." else "Add songs here, or use Add to playlist in any song's menu.")
                        }
                        return@LazyColumn
                    }
                    // Keyed by the song (and which copy of it), not its place, so a row taken out pops
                    // alone and the rows below slide up rather than every row after it being redrawn.
                    if (shown.isEmpty()) item { Text(com.opentune.ui.theme.tr("No matching songs"), Modifier.padding(24.dp)) }
                    val keys = songKeys(songs)
                    itemsIndexed(paged, key = { i, _ -> keys[matching[i]] }) { i, song ->
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
                    if (shownCount < shown.size) item(key = "playlist-more") {
                        LaunchedEffect(shownCount) {
                            kotlinx.coroutines.delay(700)
                            shownCount += PAGE_SIZE
                        }
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    }
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
    onPrivacy: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val spotify = playlist.spotifyId != null
    val pinned = AppSettings.ui.collectAsState().value.pinnedPlaylists.contains(playlist.id)
    com.opentune.ui.components.FloatingCard(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        expandable = true,
        startExpanded = false,
        fullFraction = 1f,
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
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Refresh), "Refresh") { close(); onRefresh() }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.PlaylistPlay), "Play next") { close(); songs.asReversed().forEach(actions.playNext) }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.QueueMusic), "Add to queue") { close(); songs.forEach(actions.addToQueue) }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Download), "Download all") {
                close()
                songs.forEach { Downloads.enqueue(context, it) }
                com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Downloading {0} songs", songs.size), context)
            }
            if (!spotify) {
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Add), "Add songs") { close(); onAddSongs() }
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Edit), "Rename") { close(); onRename() }
            }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Share), "Share") { close(); onShare() }
            val privacyTap = onPrivacy
            if (privacyTap != null) {
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource((playlist.privacy ?: com.opentune.data.model.PlaylistPrivacy.PRIVATE).iconRes), (playlist.privacy ?: com.opentune.data.model.PlaylistPrivacy.PRIVATE).meaning) { close(); privacyTap() }
            }
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.PushPin), if (pinned) "Unpin from Library" else "Pin to the top of Library") { close(); LibraryPins.toggle(playlist.id) }
            if (!spotify) com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.DeleteOutline), "Delete") { close(); onDelete() }
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
