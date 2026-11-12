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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlaylistAdd
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
import kotlinx.coroutines.withContext

/** What a smart playlist's songs are worked out from, kept up to date as they change. */
@Composable
private fun rememberSmartFacts(): SmartPlaylists.Facts? {
    val flow = remember {
        combine(History.records, LibraryStore.liked, Downloads.entries, LibraryStore.playlists) { h, l, d, p ->
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

private fun days(n: Int?): String = when (n) {
    null -> "Off"
    7 -> "A week"
    30 -> "A month"
    90 -> "3 months"
    180 -> "6 months"
    365 -> "A year"
    else -> com.opentune.i18n.Tr.f("{0} days", n)
}

/** The rules in one short line, for under the playlist's name. */
internal fun smartSummary(rules: SmartRules): String = buildList {
    add(rules.from.label())
    if (rules.minPlays > 0) add(if (rules.playedWithinDays != null) com.opentune.i18n.Tr.f("played {0}+ times in {1}", rules.minPlays, days(rules.playedWithinDays).lowercase()) else com.opentune.i18n.Tr.f("played {0}+ times", rules.minPlays))
    else if (rules.playedWithinDays != null) add(com.opentune.i18n.Tr.f("played in the last {0}", days(rules.playedWithinDays).lowercase()))
    rules.notPlayedForDays?.let { add(com.opentune.i18n.Tr.f("not played for {0}", days(it).lowercase())) }
    rules.newWithinDays?.let { add(com.opentune.i18n.Tr.f("new in the last {0}", days(it).lowercase())) }
    if (rules.likedOnly && rules.from != SmartSource.LIKED) add("liked")
    if (rules.downloadedOnly && rules.from != SmartSource.DOWNLOADS) add("downloaded")
    if (rules.losslessOnly) add("lossless")
    if (rules.artist.isNotBlank()) add(com.opentune.i18n.Tr.f("by {0}", rules.artist.trim()))
    add(rules.sort.label().lowercase())
    rules.limit?.let { add(com.opentune.i18n.Tr.f("up to {0}", it)) }
}.joinToString(" · ")

/** Smart playlists as a row of tiles on Library, with one to make a new one. */
@Composable
internal fun SmartPlaylistsRow(onOpen: (String) -> Unit, onNew: () -> Unit, tileWidth: androidx.compose.ui.unit.Dp) {
    val smart by LibraryStore.smartPlaylists.collectAsState()
    SectionHeader("Smart playlists")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        items(smart, key = { it.id }) { p -> SmartTile(p.name, smartSummary(p.rules), { onOpen(p.id) }, Modifier.width(tileWidth)) }
        item(key = "new-smart") {
            SmartTile("New smart playlist", "Fills itself from rules", onNew, Modifier.width(tileWidth), icon = Icons.Rounded.Add)
        }
    }
}

/** A smart playlist's tile: a gradient of its own from its name, and the sparkle mark. */
@Composable
private fun SmartTile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.AutoAwesome) {
    val hue = (title.lowercase().hashCode().toUInt() % 360u).toFloat()
    val brush = Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.75f), Color.hsv((hue + 50f) % 360f, 0.65f, 0.45f)))
    Column(modifier.clip(MaterialTheme.shapes.large).menuClickable(onClick = onClick, onLongClick = null).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).background(brush), Alignment.Center) {
            Icon(icon, null, Modifier.size(56.dp), tint = Color.White)
        }
        Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit rules") }
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
                MessageState(Icons.Rounded.AutoAwesome, "Nothing matches yet", Modifier.padding(top = 24.dp),
                    message = "Songs appear here once they fit the rules. Keep listening, or edit the rules.")
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
                    Icon(Icons.Rounded.PlaylistAdd, null, Modifier.size(18.dp))
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

/**
 * Making a smart playlist ([id] null) or changing one: a name, a place to start from,
 * the rules, and how many songs match them right now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartPlaylistEditor(id: String?, contentPadding: PaddingValues, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val existing: SmartPlaylist? = remember(id) { id?.let(LibraryStore::smartPlaylist) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: SmartPlaylists.presets.first().first) }
    var rules by remember { mutableStateOf(existing?.rules ?: SmartPlaylists.presets.first().second) }
    var choice by remember { mutableStateOf<SmartChoice?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val matches = rememberSmartSongs(rules, existing?.id?.hashCode()?.toLong() ?: 0L)

    fun save() {
        val saved = if (existing == null) LibraryStore.createSmartPlaylist(name, rules)
            else existing.id.also { LibraryStore.updateSmartPlaylist(it, name, rules) }
        onSaved(saved)
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader(if (existing == null) "New smart playlist" else "Edit smart playlist", onBack = onBack, actions = {
                TextButton(onClick = ::save, enabled = name.isNotBlank()) { Text("Save", fontWeight = FontWeight.SemiBold) }
            })
        }
        item {
            OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (existing == null) {
            item { GroupLabel("Start from") }
            item {
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartPlaylists.presets.forEach { (presetName, preset) ->
                        FilterChip(selected = rules == preset, onClick = { name = presetName; rules = preset }, label = { Text(presetName) })
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
                NavRow("New to you within", { choice = SmartChoice.NEW_WITHIN }, value = days(rules.newWithinDays))
            }
        }
        item { GroupLabel("Only") }
        item {
            GroupCard {
                ToggleRow("Liked songs", rules.likedOnly, { rules = rules.copy(likedOnly = it) })
                RowDivider()
                ToggleRow("Downloaded songs", rules.downloadedOnly, { rules = rules.copy(downloadedOnly = it) })
                RowDivider()
                ToggleRow("Lossless downloads", rules.losslessOnly, { rules = rules.copy(losslessOnly = it) }, summary = "Downloaded from a lossless add-on")
            }
        }
        item {
            OutlinedTextField(
                rules.artist, { rules = rules.copy(artist = it) }, singleLine = true,
                label = { Text("Artist") }, placeholder = { Text("Any artist") },
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
            Text(
                when (val n = matches?.size) {
                    null -> "Counting…"
                    0 -> "No songs match right now"
                    1 -> "1 song matches right now"
                    else -> com.opentune.i18n.Tr.f("{0} songs match right now", n)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
        if (existing != null) item {
            TextButton(onClick = { deleting = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                Text("Delete smart playlist", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (deleting && existing != null) {
        com.opentune.ui.components.FloatingDialog(
            onDismissRequest = { deleting = false },
            title = { Text(com.opentune.i18n.Tr.f("Delete {0}?", existing.name)) },
            text = { Text("Only the rules go. Your songs, likes and history stay as they are.") },
            confirmButton = { com.opentune.ui.components.SheetButton("Delete", onClick = { deleting = false; LibraryStore.deleteSmartPlaylist(existing.id); onBack() }) },
            dismissButton = { com.opentune.ui.components.SheetButton("Cancel", onClick = { deleting = false }, closes = true) },
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
