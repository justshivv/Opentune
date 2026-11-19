package com.opentune.ui.library

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.SmartPlaylist
import com.opentune.data.library.SmartPlaylists
import com.opentune.data.library.SmartRules
import com.opentune.data.library.SmartSort
import com.opentune.data.library.SmartSource
import com.opentune.data.library.SongRef
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.ChoiceSheet
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.NavRow
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.RowDivider
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.ToggleRow
import com.opentune.ui.components.menuClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

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

/** The songs [rules] pick right now, worked out off the main thread. */
@Composable
private fun rememberSmartSongs(rules: SmartRules, seed: Long): List<SongRef>? {
    val facts = rememberSmartFacts()
    return produceState<List<SongRef>?>(null, rules, facts, seed) {
        val f = facts ?: return@produceState
        value = withContext(Dispatchers.Default) { SmartPlaylists.songs(rules, f, seed = seed) }
    }.value
}

/** Rules kept through a turn of the phone or the app being put away, as the JSON they're saved in. */
private val rulesJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
private val RulesSaver = androidx.compose.runtime.saveable.Saver<SmartRules, String>(
    save = { rulesJson.encodeToString(SmartRules.serializer(), it) },
    restore = { runCatching { rulesJson.decodeFromString(SmartRules.serializer(), it) }.getOrNull() },
)

private fun SmartSource.label() = when (this) {
    SmartSource.EVERYTHING -> "Everything on this phone"
    SmartSource.HISTORY -> "Songs you've played"
    SmartSource.LIKED -> "Liked songs"
    SmartSource.DOWNLOADS -> "Downloads"
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

/** A span of days in words, in the reader's language: it's also put inside other lines. */
private fun days(n: Int?): String = when (n) {
    null -> com.opentune.i18n.Tr.t("Off")
    7 -> com.opentune.i18n.Tr.t("A week")
    30 -> com.opentune.i18n.Tr.t("A month")
    90 -> com.opentune.i18n.Tr.t("3 months")
    180 -> com.opentune.i18n.Tr.t("6 months")
    365 -> com.opentune.i18n.Tr.t("A year")
    else -> com.opentune.i18n.Tr.f("{0} days", n)
}

/** The rules in one short line, for under the playlist's name. */
internal fun smartSummary(rules: SmartRules): String = buildList {
    // Joined into one line, so each piece is put in the reader's language on its own.
    val t = com.opentune.i18n.Tr::t
    add(t(rules.from.label()))
    if (rules.minPlays > 0) add(if (rules.playedWithinDays != null) com.opentune.i18n.Tr.f("played {0}+ times in {1}", rules.minPlays, days(rules.playedWithinDays).lowercase()) else com.opentune.i18n.Tr.f("played {0}+ times", rules.minPlays))
    else if (rules.playedWithinDays != null) add(com.opentune.i18n.Tr.f("played in the last {0}", days(rules.playedWithinDays).lowercase()))
    rules.notPlayedForDays?.let { add(com.opentune.i18n.Tr.f("not played for {0}", days(it).lowercase())) }
    rules.newWithinDays?.let { add(com.opentune.i18n.Tr.f("new in the last {0}", days(it).lowercase())) }
    if (rules.likedOnly && rules.from != SmartSource.LIKED) add(com.opentune.i18n.Tr.t("liked"))
    // A lossless download is a download: "downloaded" beside "lossless" says nothing more.
    if (rules.downloadedOnly && rules.from != SmartSource.DOWNLOADS && !rules.losslessOnly) add(com.opentune.i18n.Tr.t("downloaded"))
    if (rules.losslessOnly) add(com.opentune.i18n.Tr.t("lossless"))
    if (rules.artist.isNotBlank()) add(com.opentune.i18n.Tr.f("by {0}", rules.artist.trim()))
    add(t(rules.sort.label()).lowercase())
    rules.limit?.let { add(com.opentune.i18n.Tr.f("up to {0}", it)) }
}.joinToString(" · ")

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

/** A smart playlist's tile: a gradient of its own from its name, and the sparkle mark. */
@Composable
internal fun SmartTile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = ImageVector.vectorResource(AppIcons.AutoAwesome)) {
    val hue = (title.lowercase().hashCode().toUInt() % 360u).toFloat()
    val brush = Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.75f), Color.hsv((hue + 50f) % 360f, 0.65f, 0.45f)))
    Column(modifier.clip(MaterialTheme.shapes.medium).menuClickable(onClick = onClick, onLongClick = null).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(brush), Alignment.Center) {
            Icon(icon, null, Modifier.size(56.dp), tint = Color.White)
        }
        Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

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
    val refs = rememberSmartSongs(playlist.rules, playlist.id.hashCode().toLong())
    val songs = remember(refs) { refs.orEmpty().map { it.toSong() } }
    // The page's background as Settings ask for it, from the first song's cover.
    com.opentune.ui.browse.ListPageTint(coverUrl = songs.firstOrNull()?.thumbnailUrl) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            com.opentune.ui.browse.ListPageWash()
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item {
                    PageHeader(playlist.name, onBack = onBack, actions = {
                        IconButton(onClick = onEdit) { Icon(ImageVector.vectorResource(AppIcons.Edit), "Edit rules") }
                    })
                }
                item {
                    Text(
                        smartSummary(playlist.rules), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                if (refs == null) return@LazyColumn
                if (songs.isEmpty()) {
                    item {
                        MessageState(
                            ImageVector.vectorResource(AppIcons.AutoAwesome),
                            if (playlist.rules.contradictory) "These rules can't both hold" else "Nothing matches yet",
                            Modifier.padding(top = 24.dp),
                            message = if (playlist.rules.contradictory) CONTRADICTION
                            else "Songs appear here once they fit the rules. Keep listening, or edit the rules.",
                            action = { androidx.compose.material3.OutlinedButton(onClick = onEdit) { Text("Edit rules") } },
                        )
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
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (songs.size == 1) "1 song" else com.opentune.i18n.Tr.f("{0} songs", songs.size),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                        )
                        TextButton(onClick = {
                            // A copy that stays as it is now: an ordinary playlist made in the app.
                            val copy = LibraryStore.createPlaylist(playlist.name)
                            LibraryStore.addAllToPlaylist(copy, songs)
                            com.opentune.data.Pills.done("Saved as a playlist", context)
                            onOpenPlaylist(copy)
                        }) {
                            Icon(ImageVector.vectorResource(AppIcons.PlaylistAdd), null, Modifier.size(18.dp))
                            Text("Save as playlist", Modifier.padding(start = 6.dp))
                        }
                    }
                }
                itemsIndexed(songs, key = { _, s -> s.videoId }) { i, song ->
                    SongListItem(
                        song = song,
                        onClick = { actions.playAll(songs, i, false, playlist.name) },
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

/** Which choice sheet is open in the editor. */
private enum class SmartChoice { SOURCE, MIN_PLAYS, PLAYED_WITHIN, NOT_PLAYED, NEW_WITHIN, SORT, LIMIT }

/** Why rules that ask for a song both played and not played over the same days match nothing. */
private const val CONTRADICTION =
    "No song can be played within a span and not played for one as long or longer. Shorten Not played for, or lengthen Played within."

/**
 * Making a smart playlist ([id] null) or changing one: a name, a place to start from,
 * the rules, and how many songs match them right now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartPlaylistEditor(id: String?, contentPadding: PaddingValues, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val existing: SmartPlaylist? = remember(id) { id?.let(LibraryStore::smartPlaylist) }
    if (id != null && existing == null) {
        // Deleted while the editor was on its way: there's nothing left to change.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val startName = existing?.name ?: SmartPlaylists.presets.first().first
    val startRules = existing?.rules ?: SmartPlaylists.presets.first().second
    // Both kept through a turn of the phone, so the name and its rules never come apart.
    var name by rememberSaveable { mutableStateOf(startName) }
    var rules by rememberSaveable(stateSaver = RulesSaver) { mutableStateOf(startRules) }
    var choice by remember { mutableStateOf<SmartChoice?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // Counted without the limit, so the count can say how many the limit keeps.
    val matches = rememberSmartSongs(rules.copy(limit = null), existing?.id?.hashCode()?.toLong() ?: 0L)
    val cleaned = rules.copy(artist = rules.artist.trim())
    val changed = name.trim() != startName || cleaned != startRules
    val presetNames = remember { SmartPlaylists.presets.map { it.first }.toSet() }

    fun save() {
        val saved = if (existing == null) LibraryStore.createSmartPlaylist(name, cleaned)
        else existing.id.also { LibraryStore.updateSmartPlaylist(it, name, cleaned) }
        onSaved(saved)
    }
    // Leaving with changes asks first, by the back button and by the back gesture alike.
    val leave = { if (changed) leaving = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = changed) { leaving = true }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader(if (existing == null) "New smart playlist" else "Edit smart playlist", onBack = leave, actions = {
                TextButton(onClick = ::save, enabled = name.isNotBlank()) { Text("Save", fontWeight = FontWeight.SemiBold) }
            })
        }
        item {
            OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text("Name") },
                isError = name.isBlank(),
                supportingText = if (name.isBlank()) { { Text("Give it a name to save it") } } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (existing == null) {
            item { GroupLabel("Start from") }
            item {
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartPlaylists.presets.forEach { (presetName, preset) ->
                        FilterChip(selected = rules == preset, onClick = {
                            // A name of your own stays; one a preset gave follows the preset.
                            if (name.isBlank() || name.trim() in presetNames) name = presetName
                            rules = preset
                        }, label = { Text(presetName) })
                    }
                }
            }
        }
        item { GroupLabel("Songs") }
        item {
            GroupCard {
                NavRow("Songs from", { choice = SmartChoice.SOURCE }, value = rules.from.label())
                RowDivider()
                NavRow("Played at least", { choice = SmartChoice.MIN_PLAYS }, value = if (rules.minPlays <= 0) "Any" else com.opentune.i18n.Tr.f("{0} times", rules.minPlays))
                RowDivider()
                NavRow("Played within", { choice = SmartChoice.PLAYED_WITHIN }, value = rules.playedWithinDays?.let(::days) ?: "Any time")
                RowDivider()
                NavRow("Not played for", { choice = SmartChoice.NOT_PLAYED }, value = days(rules.notPlayedForDays))
                RowDivider()
                NavRow("New to you within", { choice = SmartChoice.NEW_WITHIN }, value = days(rules.newWithinDays),
                    summary = "First played or downloaded")
            }
        }
        if (rules.contradictory) item {
            Text(
                CONTRADICTION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        item { GroupLabel("Only") }
        item {
            GroupCard {
                // What the songs come from may already say it: shown on, and greyed with why.
                ToggleRow(
                    "Liked songs", rules.likedOnly || rules.from == SmartSource.LIKED, { rules = rules.copy(likedOnly = it) },
                    enabled = rules.from != SmartSource.LIKED,
                    disabledReason = "Every song here is liked: they come from Liked songs",
                )
                RowDivider()
                ToggleRow(
                    "Downloaded songs", rules.downloadedOnly || rules.from == SmartSource.DOWNLOADS || rules.losslessOnly,
                    { rules = rules.copy(downloadedOnly = it) },
                    enabled = rules.from != SmartSource.DOWNLOADS && !rules.losslessOnly,
                    disabledReason = if (rules.from == SmartSource.DOWNLOADS) "Every song here is downloaded: they come from Downloads"
                    else "Lossless downloads are downloaded already",
                )
                RowDivider()
                ToggleRow("Lossless downloads", rules.losslessOnly, { rules = rules.copy(losslessOnly = it) }, summary = "Downloaded from a lossless add-on")
            }
        }
        item {
            OutlinedTextField(
                rules.artist, { rules = rules.copy(artist = it) }, singleLine = true,
                label = { Text("Artist") }, placeholder = { Text("Any artist") },
                supportingText = { Text("Part of the name is enough. Case and accents don't matter") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        item { GroupLabel("Order") }
        item {
            GroupCard {
                NavRow("Sort by", { choice = SmartChoice.SORT }, value = rules.sort.label())
                RowDivider()
                NavRow("How many", { choice = SmartChoice.LIMIT }, value = rules.limit?.let { com.opentune.i18n.Tr.f("{0} songs", it) } ?: com.opentune.i18n.Tr.t("All"))
            }
        }
        item {
            val limit = rules.limit
            Text(
                when (val n = matches?.size) {
                    null -> "Counting…"
                    0 -> if (rules.contradictory) "No song can match these rules" else "No songs match right now"
                    1 -> "1 song matches right now"
                    else -> if (limit != null && n > limit) com.opentune.i18n.Tr.f("{0} songs match right now. The playlist keeps the first {1}", n, limit)
                    else com.opentune.i18n.Tr.f("{0} songs match right now", n)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
        if (existing != null) item {
            TextButton(onClick = { deleting = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(ImageVector.vectorResource(AppIcons.Delete), null, tint = MaterialTheme.colorScheme.error)
                Text("Delete smart playlist", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (deleting && existing != null) {
        com.opentune.ui.components.FloatingDialog(
            onDismissRequest = { deleting = false },
            title = { Text(com.opentune.i18n.Tr.f("Delete {0}?", existing.name)) },
            text = { Text("Only the rules go. Your songs, likes and history stay as they are.") },
            confirmButton = {
                com.opentune.ui.components.SheetButton("Delete", tone = com.opentune.ui.components.SheetTone.Danger, onClick = {
                    deleting = false
                    LibraryStore.deleteSmartPlaylist(existing.id)
                    com.opentune.data.Pills.done("Smart playlist deleted", context)
                    // Its page, under the editor, sees it gone and leaves too.
                    onBack()
                })
            },
            dismissButton = { com.opentune.ui.components.SheetButton("Cancel", onClick = { deleting = false }, closes = true) },
        )
    }

    if (leaving) {
        com.opentune.ui.components.FloatingDialog(
            onDismissRequest = { leaving = false },
            title = { Text(if (existing == null) "Discard this smart playlist?" else "Discard your changes?") },
            text = { Text(if (existing == null) "It hasn't been saved yet." else "The playlist keeps the rules it had.") },
            confirmButton = {
                com.opentune.ui.components.SheetButton("Discard", tone = com.opentune.ui.components.SheetTone.Danger, onClick = { leaving = false; onBack() })
            },
            dismissButton = { com.opentune.ui.components.SheetButton("Keep editing", onClick = { leaving = false }, closes = true) },
        )
    }

    when (choice) {
        SmartChoice.SOURCE -> ChoiceSheet("Songs from", SmartSource.entries, rules.from, { it.label() }, { rules = rules.copy(from = it) }, { choice = null })
        SmartChoice.MIN_PLAYS -> ChoiceSheet("Played at least", listOf(0, 2, 3, 5, 10, 20), rules.minPlays, { if (it == 0) "Any" else com.opentune.i18n.Tr.f("{0} times", it) }, { rules = rules.copy(minPlays = it) }, { choice = null })
        SmartChoice.PLAYED_WITHIN -> ChoiceSheet("Played within", listOf(null, 7, 30, 90, 365), rules.playedWithinDays, { it?.let(::days) ?: "Any time" }, { rules = rules.copy(playedWithinDays = it) }, { choice = null })
        SmartChoice.NOT_PLAYED -> ChoiceSheet("Not played for", listOf(null, 30, 90, 180, 365), rules.notPlayedForDays, ::days, { rules = rules.copy(notPlayedForDays = it) }, { choice = null })
        SmartChoice.NEW_WITHIN -> ChoiceSheet("New to you within", listOf(null, 7, 30, 90), rules.newWithinDays, ::days, { rules = rules.copy(newWithinDays = it) }, { choice = null })
        SmartChoice.SORT -> ChoiceSheet("Sort by", SmartSort.entries, rules.sort, { it.label() }, { rules = rules.copy(sort = it) }, { choice = null })
        SmartChoice.LIMIT -> ChoiceSheet("How many", listOf(25, 50, 100, 250, null), rules.limit, { it?.let { n -> com.opentune.i18n.Tr.f("{0} songs", n) } ?: com.opentune.i18n.Tr.t("All") }, { rules = rules.copy(limit = it) }, { choice = null })
        null -> {}
    }
}
