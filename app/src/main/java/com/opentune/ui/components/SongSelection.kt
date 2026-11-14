package com.opentune.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentune.data.download.Downloads
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.isYouTubeId

/**
 * Songs picked on a page, to act on together: like them, add them to a playlist or
 * the queue, download them, or take them out of the playlist. Each row is picked by
 * its own key, so the same song twice in a playlist is two rows.
 */
@Stable
class SongSelection {
    val picked = mutableStateMapOf<String, Song>()
    var active by mutableStateOf(false)
        private set

    fun start(key: String, song: Song) {
        active = true
        picked[key] = song
    }

    fun toggle(key: String, song: Song) {
        if (picked.remove(key) == null) picked[key] = song
    }

    fun clear() {
        picked.clear()
        active = false
    }
}

/** The page's selection, when it has one; song rows join it. */
val LocalSongSelection = compositionLocalOf<SongSelection?> { null }

@Composable
fun rememberSongSelection(): SongSelection = remember { SongSelection() }

/** A row's tick while picking: an empty ring, or filled with the accent and a check. */
@Composable
internal fun SelectionTick(picked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.padding(horizontal = 12.dp).size(26.dp).clip(RoundedCornerShape(8.dp))
            .then(if (picked) Modifier.background(scheme.primary) else Modifier.border(2.dp, scheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))),
        Alignment.Center,
    ) {
        if (picked) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = scheme.onPrimary)
    }
}

/**
 * The bar over the top of a page while songs are picked: how many, Select all, and what
 * can be done with them. [rows] are the page's rows in order (key and song), for
 * Select all and to keep the picked songs in the page's order. [onRemove] takes the
 * picked rows out of the page's list, where that can be done (your own playlist).
 */
@Composable
fun SongSelectionBar(
    selection: SongSelection,
    rows: List<Pair<String, Song>>,
    onAddToQueue: (List<Song>) -> Unit,
    modifier: Modifier = Modifier,
    onRemove: ((Set<String>) -> Unit)? = null,
    removeLabel: String = "Remove",
) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf<List<Song>?>(null) }
    adding?.let { songs -> AddAllToPlaylistDialog(songs, onDismiss = { adding = null }, onDone = { adding = null; selection.clear() }) }
    androidx.activity.compose.BackHandler(enabled = selection.active) { selection.clear() }
    AnimatedVisibility(
        selection.active,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val scheme = MaterialTheme.colorScheme
        val liked by LibraryStore.liked.collectAsState()
        val inOrder = rows.filter { selection.picked.containsKey(it.first) }.map { it.second }
        val count = selection.picked.size
        val allLiked = inOrder.isNotEmpty() && inOrder.all { s -> liked.any { it.videoId == s.videoId } }
        Column(
            Modifier.fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(scheme.surfaceContainerHigh)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selection.clear() }) { Icon(Icons.Rounded.Close, "Stop selecting") }
                Text(if (count == 1) "1 selected" else com.opentune.i18n.Tr.f("{0} selected", count), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                val everything = count == rows.size && rows.isNotEmpty()
                TextButton(onClick = {
                    if (everything) selection.picked.clear() else rows.forEach { (k, s) -> selection.picked[k] = s }
                }) { Text(if (everything) "Select none" else "Select all", fontWeight = FontWeight.SemiBold) }
            }
            // One height for every tile, the tallest label's.
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val enabled = count > 0
                BarAction(if (allLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (allLiked) "Unlike" else "Like", enabled) {
                    inOrder.forEach { LibraryStore.setLiked(it, !allLiked) }
                    com.opentune.data.Pills.done(if (allLiked) com.opentune.i18n.Tr.f("Removed {0} from Liked", inOrder.size) else com.opentune.i18n.Tr.f("Liked {0} {1}", inOrder.size, if (inOrder.size == 1) "song" else "songs"), context)
                    selection.clear()
                }
                BarAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", enabled) { adding = inOrder }
                BarAction(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", enabled) {
                    onAddToQueue(inOrder)
                    com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Added {0} to the queue", inOrder.size), context)
                    selection.clear()
                }
                if (onRemove != null) BarAction(Icons.Rounded.RemoveCircleOutline, removeLabel, enabled) {
                    onRemove(selection.picked.keys.toSet())
                    selection.clear()
                }
                BarAction(Icons.Rounded.Download, "Download", enabled && inOrder.any { isYouTubeId(it.videoId) }) {
                    val online = inOrder.filter { isYouTubeId(it.videoId) }
                    online.forEach { Downloads.enqueue(context, it) }
                    com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Downloading {0} {1}", online.size, if (online.size == 1) "song" else "songs"), context)
                    selection.clear()
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp))
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .menuClickable(onClick = { if (enabled) onClick() }, onLongClick = null)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tint = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f)
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Picks one of your playlists (or a new one) and adds every song in [songs] to it, skipping those already there. */
@Composable
internal fun AddAllToPlaylistDialog(songs: List<Song>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val all by LibraryStore.playlists.collectAsState()
    val playlists = remember(all) { all.filter { it.spotifyId == null } }
    // Signed in, the account's own playlists on YouTube Music are offered too, as for one song:
    // without them, someone whose playlists are all there was sent straight to naming a new one.
    val signedIn by com.opentune.data.account.AccountStore.signedIn.collectAsState()
    val account by com.opentune.data.library.LibraryCache.playlists.collectAsState()
    val owned by com.opentune.data.library.LibraryCache.owned.collectAsState()
    val online = signedIn && songs.any { isYouTubeId(it.videoId) }
    val accountPlaylists = if (online) remember(account, owned, playlists) { com.opentune.data.library.PlaylistLinks.pickable(account, owned, playlists) } else emptyList()
    val ids = remember(songs) { songs.mapTo(HashSet()) { it.videoId } }
    fun addTo(id: String) {
        val have = LibraryStore.playlist(id)?.songs?.map { it.videoId }?.toHashSet().orEmpty()
        val fresh = songs.filter { it.videoId !in have }
        if (fresh.isNotEmpty()) LibraryStore.addAllToPlaylist(id, fresh)
    }
    PlaylistPicker(
        songs = songs,
        local = playlists.map { p ->
            localTarget(p, p.songs.count { it.videoId in ids }, add = { addTo(p.id) }, remove = { LibraryStore.removeSongsFromPlaylist(p.id, ids) })
        },
        account = accountPlaylists.map { p ->
            accountTarget(p, null, add = {
                com.opentune.data.library.PlaylistSync.addAllToAccountPlaylist(p.playlistId, songs) { ok ->
                    if (!ok) com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Couldn't add them to {0}", p.title), context)
                }
            })
        },
        online = online,
        onCreate = { name, privacy ->
            val id = LibraryStore.createPlaylist(name, privacy = privacy)
            addTo(id)
            com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Added {0} to {1}", songs.size, name), context)
            onDone()
        },
        onDone = onDone,
        onDismiss = onDismiss,
    )
}
