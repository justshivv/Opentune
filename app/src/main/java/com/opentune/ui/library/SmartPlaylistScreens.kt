package com.opentune.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.SmartMatch
import com.opentune.data.library.SmartPlaylist
import com.opentune.data.library.SmartPlaylists
import com.opentune.data.library.SmartRule
import com.opentune.data.library.SmartRules
import com.opentune.data.library.SmartRulesSerializer
import com.opentune.data.library.SmartSort
import com.opentune.data.library.SmartSource
import com.opentune.data.library.SongRef
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.i18n.Tr
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.ChoiceSheet
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.NavRow
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.RowDivider
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.glass
import com.opentune.ui.components.menuClickable
import com.opentune.ui.components.pageButtonFace
import com.opentune.ui.components.pageButtonInk
import com.opentune.ui.components.posterLayout
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// ---- What a smart playlist is worked out from ----

/** What a smart playlist's songs are worked out from, kept up to date as they change. */
@Composable
private fun rememberSmartFacts(): SmartPlaylists.Facts? {
    val flow = remember {
        // Only finished downloads count, so one on its way (whose progress ticks many times
        // a second) doesn't work the whole list out again with every tick.
        val finished = Downloads.entries
            .map { all -> all.filterValues { it.state == com.opentune.data.download.DownloadState.DONE } }
            .distinctUntilChanged()
        combine(History.records, LibraryStore.liked, finished, LibraryStore.playlists) { h, l, d, p ->
            SmartPlaylists.Facts(h, l, d, p)
        }
    }
    return flow.collectAsState(null).value
}

/** The songs [rules] pick from [facts] right now, worked out off the main thread; null until then. */
@Composable
private fun rememberSmartSongs(rules: SmartRules, seed: Long, facts: SmartPlaylists.Facts?): List<SongRef>? =
    produceState<List<SongRef>?>(null, rules, facts, seed) {
        val f = facts ?: return@produceState
        value = withContext(Dispatchers.Default) { SmartPlaylists.songs(rules, f, seed = seed) }
    }.value

/** Rules kept through a turn of the phone or the app being put away, as the JSON they're saved in. */
private val rulesJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
private val RulesSaver = androidx.compose.runtime.saveable.Saver<SmartRules, String>(
    save = { rulesJson.encodeToString(SmartRulesSerializer, it) },
    restore = { runCatching { rulesJson.decodeFromString(SmartRulesSerializer, it) }.getOrNull() },
)

// ---- The rules in words ----

private fun SmartSource.label() = when (this) {
    SmartSource.EVERYTHING -> "Everything"
    SmartSource.HISTORY -> "Played"
    SmartSource.LIKED -> "Liked"
    SmartSource.DOWNLOADS -> "Downloads"
}

private fun SmartSource.about() = when (this) {
    SmartSource.EVERYTHING -> "Every song you've played, liked, downloaded or put in a playlist"
    SmartSource.HISTORY -> "Songs you've played at least once"
    SmartSource.LIKED -> "Songs you've liked"
    SmartSource.DOWNLOADS -> "Songs downloaded to this phone"
}

private fun SmartSource.icon() = when (this) {
    SmartSource.EVERYTHING -> AppIcons.AllInclusive
    SmartSource.HISTORY -> AppIcons.History
    SmartSource.LIKED -> AppIcons.Favorite
    SmartSource.DOWNLOADS -> AppIcons.Download
}

private fun SmartSort.label() = when (this) {
    SmartSort.MOST_PLAYED -> "Most played"
    SmartSort.RECENTLY_PLAYED -> "Recently played"
    SmartSort.LONGEST_UNPLAYED -> "Longest since played"
    SmartSort.NEWEST -> "Newest to you"
    SmartSort.TITLE -> "Title, A to Z"
    SmartSort.ARTIST -> "Artist, A to Z"
    SmartSort.SHUFFLE -> "Shuffled each day"
}

private fun limitLabel(limit: Int?): String =
    limit?.let { Tr.f("{0} songs", it) } ?: Tr.t("No limit")

/** A span of days as it ends "in …": "the last month", "the last 3 months". */
private fun span(days: Int): String = when (days) {
    7 -> Tr.t("the last week")
    30 -> Tr.t("the last month")
    90 -> Tr.t("the last 3 months")
    180 -> Tr.t("the last 6 months")
    365 -> Tr.t("the last year")
    else -> Tr.f("the last {0} days", days)
}

/** A span of days on its own, for a picker: "Week", "3 months". */
private fun spanChoice(days: Int?): String = when (days) {
    null -> Tr.t("Any time")
    7 -> Tr.t("Week")
    30 -> Tr.t("Month")
    90 -> Tr.t("3 months")
    180 -> Tr.t("6 months")
    365 -> Tr.t("Year")
    else -> Tr.f("{0} days", days)
}

/** One rule in plain words, as it reads on its own: "Played 10+ times in the last month". */
internal fun ruleLabel(rule: SmartRule): String = when (rule) {
    is SmartRule.Plays -> {
        val days = rule.withinDays
        when {
            days == null && rule.atLeast <= 1 -> Tr.t("Played at least once")
            days == null -> Tr.f("Played {0}+ times", rule.atLeast)
            rule.atLeast <= 1 -> Tr.f("Played in {0}", span(days))
            else -> Tr.f("Played {0}+ times in {1}", rule.atLeast, span(days))
        }
    }
    is SmartRule.NotPlayedFor -> Tr.f("Not played in {0}", span(rule.days))
    is SmartRule.NewWithin -> Tr.f("New to you in {0}", span(rule.days))
    SmartRule.Liked -> Tr.t("Liked")
    SmartRule.Downloaded -> Tr.t("Downloaded")
    SmartRule.Lossless -> Tr.t("Downloaded in lossless")
    is SmartRule.Artist -> if (rule.name.isBlank()) Tr.t("By an artist you choose") else Tr.f("By {0}", rule.name.trim())
}

/** One rule as it follows "Songs": "you played 10+ times in the last month". */
private fun ruleClause(rule: SmartRule): String = when (rule) {
    is SmartRule.Plays -> {
        val days = rule.withinDays
        when {
            days == null && rule.atLeast <= 1 -> Tr.t("you've played")
            days == null -> Tr.f("you've played {0}+ times", rule.atLeast)
            rule.atLeast <= 1 -> Tr.f("you played in {0}", span(days))
            else -> Tr.f("you played {0}+ times in {1}", rule.atLeast, span(days))
        }
    }
    is SmartRule.NotPlayedFor -> Tr.f("you haven't played in {0}", span(rule.days))
    is SmartRule.NewWithin -> Tr.f("new to you in {0}", span(rule.days))
    SmartRule.Liked -> Tr.t("you liked")
    SmartRule.Downloaded -> Tr.t("you downloaded")
    SmartRule.Lossless -> Tr.t("downloaded in lossless")
    is SmartRule.Artist -> Tr.f("by {0}", rule.name.trim())
}

/**
 * The rules in one plain sentence, for under the playlist's name: "Songs you played 10+ times
 * in the last month", "Liked songs you haven't played in the last 3 months".
 */
internal fun smartSummary(rules: SmartRules): String {
    val conditions = rules.conditions.filterNot { it is SmartRule.Artist && it.name.isBlank() }
    val all = rules.match == SmartMatch.ALL || conditions.size <= 1
    // Under All, liked, downloaded and lossless describe the songs themselves: "Liked lossless downloads".
    fun folds(rule: SmartRule) = all && conditions.contains(rule)
    val liked = rules.from == SmartSource.LIKED || folds(SmartRule.Liked)
    val lossless = folds(SmartRule.Lossless)
    val downloaded = rules.from == SmartSource.DOWNLOADS || folds(SmartRule.Downloaded) || lossless
    val clauses = conditions
        .filterNot { all && (it == SmartRule.Liked || it == SmartRule.Downloaded || it == SmartRule.Lossless) }
        // "Songs by Moss Lantern you played…" reads better than the artist trailing behind.
        .sortedBy { if (it is SmartRule.Artist) 0 else 1 }
    val played = clauses.any { it is SmartRule.Plays }
    val subject = when {
        liked && lossless -> Tr.t("Liked lossless downloads")
        lossless -> Tr.t("Lossless downloads")
        liked && downloaded -> Tr.t("Liked downloads")
        liked -> Tr.t("Liked songs")
        downloaded -> Tr.t("Downloads")
        rules.from == SmartSource.HISTORY && !played -> Tr.t("Songs you've played")
        clauses.isEmpty() -> Tr.t("Every song on this phone")
        else -> Tr.t("Songs")
    }
    if (clauses.isEmpty()) return subject
    val words = clauses.map(::ruleClause)
    val last = if (all) Tr.t("and") else Tr.t("or")
    val joined = if (words.size == 1) words[0] else words.dropLast(1).joinToString(", ") + " $last " + words.last()
    return "$subject $joined"
}

// ---- Library's tiles ----

/** Smart playlists as a row of tiles on Library, with one to make a new one. */
@Composable
internal fun SmartPlaylistsRow(onOpen: (String) -> Unit, onNew: () -> Unit, tileWidth: androidx.compose.ui.unit.Dp) {
    val smart by LibraryStore.smartPlaylists.collectAsState()
    SectionHeader("Smart playlists")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        items(smart, key = { it.id }) { p -> SmartTile(p.name, smartSummary(p.rules), { onOpen(p.id) }, Modifier.width(tileWidth)) }
        item(key = "new-smart") {
            SmartTile("New smart playlist", "Fills itself from rules", onNew, Modifier.width(tileWidth), icon = ImageVector.vectorResource(AppIcons.Add))
        }
    }
}

/** A gradient of a smart playlist's own, from its name. */
private fun smartBrush(title: String): Brush {
    val hue = (title.lowercase().hashCode().toUInt() % 360u).toFloat()
    return Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.75f), Color.hsv((hue + 50f) % 360f, 0.65f, 0.45f)))
}

/** A smart playlist's tile: a gradient of its own from its name, and the sparkle mark. */
@Composable
internal fun SmartTile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier, icon: ImageVector = ImageVector.vectorResource(AppIcons.AutoAwesome)) {
    Column(modifier.clip(MaterialTheme.shapes.medium).menuClickable(onClick = onClick, onLongClick = null).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(smartBrush(title)), Alignment.Center) {
            Icon(icon, null, Modifier.size(56.dp), tint = Color.White)
        }
        Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- The playlist's page ----

/** A smart playlist: its songs as its rules pick them now, to play, shuffle or keep as an ordinary playlist. */
@Composable
fun SmartPlaylistScreen(id: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onEdit: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val all by LibraryStore.smartPlaylists.collectAsState()
    val playlist = all.firstOrNull { it.id == id }
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val refs = rememberSmartSongs(playlist.rules, playlist.id.hashCode().toLong(), rememberSmartFacts())
    SmartPlaylistPage(
        playlist, refs, contentPadding, actions, onBack, onEdit,
        onSaveAsPlaylist = { songs ->
            // A copy that stays as it is now: an ordinary playlist made in the app.
            val copy = LibraryStore.createPlaylist(playlist.name)
            LibraryStore.addAllToPlaylist(copy, songs)
            com.opentune.data.Pills.done("Saved as a playlist", context)
            onOpenPlaylist(copy)
        },
        onDelete = {
            LibraryStore.deleteSmartPlaylist(playlist.id)
            com.opentune.data.Pills.done("Smart playlist deleted", context)
            // The page sees it gone and leaves.
        },
    )
}

/** The page itself, given the playlist and its songs ([refs] null while they're worked out). */
@Composable
internal fun SmartPlaylistPage(
    playlist: SmartPlaylist,
    refs: List<SongRef>?,
    contentPadding: PaddingValues,
    actions: SongActions,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSaveAsPlaylist: (List<Song>) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val songs = remember(refs) { refs.orEmpty().map { it.toSong() } }
    val keys = remember(songs) { songKeys(songs) }
    val listState = rememberLazyListState()
    val band = com.opentune.ui.components.rememberRubberBand()
    val selection = com.opentune.ui.components.rememberSongSelection()
    var moreOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val source = playlist.name
    val ours = actions.queueSource == source && actions.currentVideoId != null

    if (deleting) {
        com.opentune.ui.components.FloatingDialog(
            onDismissRequest = { deleting = false },
            title = { Text(Tr.f("Delete {0}?", playlist.name)) },
            text = { Text("Only the rules go. Your songs, likes and history stay as they are.") },
            confirmButton = { SheetButton("Delete", tone = SheetTone.Danger, onClick = { deleting = false; onDelete() }) },
            dismissButton = { SheetButton("Cancel", onClick = { deleting = false }, closes = true) },
        )
    }
    // The page's background as Settings ask for it, from the first song's cover.
    com.opentune.ui.browse.ListPageTint(coverUrl = songs.firstOrNull()?.thumbnailUrl) {
        // Composed inside the tint, so its sheet wears the page's colours.
        if (moreOpen) SmartMoreSheet(
            playlist, songs, actions,
            onEdit = onEdit,
            onSaveAsPlaylist = { onSaveAsPlaylist(songs) },
            onShare = { com.opentune.ui.share.ListShare.shareLocal(context, playlist.name, songs) },
            onDelete = { deleting = true },
            onDismiss = { moreOpen = false },
        )
        CompositionLocalProvider(com.opentune.ui.components.LocalSongSelection provides selection) { com.opentune.ui.components.PageBackdropScope {
            com.opentune.ui.components.GlassPage(Modifier.fillMaxSize(), overlay = {
                val density = LocalDensity.current
                val heroPx = with(density) { com.opentune.ui.browse.collectionHeroHeight().roundToPx() }
                val barPx = with(density) { 56.dp.roundToPx() }
                val pastHero by remember(listState, heroPx, barPx) {
                    derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroPx - barPx }
                }
                TopNavBar(playlist.name, pastHero, onBack) {
                    Row(
                        Modifier.height(48.dp).then(if (pastHero) Modifier else Modifier.glass(CircleShape)).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onEdit) { Icon(ImageVector.vectorResource(AppIcons.Edit), "Edit rules") }
                        IconButton(onClick = { moreOpen = true }) { Icon(ImageVector.vectorResource(AppIcons.MoreHoriz), "More") }
                    }
                }
                com.opentune.ui.components.SongSelectionBar(
                    selection,
                    rows = songs.map { it.videoId to it },
                    onAddToQueue = { picked -> picked.forEach(actions.addToQueue) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }) {
                // The cover's blur and fade behind the songs, when Settings ask for the cover-coloured page.
                com.opentune.ui.browse.ListPageWash()
                LazyColumn(
                    state = listState,
                    overscrollEffect = band,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "hero") {
                        SmartHero(
                            playlist, songs, loading = refs == null,
                            onPlay = { actions.playAll(songs, 0, false, source) },
                            onShuffle = { actions.playAll(songs, 0, true, source) },
                            onSmartShuffle = { actions.smartShuffle(songs, source) },
                            showPause = ours && actions.isSounding,
                            onToggle = if (ours) actions.togglePlayPause else null,
                            busy = ours && actions.isPlaying && !actions.isSounding,
                            onEdit = onEdit,
                            onSaveAsPlaylist = { onSaveAsPlaylist(songs) },
                            band = band, listState = listState,
                        )
                    }
                    when {
                        // Worked out in a moment: the rows' shapes, so nothing jumps when they land.
                        refs == null -> items(8, key = { "skeleton-$it" }) { com.opentune.ui.components.SongRowPlaceholder() }
                        songs.isEmpty() -> item(key = "empty") { SmartEmpty(playlist.rules, onEdit, Modifier.animateItem()) }
                        // Keys that stay unique even if a song shows up twice.
                        else -> itemsIndexed(songs, key = { i, _ -> keys[i] }) { i, song ->
                            SongListItem(
                                song = song,
                                onClick = { actions.playAll(songs, i, false, source) },
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
        } }
    }
}

/**
 * The smart playlist's hero, as the other playlist pages have it: the cover made from its songs,
 * "Smart playlist", the name, its rules in a sentence, how many songs and how long, the play
 * buttons, then Edit rules and Save as playlist.
 */
@Composable
private fun SmartHero(
    playlist: SmartPlaylist,
    songs: List<Song>,
    loading: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSmartShuffle: () -> Unit,
    showPause: Boolean,
    onToggle: (() -> Unit)?,
    busy: Boolean,
    onEdit: () -> Unit,
    onSaveAsPlaylist: () -> Unit,
    band: com.opentune.ui.components.RubberBandOverscroll?,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val poster = posterLayout()
    val covers = remember(songs) { songs.map { it.thumbnailUrl } }
    val meta = if (loading) null else com.opentune.ui.browse.playlistMeta(songs)
    val summary = remember(playlist.rules, Tr.language) { smartSummary(playlist.rules) }
    val fullCover = com.opentune.ui.browse.fullListCover()
    com.opentune.ui.browse.ListHero(
        // Across the top, the sparkle sits clear of the name and buttons over the cover's foot.
        cover = { m -> SmartCover(playlist.name, covers, m, RectangleShape, mark = if (fullCover) androidx.compose.ui.BiasAlignment(0f, -0.45f) else Alignment.Center, markSize = if (fullCover) 0.24f else 0.3f) },
        band = band,
        listState = listState,
    ) {
        if (!poster) SmartKindLabel()
        Text(
            playlist.name,
            style = if (poster) MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black)
            else MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (poster) 0.dp else 6.dp).semantics { heading() },
        )
        Text(
            summary,
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!poster) {
            // One line tall whether counted yet or not, so nothing below moves when the count lands.
            Box(Modifier.padding(top = 8.dp).heightIn(min = 20.dp), contentAlignment = Alignment.Center) {
                if (meta == null) com.opentune.ui.components.Placeholder(Modifier.width(120.dp).height(14.dp))
                else Text(meta, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Spacer(Modifier.height(if (poster) 24.dp else 20.dp))
        com.opentune.ui.browse.ListPlayRow(
            enabled = songs.isNotEmpty(),
            saved = false,
            onPlay = onPlay,
            onShuffle = onShuffle,
            onSmartShuffle = onSmartShuffle,
            showPause = showPause,
            onToggle = onToggle,
            busy = busy,
            onSave = null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            SmartToolPill(AppIcons.Tune, "Edit rules", onClick = onEdit)
            SmartToolPill(AppIcons.PlaylistAdd, "Save as playlist", enabled = songs.isNotEmpty(), onClick = onSaveAsPlaylist)
        }
        if (poster) {
            // The Poster layout's counts, in its spaced capitals over a hairline where the songs begin.
            val counts = meta?.replace(" • ", " · ")?.replace(Regex("""(\d+)m\b"""), "$1 min")?.replace(Regex("""(\d+)h\b"""), "$1 hr")
            Box(Modifier.padding(top = 22.dp).heightIn(min = 20.dp), contentAlignment = Alignment.Center) {
                com.opentune.ui.components.PosterCaps(listOfNotNull(Tr.t("Smart playlist"), counts).joinToString(" · "))
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** "✦ Smart playlist", above the name: what kind of list this is. */
@Composable
private fun SmartKindLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(ImageVector.vectorResource(AppIcons.AutoAwesome), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("Smart playlist", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A pill under the play buttons, in the page buttons' own face: glass, or the page's
 * accent when Settings ask for it. 48dp tall, so it is easy to hit.
 */
@Composable
private fun SmartToolPill(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    val shape = CircleShape
    val face = if (posterLayout() || com.opentune.ui.components.LocalPageAccent.current != null) Modifier.pageButtonFace(shape)
    else Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
    val ink = if (posterLayout() || com.opentune.ui.components.LocalPageAccent.current != null) pageButtonInk() else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.height(48.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.then(face)
            .clickable(enabled = enabled, role = Role.Button) { haptics.tick(); onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ImageVector.vectorResource(icon), null, Modifier.size(20.dp), tint = ink)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

/** Why the page is empty, and the way to change that. */
@Composable
private fun SmartEmpty(rules: SmartRules, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    MessageState(
        ImageVector.vectorResource(AppIcons.AutoAwesome),
        if (rules.contradictory) "These rules can't all be met" else "Nothing matches yet",
        modifier.padding(top = 8.dp),
        message = if (rules.contradictory) CONTRADICTION
        else when (rules.from) {
            SmartSource.LIKED -> "Songs show up here once you've liked some that fit the rules. You can also loosen the rules."
            SmartSource.DOWNLOADS -> "Songs show up here once you've downloaded some that fit the rules. You can also loosen the rules."
            else -> "Songs show up here as soon as they fit the rules. Keep listening, or loosen the rules."
        },
        action = {
            androidx.compose.material3.FilledTonalButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(ImageVector.vectorResource(AppIcons.Tune), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Edit rules")
            }
        },
    )
}

/** The page's own menu: the cover and name, then what can be done with the whole list. */
@Composable
private fun SmartMoreSheet(
    playlist: SmartPlaylist,
    songs: List<Song>,
    actions: SongActions,
    onEdit: () -> Unit,
    onSaveAsPlaylist: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.opentune.ui.components.FloatingCard(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmartCover(playlist.name, songs.map { it.thumbnailUrl }, Modifier.size(56.dp), RoundedCornerShape(14.dp), markSize = 0.45f)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.opentune.ui.share.ListShare.countLine(songs.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        },
    ) {
        val card = com.opentune.ui.components.LocalFloatingCard.current
        // The sheet plays out and goes, then the action runs, as the other pages' menus do.
        val close: (() -> Unit) -> Unit = { then -> card?.close { onDismiss(); then() } ?: run { onDismiss(); then() } }
        Column {
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Tune), "Edit rules") { close(onEdit) }
            if (songs.isNotEmpty()) {
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.PlaylistPlay), "Play next") { close { songs.asReversed().forEach(actions.playNext) } }
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.QueueMusic), "Add to queue") { close { songs.forEach(actions.addToQueue) } }
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.PlaylistAdd), "Save as playlist", summary = "A copy that keeps these songs as they are now") { close(onSaveAsPlaylist) }
                com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Share), "Share") { close(onShare) }
            }
            com.opentune.ui.components.MenuGroupBreak()
            com.opentune.ui.components.MenuRow(ImageVector.vectorResource(AppIcons.Delete), "Delete smart playlist") { close(onDelete) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---- The rule editor ----

/** Why rules that ask for a song both played and not played over the same days match nothing. */
private const val CONTRADICTION =
    "A song can't be played in a span of time and also not played in the same span or a longer one. Shorten the \"Not played\" rule, or lengthen the \"Played\" one."

/** The kinds of rule there are, in the order the Add a rule sheet offers them. */
private enum class RuleKind(val title: String, val about: String, val icon: Int) {
    PLAYS("Play count", "How often you've played it, ever or lately", AppIcons.Repeat),
    NOT_PLAYED("Not played lately", "Songs you haven't played for a while", AppIcons.Bedtime),
    NEW("New to you", "First played or downloaded lately", AppIcons.FiberNew),
    ARTIST("Artist", "Songs by an artist you choose", AppIcons.Person),
    LIKED("Liked", "Songs you've liked", AppIcons.Favorite),
    DOWNLOADED("Downloaded", "Songs downloaded to this phone", AppIcons.Download),
    LOSSLESS("Lossless", "Downloaded in lossless quality", AppIcons.HighQuality),
    ;

    /** A rule of this kind to start the sheet from. */
    fun start(): SmartRule = when (this) {
        PLAYS -> SmartRule.Plays(5, 30)
        NOT_PLAYED -> SmartRule.NotPlayedFor(90)
        NEW -> SmartRule.NewWithin(30)
        ARTIST -> SmartRule.Artist("")
        LIKED -> SmartRule.Liked
        DOWNLOADED -> SmartRule.Downloaded
        LOSSLESS -> SmartRule.Lossless
    }

    /** Nothing to choose: added as soon as it's picked. */
    val plain: Boolean get() = this == LIKED || this == DOWNLOADED || this == LOSSLESS

    companion object {
        fun of(rule: SmartRule) = when (rule) {
            is SmartRule.Plays -> PLAYS
            is SmartRule.NotPlayedFor -> NOT_PLAYED
            is SmartRule.NewWithin -> NEW
            is SmartRule.Artist -> ARTIST
            SmartRule.Liked -> LIKED
            SmartRule.Downloaded -> DOWNLOADED
            SmartRule.Lossless -> LOSSLESS
        }
    }
}

/** Which choice sheet is open in the editor. */
private enum class SmartChoice { SORT, LIMIT }

/** The rule sheet: adding a new rule ([index] null) or changing the one at [index]. */
private data class RuleSheet(val index: Int?)

/** What stops the rules being saved as they are, in plain words; null when nothing does. */
internal fun smartProblem(name: String, rules: SmartRules): String? = when {
    name.isBlank() -> Tr.t("Give it a name to save it")
    rules.contradictory -> Tr.t("Two rules can't both be met")
    rules.conditions.any { it is SmartRule.Artist && it.name.isBlank() } -> Tr.t("An artist rule has no name")
    else -> null
}

/** Which of the rules (by place) can't be met along with another: what [SmartRules.contradictory] found. */
private fun clashingRules(rules: SmartRules): Set<Int> {
    if (!rules.contradictory) return emptySet()
    val list = rules.conditions
    return buildSet {
        list.forEachIndexed { i, p ->
            val within = (p as? SmartRule.Plays)?.withinDays ?: return@forEachIndexed
            list.forEachIndexed { j, n -> if (n is SmartRule.NotPlayedFor && n.days >= within) { add(i); add(j) } }
        }
    }
}

/**
 * Making a smart playlist ([id] null) or changing one: a name, a template to start from (new
 * ones), where its songs come from, the rules in words, their order and how many, and how
 * many songs match right now.
 */
@Composable
fun SmartPlaylistEditor(id: String?, contentPadding: PaddingValues, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val existing: SmartPlaylist? = remember(id) { id?.let(LibraryStore::smartPlaylist) }
    if (id != null && existing == null) {
        // Deleted while the editor was on its way: there's nothing left to change.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    SmartEditorPage(
        existing, rememberSmartFacts(), contentPadding, onBack,
        onSave = { name, rules ->
            val saved = if (existing == null) LibraryStore.createSmartPlaylist(name, rules)
            else existing.id.also { LibraryStore.updateSmartPlaylist(it, name, rules) }
            onSaved(saved)
        },
        onDelete = if (existing == null) null else ({
            LibraryStore.deleteSmartPlaylist(existing.id)
            com.opentune.data.Pills.done("Smart playlist deleted", context)
            // Its page, under the editor, sees it gone and leaves too.
            onBack()
        }),
    )
}

/** The editor itself, given the playlist being changed (null for a new one) and what its count is worked out from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SmartEditorPage(
    existing: SmartPlaylist?,
    facts: SmartPlaylists.Facts?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSave: (String, SmartRules) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val first = SmartPlaylists.presets.first()
    val startName = existing?.name ?: first.first
    val startRules = existing?.rules ?: first.second
    // Both kept through a turn of the phone, so the name and its rules never come apart.
    var name by rememberSaveable { mutableStateOf(startName) }
    var rules by rememberSaveable(stateSaver = RulesSaver) { mutableStateOf(startRules) }
    var choice by remember { mutableStateOf<SmartChoice?>(null) }
    var sheet by remember { mutableStateOf<RuleSheet?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val seed = existing?.id?.hashCode()?.toLong() ?: 0L
    // Counted without the limit, so the count can say how many the limit keeps.
    val matches = rememberSmartSongs(rules.copy(limit = null), seed, facts)
    val cleaned = rules.copy(conditions = rules.conditions.map { if (it is SmartRule.Artist) SmartRule.Artist(it.name.trim()) else it })
    val changed = name.trim() != startName || cleaned != startRules
    val problem = smartProblem(name, rules)
    val presetNames = remember { SmartPlaylists.presets.map { it.first }.toSet() }
    val poster = posterLayout()

    fun save() { if (problem == null) onSave(name.trim(), cleaned) }
    // Leaving with changes asks first, by the back button and by the back gesture alike.
    val leave = { if (changed) leaving = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = changed) { leaving = true }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + PREVIEW_ROOM),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "head") { PageHeader(if (existing == null) "New smart playlist" else "Edit smart playlist", onBack = leave) }
            item(key = "name") {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("Name") },
                    isError = name.isBlank(),
                    supportingText = if (name.isBlank()) { { Text("Give it a name to save it") } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (existing == null) {
                item(key = "templates-label") { GroupLabel("Start from a template") }
                item(key = "templates") {
                    LazyRow(Modifier.selectableGroup(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(SmartPlaylists.presets, key = { it.first }) { (presetName, preset) ->
                            TemplateCard(presetName, preset, selected = rules == preset) {
                                // A name of your own stays; one a template gave follows the template.
                                if (name.isBlank() || name.trim() in presetNames) name = presetName
                                rules = preset
                            }
                        }
                    }
                }
            }
            item(key = "source-label") { GroupLabel("Songs from") }
            item(key = "source") { SourcePicker(rules.from) { from -> rules = rules.copy(from = from) } }
            item(key = "rules-label") { GroupLabel("Rules") }
            if (rules.conditions.size >= 2) item(key = "match") {
                Column(Modifier.animateItem().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    com.opentune.ui.components.PillSegmented(
                        listOf(SmartMatch.ALL, SmartMatch.ANY), rules.match,
                        { if (it == SmartMatch.ALL) "Match all rules" else "Match any rule" },
                        { rules = rules.copy(match = it) },
                    )
                }
            }
            val count = rules.conditions.size
            // Keyed by kind and which of that kind, so a rule changed in place keeps its row.
            val ruleKeys = rules.conditions.mapIndexed { i, r -> "rule-${RuleKind.of(r)}-${rules.conditions.take(i).count { RuleKind.of(it) == RuleKind.of(r) }}" }
            val clashing = clashingRules(rules)
            itemsIndexed(rules.conditions, key = { i, _ -> ruleKeys[i] }) { i, rule ->
                RuleRow(
                    rule,
                    position = if (i == 0) CardPosition.TOP else CardPosition.MIDDLE,
                    joiner = if (i == 0) null else if (rules.match == SmartMatch.ALL) "and" else "or",
                    clashes = i in clashing,
                    onEdit = if (RuleKind.of(rule).plain) null else ({ sheet = RuleSheet(i) }),
                    onRemove = { rules = rules.copy(conditions = rules.conditions.filterIndexed { j, _ -> j != i }) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "add-rule") {
                AddRuleRow(
                    position = if (count == 0) CardPosition.ONLY else CardPosition.BOTTOM,
                    hint = if (count == 0) "No rules yet, so all of these songs are in. Add a rule to narrow them down." else null,
                    onClick = { sheet = RuleSheet(null) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (rules.contradictory) item(key = "contradiction") {
                ProblemNote(CONTRADICTION, Modifier.animateItem().padding(top = 12.dp))
            }
            item(key = "order-label") { GroupLabel("Order") }
            item(key = "order") {
                GroupCard {
                    NavRow("Sort by", { choice = SmartChoice.SORT }, value = rules.sort.label(), icon = ImageVector.vectorResource(AppIcons.Sort))
                    RowDivider()
                    NavRow("How many", { choice = SmartChoice.LIMIT }, value = limitLabel(rules.limit), icon = ImageVector.vectorResource(AppIcons.FormatListBulleted))
                }
            }
            if (onDelete != null) item(key = "delete") {
                androidx.compose.material3.TextButton(
                    onClick = { deleting = true },
                    modifier = Modifier.padding(horizontal = if (poster) 16.dp else 20.dp, vertical = 16.dp).heightIn(min = 48.dp),
                ) {
                    Icon(ImageVector.vectorResource(AppIcons.Delete), null, tint = MaterialTheme.colorScheme.error)
                    Text("Delete smart playlist", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        PreviewBar(
            name = name, matches = matches?.size, limit = rules.limit, problem = problem, covers = matches.orEmpty().take(4).map { it.thumbnailUrl },
            canSave = problem == null, onSave = ::save,
            // Above the dock and mini player, read as it's placed so their easing moves only the bar.
            modifier = Modifier.align(Alignment.BottomCenter).offset { androidx.compose.ui.unit.IntOffset(0, -contentPadding.calculateBottomPadding().roundToPx()) },
        )
    }

    if (deleting && existing != null && onDelete != null) {
        com.opentune.ui.components.FloatingDialog(
            onDismissRequest = { deleting = false },
            title = { Text(Tr.f("Delete {0}?", existing.name)) },
            text = { Text("Only the rules go. Your songs, likes and history stay as they are.") },
            confirmButton = { SheetButton("Delete", tone = SheetTone.Danger, onClick = { deleting = false; onDelete() }) },
            dismissButton = { SheetButton("Cancel", onClick = { deleting = false }, closes = true) },
        )
    }

    if (leaving) {
        com.opentune.ui.components.FloatingDialog(
            onDismissRequest = { leaving = false },
            title = { Text(if (existing == null) "Discard this smart playlist?" else "Discard your changes?") },
            text = { Text(if (existing == null) "It hasn't been saved yet." else "The playlist keeps the rules it had.") },
            confirmButton = { SheetButton("Discard", tone = SheetTone.Danger, onClick = { leaving = false; onBack() }) },
            dismissButton = { SheetButton("Keep editing", onClick = { leaving = false }, closes = true) },
        )
    }

    sheet?.let { open ->
        RuleSheetCard(
            editing = open.index?.let { rules.conditions.getOrNull(it) },
            rules = rules,
            index = open.index,
            facts = facts,
            seed = seed,
            onDone = { rule ->
                val list = rules.conditions.toMutableList()
                if (open.index != null && open.index in list.indices) list[open.index] = rule else list += rule
                rules = rules.copy(conditions = list)
            },
            onDismiss = { sheet = null },
        )
    }

    when (choice) {
        SmartChoice.SORT -> ChoiceSheet("Sort by", SmartSort.entries, rules.sort, { it.label() }, { rules = rules.copy(sort = it) }, { choice = null })
        SmartChoice.LIMIT -> ChoiceSheet("How many", listOf(25, 50, 100, 250, null), rules.limit, ::limitLabel, { rules = rules.copy(limit = it) }, { choice = null })
        null -> {}
    }
}

/** Room kept under the editor's list for the preview bar floating over its foot. */
private val PREVIEW_ROOM = 112.dp

/** Where a row sits in a card made of separate list items, for its corners. */
private enum class CardPosition { ONLY, TOP, MIDDLE, BOTTOM }

/**
 * The card a row of the rules belongs to, drawn piece by piece so each row can be its own list
 * item (and come and go with its own motion): rounded at the card's top and foot, as GroupCard
 * is. The Poster layout has no card, as GroupCard has none there.
 */
@Composable
private fun Modifier.cardPiece(position: CardPosition): Modifier {
    if (posterLayout()) return this.padding(horizontal = 8.dp)
    val r = 28.dp
    val shape = when (position) {
        CardPosition.ONLY -> RoundedCornerShape(r)
        CardPosition.TOP -> RoundedCornerShape(topStart = r, topEnd = r)
        CardPosition.MIDDLE -> RectangleShape
        CardPosition.BOTTOM -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
    }
    return this.padding(horizontal = 16.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
}

/** A rule's mark: its icon on a soft tile of the accent. */
@Composable
private fun RuleIcon(icon: Int, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(ImageVector.vectorResource(icon), null, Modifier.size(22.dp), tint = tint) }
}

/**
 * One rule in words, with "and" or "or" before it saying how it joins the one above. Tapping it
 * opens it to change; the cross takes it out.
 */
@Composable
private fun RuleRow(rule: SmartRule, position: CardPosition, joiner: String?, clashes: Boolean, onEdit: (() -> Unit)?, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val label = ruleLabel(rule)
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().cardPiece(position)) {
        if (joiner != null) {
            // The join between this rule and the last, on the divider between them.
            Row(Modifier.fillMaxWidth().padding(start = 76.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Tr.t(joiner).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp),
                )
                androidx.compose.material3.HorizontalDivider(
                    Modifier.weight(1f), thickness = 0.75.dp,
                    color = if (posterLayout()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .then(if (onEdit != null) Modifier.clickable(onClickLabel = Tr.t("Change this rule"), role = Role.Button, onClick = onEdit) else Modifier)
                .padding(start = 20.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A rule that can't be met with another wears the error colour and says so, where it is.
            if (clashes) RuleIcon(AppIcons.ErrorOutline, c.error) else RuleIcon(RuleKind.of(rule).icon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                if (clashes || onEdit != null) Text(
                    if (clashes) "Can't be met along with another rule" else "Tap to change",
                    style = MaterialTheme.typography.bodySmall, color = if (clashes) c.error else c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(ImageVector.vectorResource(AppIcons.Close), Tr.f("Remove rule: {0}", label), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The card's last row: Add a rule, and with no rules yet, what that means. */
@Composable
private fun AddRuleRow(position: CardPosition, hint: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().cardPiece(position)) {
        if (position != CardPosition.ONLY) androidx.compose.material3.HorizontalDivider(
            Modifier.padding(start = 76.dp), thickness = 0.75.dp,
            color = if (posterLayout()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else MaterialTheme.colorScheme.outlineVariant,
        )
        if (hint != null) Text(
            hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface),
                contentAlignment = Alignment.Center,
            ) { Icon(ImageVector.vectorResource(AppIcons.Add), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.surface) }
            Spacer(Modifier.width(16.dp))
            Text("Add a rule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** A problem with the rules, said plainly, in the error colours. */
@Composable
private fun ProblemNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(ImageVector.vectorResource(AppIcons.ErrorOutline), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/** A template to start from: its gradient and mark, its name, and its rules in a sentence. */
@Composable
private fun TemplateCard(name: String, rules: SmartRules, selected: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val reduce = AppSettings.ui.collectAsState().value.reduceAnimation
    val ring by animateColorAsState(if (selected) ink else Color.Transparent, if (reduce) snap() else tween(180), label = "templateRing")
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.width(176.dp).height(150.dp).clip(shape)
            .background(if (posterLayout()) ink.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surfaceContainer)
            .drawBehind {
                // The ring is drawn, not laid out, so choosing a template moves nothing.
                val w = 2.dp.toPx()
                drawRoundRect(ring, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(22.dp.toPx() - w / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            }
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(smartBrush(name)), Alignment.Center) {
                Icon(ImageVector.vectorResource(AppIcons.AutoAwesome), null, Modifier.size(20.dp), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                ImageVector.vectorResource(AppIcons.CheckCircle), null, Modifier.size(22.dp).graphicsLayer { alpha = if (selected) 1f else 0f },
                tint = ink,
            )
        }
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(smartSummary(rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Where the songs come from: four choices in a grid, and what the chosen one means. */
@Composable
private fun SourcePicker(from: SmartSource, onPick: (SmartSource) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartSource.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { s -> SourceChoice(s, s == from, { onPick(s) }, Modifier.weight(1f)) }
                }
            }
        }
        Text(
            from.about(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), minLines = 2, maxLines = 2,
        )
    }
}

/** One place songs can come from: the chosen one filled, as the app's segmented pills fill theirs. */
@Composable
private fun SourceChoice(source: SmartSource, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val reduce = AppSettings.ui.collectAsState().value.reduceAnimation
    val idle = if (posterLayout()) c.onSurface.copy(alpha = 0.06f) else c.surfaceContainer
    val fill by animateColorAsState(if (selected) c.onSurface else idle, if (reduce) snap() else tween(180), label = "sourceFill")
    val ink by animateColorAsState(if (selected) c.surface else c.onSurface, if (reduce) snap() else tween(180), label = "sourceInk")
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(56.dp).clip(shape)
            // The fill is drawn, not composed, so it eases between choices without laying anything out again.
            .drawBehind { drawRect(fill) }
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ImageVector.vectorResource(source.icon()), null, Modifier.size(20.dp), tint = ink)
        Spacer(Modifier.width(10.dp))
        Text(source.label(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The editor's foot, floating over the list: how many songs match the rules right now (rolling
 * to the new number as they change, never shifting the bar), what the limit keeps or what's
 * stopping a save, and Save.
 */
@Composable
private fun PreviewBar(name: String, matches: Int?, limit: Int?, problem: String?, covers: List<String?>, canSave: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val headline = when (matches) {
        null -> Tr.t("Counting songs…")
        0 -> Tr.t("No songs match yet")
        1 -> Tr.t("1 song matches")
        else -> Tr.f("{0} songs match", matches)
    }
    val detail = when {
        problem != null -> problem
        matches != null && limit != null && matches > limit -> Tr.f("The playlist keeps the first {0}", limit)
        else -> Tr.t("It updates itself as you listen")
    }
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape).background(c.surfaceContainerHigh)
            .border(0.75.dp, c.onSurface.copy(alpha = 0.08f), shape)
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The cover the playlist will have, from the songs that match now.
        SmartCover(name.ifBlank { "Smart playlist" }, covers, Modifier.size(48.dp), RoundedCornerShape(12.dp), markSize = 0.5f)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            RollingText(headline, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), c.onSurface)
            RollingText(detail, MaterialTheme.typography.bodySmall, if (problem != null) c.error else c.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Button(
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier.height(48.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.onSurface, contentColor = c.surface),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) { Text("Save", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * A smart playlist's cover: its songs' covers made into one, as the app's own playlists have
 * them; with none yet, its own gradient and sparkle, as its tile on Library has. [mark] places
 * the sparkle, kept clear of a hero's words over the cover's foot.
 */
@Composable
private fun SmartCover(name: String, covers: List<String?>, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, mark: Alignment = Alignment.Center, markSize: Float = 0.3f) {
    if (covers.any { !it.isNullOrBlank() }) {
        com.opentune.ui.components.PlaylistCover(name, covers, modifier, shape, named = false)
        return
    }
    Box(
        modifier.clip(shape).background(smartBrush(name)).drawBehind {
            // A soft light from the top corner, so the gradient isn't flat.
            val at = androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.12f)
            val r = size.maxDimension * 0.7f
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0f)), at, r), r, at)
        },
    ) {
        Icon(
            ImageVector.vectorResource(AppIcons.AutoAwesome), null,
            Modifier.align(mark).fillMaxWidth(markSize).aspectRatio(1f), tint = Color.White.copy(alpha = 0.92f),
        )
    }
}

/**
 * A line that rolls to its new words when they change: the old ones slide up and fade as the
 * new ones come up from below, both moved by their layers only, so nothing is laid out again.
 */
@Composable
private fun RollingText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduce = AppSettings.ui.collectAsState().value.reduceAnimation
    var shown by remember { mutableStateOf(text) }
    var gone by remember { mutableStateOf<String?>(null) }
    val p = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        if (reduce) { shown = text; return@LaunchedEffect }
        gone = shown
        shown = text
        p.snapTo(0f)
        p.animateTo(1f, tween(220))
        gone = null
    }
    Box(modifier.fillMaxWidth().clipToBounds()) {
        gone?.let { old ->
            Text(old, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer {
                alpha = 1f - p.value
                translationY = -p.value * size.height * 0.6f
            })
        }
        Text(shown, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer {
            alpha = p.value
            translationY = (1f - p.value) * size.height * 0.6f
        })
    }
}

// ---- The rule sheet ----

/**
 * Adding a rule or changing one, in a sheet: first what kind of rule (when adding), then its
 * values with pickers, the rule in words as it will read, and how many songs the playlist
 * would hold with it. Nothing changes until Add rule or Done; Cancel leaves the rules as they were.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleSheetCard(
    editing: SmartRule?,
    rules: SmartRules,
    index: Int?,
    facts: SmartPlaylists.Facts?,
    seed: Long,
    onDone: (SmartRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(editing) }
    val others = rules.conditions.filterIndexed { i, _ -> i != index }
    val current = draft
    // What stops this rule being added as it is, said in the sheet where the count would be.
    val problem = when {
        current is SmartRule.Artist && current.name.isBlank() -> Tr.t("Pick an artist, or type part of a name")
        current != null && current in others -> Tr.t("That rule is already in the list")
        else -> null
    }
    com.opentune.ui.components.FloatingCard(
        onDismiss = onDismiss,
        title = if (editing != null) "Change rule" else if (current == null) "Add a rule" else Tr.t(RuleKind.of(current).title),
        icon = ImageVector.vectorResource(current?.let { RuleKind.of(it).icon } ?: AppIcons.AutoAwesome),
        actions = if (current == null) null else ({
            Box(Modifier.weight(1f)) { SheetButton("Cancel", onClick = onDismiss, tone = SheetTone.Neutral, closes = true) }
            Box(Modifier.weight(1f)) {
                SheetButton(
                    if (editing != null) "Done" else "Add rule",
                    onClick = {
                        val rule = if (current is SmartRule.Artist) SmartRule.Artist(current.name.trim()) else current
                        // Played out first, then the rule lands and the sheet goes.
                        onDone(rule)
                        onDismiss()
                    },
                    tone = SheetTone.Primary, enabled = problem == null, closes = true,
                )
            }
        }),
    ) {
        if (current == null) {
            val card = com.opentune.ui.components.LocalFloatingCard.current
            KindList(rules, onPick = { kind ->
                // Nothing to choose: the rule is added as the sheet plays out.
                if (kind.plain) card?.close { onDone(kind.start()); onDismiss() } ?: run { onDone(kind.start()); onDismiss() }
                else draft = kind.start()
            })
        } else {
            val with = remember(rules, current, index) {
                val list = rules.conditions.toMutableList()
                if (index != null && index in list.indices) list[index] = current else list += current
                rules.copy(conditions = list, limit = null)
            }
            val would = rememberSmartSongs(with, seed, facts)
            RuleValues(current, facts, onChange = { draft = it })
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 14.dp).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    RollingText(ruleLabel(current), MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), MaterialTheme.colorScheme.onSurface)
                    RollingText(
                        problem ?: when (val n = would?.size) {
                            null -> Tr.t("Counting songs…")
                            0 -> Tr.t("No songs would match yet")
                            1 -> Tr.t("1 song would match")
                            else -> Tr.f("{0} songs would match", n)
                        },
                        MaterialTheme.typography.bodySmall, if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The kinds of rule, each with what it does; ones that would add nothing say why and can't be picked. */
@Composable
private fun KindList(rules: SmartRules, onPick: (RuleKind) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        RuleKind.entries.forEach { kind ->
            val why = when (kind) {
                RuleKind.LIKED -> when {
                    rules.from == SmartSource.LIKED -> "Every song here is liked already"
                    SmartRule.Liked in rules.conditions -> "Already a rule"
                    else -> null
                }
                RuleKind.DOWNLOADED -> when {
                    rules.from == SmartSource.DOWNLOADS -> "Every song here is downloaded already"
                    SmartRule.Downloaded in rules.conditions -> "Already a rule"
                    else -> null
                }
                RuleKind.LOSSLESS -> if (SmartRule.Lossless in rules.conditions) "Already a rule" else null
                else -> null
            }
            val enabled = why == null
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp))
                    .clickable(enabled = enabled, role = Role.Button) { onPick(kind) }
                    .graphicsLayer { alpha = if (enabled) 1f else com.opentune.ui.components.DISABLED_ROW_ALPHA }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuleIcon(kind.icon)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(why ?: kind.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!kind.plain && enabled) Icon(ImageVector.vectorResource(AppIcons.KeyboardArrowRight), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The values of one rule, with a picker for each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleValues(rule: SmartRule, facts: SmartPlaylists.Facts?, onChange: (SmartRule) -> Unit) {
    Column(Modifier.padding(horizontal = 6.dp)) {
        when (rule) {
            is SmartRule.Plays -> {
                PickerLabel("At least")
                ChipRow(listOf(1, 2, 3, 5, 10, 20, 50), rule.atLeast, { if (it == 1) Tr.t("Once") else Tr.f("{0} times", it) }) { onChange(rule.copy(atLeast = it)) }
                PickerLabel("In")
                ChipRow(listOf(7, 30, 90, 180, 365, null), rule.withinDays, { if (it == null) Tr.t("Any time") else Tr.f("The last {0}", spanChoice(it).lowercase()) }) { onChange(rule.copy(withinDays = it)) }
            }
            is SmartRule.NotPlayedFor -> {
                PickerLabel("Not played in")
                ChipRow(listOf(7, 30, 90, 180, 365), rule.days, { Tr.f("The last {0}", spanChoice(it).lowercase()) }) { onChange(SmartRule.NotPlayedFor(it)) }
                PickerNote("Songs you've never played count too.")
            }
            is SmartRule.NewWithin -> {
                PickerLabel("First played or downloaded in")
                ChipRow(listOf(7, 30, 90, 180, 365), rule.days, { Tr.f("The last {0}", spanChoice(it).lowercase()) }) { onChange(SmartRule.NewWithin(it)) }
            }
            is SmartRule.Artist -> {
                OutlinedTextField(
                    rule.name, { onChange(SmartRule.Artist(it)) }, singleLine = true,
                    label = { Text("Artist") }, placeholder = { Text("Type part of a name") },
                    supportingText = { Text("Part of the name is enough. Case and accents don't matter") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                val known = remember(facts) { facts?.let { SmartPlaylists.artists(it) }.orEmpty() }
                val typed = SmartPlaylists.fold(rule.name.trim())
                val offered = remember(known, typed) {
                    known.filter { typed.isEmpty() || SmartPlaylists.fold(it).contains(typed) }.filter { !it.equals(rule.name.trim(), true) }.take(12)
                }
                if (offered.isNotEmpty()) {
                    PickerLabel("Artists you play")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        offered.forEach { a -> PickerChip(a, selected = false) { onChange(SmartRule.Artist(a)) } }
                    }
                }
            }
            else -> {}
        }
    }
}

@Composable
private fun PickerLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun PickerNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

/** A row of choices that wraps, the chosen one filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> PickerChip(label(o), selected = o == selected) { onPick(o) } }
    }
}

/** One choice: 40dp to the eye, 48dp to the finger. */
@Composable
private fun PickerChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val reduce = AppSettings.ui.collectAsState().value.reduceAnimation
    val fill by animateColorAsState(if (selected) c.onSurface else c.onSurface.copy(alpha = 0.08f), if (reduce) snap() else tween(160), label = "chipFill")
    val ink by animateColorAsState(if (selected) c.surface else c.onSurface, if (reduce) snap() else tween(160), label = "chipInk")
    Box(
        Modifier.minimumInteractiveComponentSize()
            .selectable(selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(40.dp).clip(CircleShape).drawBehind { drawRect(fill) }.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
        }
    }
}
