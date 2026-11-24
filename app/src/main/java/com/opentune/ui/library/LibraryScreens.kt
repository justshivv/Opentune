package com.opentune.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opentune.data.history.History
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.browse.ShuffleButton
import com.opentune.ui.browse.dismissPlaylistSearch
import com.opentune.ui.browse.playlistSearchResults
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.glass
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder
import com.opentune.ui.share.ReplayShareSheet
import java.util.concurrent.TimeUnit
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

@Composable
internal fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, onSmartShuffle: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) {
            ShuffleButton(onShuffle, onSmartShuffle, compact = true)
            GlassIconButton(ImageVector.vectorResource(AppIcons.PlayArrow), "Play", onPlay, size = 44.dp)
        } else {
            Button(onClick = onPlay, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(ImageVector.vectorResource(AppIcons.PlayArrow), null); Spacer(Modifier.width(8.dp)); Text(com.opentune.ui.theme.tr("Play"))
            }
            ShuffleButton(onShuffle, onSmartShuffle, Modifier.weight(1f))
        }
    }
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

/** The device page's own colour, for its tint when no cover colours it. */
private val DEVICE_SEED = androidx.compose.ui.graphics.Color(0xFF5E7A8C)

/** How many device songs the page lays out at a time; see LocalMusicScreen. */
private const val LOCAL_PAGE_SIZE = 50

/** How much of the screen's height the device hero takes, as the playlist pages' heroes do. */
private const val LOCAL_HERO_FRACTION = 0.68f

/**
 * Audio files on the device, after asking for permission to read them.
 *
 * Built as the playlist pages are: a tinted page whose background follows the
 * cover, a glass top bar with search, sort and the page's menu, and a hero
 * carrying the cover, the counts and the play row. The cover is the first
 * song on the device whose album really has artwork.
 */
@Composable
fun LocalMusicScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val library by AppSettings.library.collectAsState()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
    }
    // Refused: Android may not ask again, so the button opens the app's settings instead.
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; refused = !it }
    // Allowed (or taken away) in the app's settings while away: read again on coming back, or
    // the page kept asking for access it already had.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var dated by remember { mutableStateOf<List<Pair<Song, Long>>>(emptyList()) }
    var heroSong by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(granted, library.localFolder, library.filterNonMusic) {
        if (granted) {
            val loaded = runCatching { LocalMusic.tracksDated(context, library) }.getOrDefault(emptyList())
            dated = loaded
            songs = loaded.map { it.first }
            heroSong = runCatching { LocalMusic.firstWithArtwork(context, loaded.map { it.first }) }.getOrNull()
        }
    }

    // Songs can be picked together, as on a playlist: long-press one and Select, then add the
    // picked ones to playlists or the queue at once.
    val selection = com.opentune.ui.components.rememberSongSelection()
    // Looking through the songs, their order, and the page's own menu, as the
    // playlist pages have them.
    val search = remember { com.opentune.ui.browse.PlaylistSearch() }
    val searching = search.open
    val query = search.query
    // The page opens newest first: the order the sort menu calls Recently added.
    var order by remember { mutableStateOf(com.opentune.ui.browse.ListOrder.RECENT) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val band = com.opentune.ui.components.rememberRubberBand()
    // The list is revealed a page at a time, as on the Liked page: with
    // hundreds of files, laying out every row up front makes the page stutter.
    var shownCount by remember { androidx.compose.runtime.mutableIntStateOf(LOCAL_PAGE_SIZE) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heroHeightPx = with(density) { (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * LOCAL_HERO_FRACTION).dp.toPx() }
    val barHeightPx = with(density) { 56.dp.toPx() }
    // The top bar turns solid once the hero's title zone has scrolled under it.
    val barSolid by remember(heroHeightPx, barHeightPx) {
        androidx.compose.runtime.derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroHeightPx - barHeightPx
        }
    }
    LaunchedEffect(searching, query) {
        if (searching) listState.scrollToItem(0)
    }
    val all = songs.orEmpty()
    val shown = remember(all, dated, query, order) {
        val q = query.trim()
        val found = if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
        when (order) {
            // The scan's own order: by title, as MediaStore returns them.
            com.opentune.ui.browse.ListOrder.DEFAULT -> found
            com.opentune.ui.browse.ListOrder.RECENT -> {
                val stamp = dated.associate { (song, added) -> song.videoId to added }
                found.sortedByDescending { stamp[it.videoId] ?: 0L }
            }
            com.opentune.ui.browse.ListOrder.A_TO_Z -> found.sortedBy { it.title.lowercase() }
            com.opentune.ui.browse.ListOrder.Z_TO_A -> found.sortedByDescending { it.title.lowercase() }
        }
    }
    val selectionRows = remember(shown) { shown.map { it.videoId to it } }
    val coverUrl = heroSong?.thumbnailUrl?.takeIf { it.isNotBlank() }

    com.opentune.ui.browse.ListPageTint(coverUrl = coverUrl, seed = DEVICE_SEED) {
        // The page's menu is composed inside the tint, so its sheet wears the page's colours.
        if (moreOpen) LocalMusicMoreSheet(all, heroSong, actions) { moreOpen = false }
        androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) {
            com.opentune.ui.components.PageBackdropScope {
                com.opentune.ui.components.GlassPage(Modifier.fillMaxSize().dismissPlaylistSearch(search), overlay = {
                    com.opentune.ui.browse.PlaylistSearchHeader(search) {
                        val returning = com.opentune.ui.browse.rememberSearchReturn(search, listState) { i, o -> i > 0 || o > heroHeightPx - barHeightPx }
                        val topSolid = searching || barSolid || returning
                        TopNavBar("On this device", topSolid, onBack) {
                            // The bar's actions are only built once there are songs to
                            // search, sort or act on: with none (no permission yet, still
                            // reading, or an empty library) they would do nothing.
                            if (songs != null && all.isNotEmpty()) {
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
                    }
                    com.opentune.ui.components.SongSelectionBar(
                        selection,
                        rows = selectionRows,
                        onAddToQueue = { picked -> picked.forEach(actions.addToQueue) },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
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
                        when {
                            !granted -> {
                                item { Spacer(Modifier.height(96.dp)) }
                                item {
                                    MessageState(
                                        ImageVector.vectorResource(AppIcons.Folder),
                                        "Play music saved on your phone",
                                        Modifier.padding(top = 32.dp),
                                        message = if (refused) "Access to audio files is off for NepTune. You can allow it in the app's settings."
                                            else "NepTune needs permission to read audio files. Nothing leaves your device.",
                                        action = {
                                            if (refused) Button(onClick = {
                                                runCatching {
                                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        android.net.Uri.parse("package:${context.packageName}")))
                                                }
                                            }) { Text("Open settings") }
                                            else Button(onClick = { launcher.launch(audioPermission) }) { Text("Allow access") }
                                        },
                                    )
                                }
                            }
                            songs == null -> {
                                item { LocalMusicSkeleton(band, listState) }
                                items(8) { SongRowPlaceholder() }
                            }
                            else -> {
                                if (!searching) item(key = "local-hero") {
                                    LocalMusicHero(
                                        heroSong, all, library.localFolder,
                                        onPlay = { actions.playAll(shown, 0, false, "On this device") },
                                        onShuffle = { actions.playAll(shown, 0, true, "On this device") },
                                        onSmartShuffle = { actions.smartShuffle(shown, "On this device") },
                                        band = band,
                                        listState = listState,
                                    )
                                }
                                when {
                                    all.isEmpty() -> item {
                                        MessageState(ImageVector.vectorResource(AppIcons.Folder), "No music found", Modifier.padding(top = 32.dp), message = "Check the folder and filter in Settings › Local music.")
                                    }
                                    shown.isEmpty() -> item { Text(com.opentune.ui.theme.tr("No matching songs"), Modifier.padding(24.dp)) }
                                    else -> {
                                        itemsIndexed(shown.take(shownCount), key = { _, s -> s.videoId }) { i, song ->
                                            SongListItem(
                                                song = song,
                                                onClick = { actions.playAll(shown, i, false, "On this device") },
                                                modifier = Modifier.animateItem(),
                                                isCurrent = song.videoId == actions.currentVideoId,
                                                isPlaying = actions.isPlaying,
                                                onPlayNext = { actions.playNext(song) },
                                                onAddToQueue = { actions.addToQueue(song) },
                                            )
                                        }
                                        if (shownCount < shown.size) {
                                            item(key = "local-more") {
                                                // Reached the end of what's shown: the app's loader turns
                                                // for a beat, the next page lands, and the loader moves
                                                // down with the end (or is gone, on the last page).
                                                LaunchedEffect(shownCount) {
                                                    kotlinx.coroutines.delay(700)
                                                    shownCount = minOf(shownCount + LOCAL_PAGE_SIZE, shown.size)
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
                }
            }
        }
    }
}

/**
 * The device page while its songs are still being read: the hero's shape as
 * a skeleton — the cover across the whole top, with the title, the counts
 * and the two buttons as bars over its fade — as the Liked page shows it.
 */
@Composable
private fun LocalMusicSkeleton(band: com.opentune.ui.components.RubberBandOverscroll? = null, listState: androidx.compose.foundation.lazy.LazyListState? = null) {
    com.opentune.ui.browse.ListHero(cover = { m -> com.opentune.ui.components.Placeholder(m, androidx.compose.ui.graphics.RectangleShape) }, band = band, listState = listState) {
        run {
            com.opentune.ui.components.Placeholder(Modifier.width(200.dp).height(26.dp))
            com.opentune.ui.components.Placeholder(Modifier.padding(top = 10.dp).width(140.dp).height(16.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.opentune.ui.components.Placeholder(Modifier.weight(1f).height(52.dp), RoundedCornerShape(26.dp))
                com.opentune.ui.components.Placeholder(Modifier.weight(1f).height(52.dp), RoundedCornerShape(26.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The device page's hero, in the Liked page's shape: the cover across the
 * whole top (the first song whose album has artwork), the title and the
 * counts over its fade, then the play row.
 */
@Composable
private fun LocalMusicHero(
    heroSong: Song?, songs: List<Song>, folder: String?,
    onPlay: () -> Unit, onShuffle: () -> Unit, onSmartShuffle: () -> Unit,
    band: com.opentune.ui.components.RubberBandOverscroll? = null,
    listState: androidx.compose.foundation.lazy.LazyListState? = null,
) {
    com.opentune.ui.browse.ListHero(
        cover = { m ->
            Artwork(heroSong?.thumbnailUrl?.takeIf { it.isNotBlank() }, m, shape = androidx.compose.ui.graphics.RectangleShape, placeholder = ImageVector.vectorResource(AppIcons.Album))
        },
        footPadding = 18.dp,
        band = band,
        listState = listState,
    ) {
        run {
            Text(
                "On this device",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (songs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    com.opentune.ui.browse.playlistMeta(songs) + (folder?.let { "  •  " + it } ?: ""),
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

/**
 * The device page's own menu, as a playlist page's menu: the cover and the
 * name, then what can be done with the whole library. No Download all: the
 * songs are already on the device.
 */
@Composable
private fun LocalMusicMoreSheet(
    songs: List<Song>,
    heroSong: Song?,
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
                Artwork(heroSong?.thumbnailUrl?.takeIf { it.isNotBlank() }, Modifier.size(56.dp), shape = RoundedCornerShape(14.dp), placeholder = ImageVector.vectorResource(AppIcons.Album))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("On this device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Share), "Share") {
                close()
                com.opentune.ui.share.ListShare.shareLocal(context, "On this device", songs)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Ranked listening summaries; all figures come from recorded listens on this device. */
@Composable
fun ReplayScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenWrapped: () -> Unit = {}) {
    val records by History.records.collectAsState()
    val account by com.opentune.data.account.AccountStore.account.collectAsState()
    var range by rememberSaveable { mutableStateOf(ReplayRange.YEAR) }
    val summary = remember(records, range) { History.replay(records, range.since()) }
    val topSongs = remember(summary) { summary.topSongs.mapNotNull { it.song } }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var story by rememberSaveable { mutableStateOf(false) }
    if (sharing) ReplayShareSheet(summary, range.heading(), onDismiss = { sharing = false })
    if (story) ReplayStory(summary, range, onDismiss = { story = false })
    // The account's name; the Listen together name stays in its rooms (issue 45).
    val name = account?.name?.takeIf { it.isNotBlank() } ?: "You"
    val since = remember(records) { memberSince(records.lastOrNull()?.playedAt) }
    // The page takes the colour of the card in front.
    var card by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val tint by androidx.compose.animation.animateColorAsState(replayTint(card), androidx.compose.animation.core.tween(500), label = "replayTint")
    val background = MaterialTheme.colorScheme.background
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize().background(
        androidx.compose.ui.graphics.Brush.verticalGradient(0f to tint.copy(alpha = 0.75f), 0.55f to background))) {
        item {
            PageHeader("Replay", onBack = onBack, actions = if (summary.totalPlays == 0) null else {
                { GlassIconButton(ImageVector.vectorResource(AppIcons.Share), "Share my Replay", { sharing = true }) }
            })
            Text(range.heading(), Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            // Weekly, Monthly, Yearly, Lifetime, the chosen one on a light pill.
            Row(Modifier.fillMaxWidth().padding(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)).padding(4.dp)) {
                ReplayRange.entries.forEach { r ->
                    val chosen = r == range
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(CircleShape)
                            .background(if (chosen) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { range = r; card = 0 },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(r.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            color = if (chosen) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        item(key = "cards:${range.name}") {
            ReplayCards(summary, range, name, since, onClick = { story = true }, onCard = { card = it })
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                    .clickable { story = true }.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ImageVector.vectorResource(AppIcons.PlayArrow), null)
                Text("Play your Replay", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                Icon(ImageVector.vectorResource(AppIcons.KeyboardArrowRight), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (summary.totalPlays == 0) item {
            MessageState(ImageVector.vectorResource(AppIcons.BarChart), "Nothing to replay yet", Modifier.padding(16.dp), message = "Listen for a while and your top songs and artists show up here.")
        }
        if (summary.topSongs.isNotEmpty()) item { SectionHeader("Top songs") }
        itemsIndexed(summary.topSongs, key = { i, entry -> "s:$i:${entry.key}" }) { i, entry ->
            val song = entry.song ?: return@itemsIndexed
            // "Artist · 9 min" under the title, the plays at the end.
            SongListItem(song.copy(durationText = com.opentune.i18n.Tr.f("{0} min", TimeUnit.MILLISECONDS.toMinutes(entry.listenedMs))),
                onClick = { actions.playAll(topSongs, i, false, "Replay") },
                isCurrent = song.videoId == actions.currentVideoId, isPlaying = actions.isPlaying,
                leading = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(28.dp), color = if (i == 0) androidx.compose.ui.graphics.Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    Artwork(song.thumbnailUrl, Modifier.size(52.dp))
                } }, onPlayNext = { actions.playNext(song) }, onAddToQueue = { actions.addToQueue(song) },
                trailing = { Text("${entry.plays}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) })
        }
        if (summary.topArtists.isNotEmpty()) item { SectionHeader("Top artists") }
        itemsIndexed(summary.topArtists, key = { i, entry -> "a:$i:${entry.key}" }) { i, entry -> ReplayRankRow(i, entry, true) }
        if (summary.topAlbums.isNotEmpty()) item { SectionHeader("Top albums") }
        itemsIndexed(summary.topAlbums, key = { i, entry -> "l:$i:${entry.key}" }) { i, entry -> ReplayRankRow(i, entry, false) }
        item {
            com.opentune.ui.wrapped.WrappedBanner("Your listening story", "Explore your listening habits as an animated story", onOpenWrapped,
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun ReplayRankRow(index: Int, entry: com.opentune.data.history.TopEntry, round: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", Modifier.width(32.dp), color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Artwork(entry.thumbnailUrl, Modifier.size(56.dp), if (round) CircleShape else RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(com.opentune.i18n.Tr.f("{0} min", TimeUnit.MILLISECONDS.toMinutes(entry.listenedMs)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${entry.plays}", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 18.dp, horizontal = 14.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
