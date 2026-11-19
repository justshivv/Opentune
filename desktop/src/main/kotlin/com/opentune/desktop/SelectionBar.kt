package com.opentune.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opentune.data.isYouTubeId
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The page's selection bar, floating over the foot of the page while songs are picked; going to
 * another page lets the selection go. Put once over the pages, keyed by the page shown.
 */
@Composable
internal fun PageSelectionBar(page: Any?, modifier: Modifier = Modifier) {
    LaunchedEffect(page) { PageSelection.clear() }
    SelectionBar(PageSelection, modifier)
}

/**
 * What can be done with the songs picked, on a frosted capsule as the web player's menus are
 * made: how many, Select all, and Play, Play next, Add to queue, Add to a playlist (yours here
 * and, signed in, your YouTube Music ones), Like or Unlike, and, on your own playlist, Remove.
 * In a room the songs can go to the room's queue. The queue's own selection plays the picked
 * songs next, moves them to the end or takes them out instead. On a narrow page only Play keeps
 * its label. Done, the selection goes, as on the phone.
 */
@Composable
internal fun SelectionBar(selection: SongSelection, modifier: Modifier = Modifier) {
    val shown = selection.active
    AnimatedVisibility(
        shown, modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(260, easing = Web.Emph)) { it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 2 },
    ) {
        BoxWithConstraints(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            SelectionBarBody(selection, wide = maxWidth >= 860.dp)
        }
    }
}

@Composable
internal fun SelectionBarBody(selection: SongSelection, wide: Boolean) {
    val liked by Library.liked.collectAsState()
    val room by com.opentune.data.together.Together.room.collectAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val songs = selection.inOrder()
    val count = selection.count
    val likedIds = remember(liked) { liked.mapTo(HashSet()) { it.videoId } }
    val allLiked = songs.isNotEmpty() && songs.all { it.videoId in likedIds }
    var adding by remember { mutableStateOf(false) }
    val done = { selection.clear() }
    val dark = darkTheme()
    val material = lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onSurface, if (dark) 0.08f else 0.03f).copy(alpha = 0.97f)
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f), spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f))
            .clip(shape).background(material).border(0.5.dp, WebInk.vibrantDivider, shape)
            .height(56.dp).padding(horizontal = 8.dp)
            .semantics { contentDescription = "$count selected" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BarIcon(Icons.Rounded.Close, "Clear the selection", onClick = done)
        Text(
            if (count == 1) "1 selected" else "$count selected", style = WebType.title3Emph, color = PageInk.text, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(start = 4.dp, end = 6.dp).widthIn(min = 82.dp),
        )
        val everything = selection.all
        BarButton(
            if (everything) Icons.Rounded.Deselect else Icons.Rounded.SelectAll, if (everything) "Select none" else "Select all", wide,
        ) { if (everything) selection.clear() else selection.selectAll() }
        BarDivider()
        if (selection.forQueue) {
            val picked = selection.indicesIn(queue).filter { it != index }
            BarButton(Icons.Rounded.PlayArrow, "Play", true, solid = true, enabled = songs.isNotEmpty()) {
                selection.indicesIn(queue).firstOrNull()?.let(Player::playAt); done()
            }
            BarButton(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", wide, enabled = picked.isNotEmpty()) {
                QueueEdit.apply(QueueEdit.playNext(queue.size, index, picked.toSet())); Notice.show("Playing next"); done()
            }
            BarButton(Icons.Rounded.VerticalAlignBottom, "Move to end", wide, enabled = picked.isNotEmpty()) {
                QueueEdit.apply(QueueEdit.toEnd(queue.size, index, picked.toSet())); Notice.show("Moved to the end"); done()
            }
            BarButton(Icons.Rounded.RemoveCircleOutline, "Remove from queue", wide, enabled = picked.isNotEmpty()) {
                QueueEdit.remove(picked); Notice.show(if (picked.size == 1) "Removed 1 song" else "Removed ${picked.size} songs"); done()
            }
        } else {
            BarButton(Icons.Rounded.PlayArrow, "Play", true, solid = true, enabled = songs.isNotEmpty()) { Player.playAll(songs, 0); done() }
            BarButton(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", wide, enabled = songs.isNotEmpty()) {
                Player.playNext(songs); Notice.show("Playing next"); done()
            }
            BarButton(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", wide, enabled = songs.isNotEmpty()) {
                Player.addToQueue(songs); Notice.show(if (songs.size == 1) "Added to queue" else "Added ${songs.size} songs to the queue"); done()
            }
            if (room != null) BarButton(Icons.Rounded.Groups, "Add to the room", wide, enabled = songs.isNotEmpty()) {
                val taken = songs.count { com.opentune.data.together.Together.suggest(it) }
                Notice.show(if (taken > 0) "Added $taken to the room" else "The room can't play these"); done()
            }
        }
        Box {
            BarButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", wide, enabled = songs.isNotEmpty()) { adding = true }
            AddSongsToPlaylistMenu(songs, adding, onDismiss = { adding = false }, onAdded = done)
        }
        BarButton(if (allLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (allLiked) "Unlike" else "Like", wide, enabled = songs.isNotEmpty()) {
            songs.forEach { Library.setLiked(it, !allLiked) }
            Notice.show(if (allLiked) "Removed ${songs.size} from Liked songs" else if (songs.size == 1) "Liked 1 song" else "Liked ${songs.size} songs")
            done()
        }
        val remover = selection.remover
        if (remover != null && !selection.forQueue) BarButton(Icons.Rounded.RemoveCircleOutline, selection.removeLabel, wide, enabled = songs.isNotEmpty()) {
            remover(songs); Notice.show(if (songs.size == 1) "Removed 1 song" else "Removed ${songs.size} songs"); done()
        }
    }
}

@Composable
private fun BarDivider() {
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(WebInk.divider))
}

/** An icon alone in the bar. */
@Composable
private fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    PageIconButton(icon, label, iconSize = 20.dp, size = 40.dp, onClick = onClick)
}

/**
 * One of the bar's actions: a pill with its icon and, when [labelled], its name; [solid] is the
 * one the bar is for (Play), in the text colour. Without a label the name is still its description.
 */
@Composable
private fun BarButton(icon: ImageVector, label: String, labelled: Boolean, solid: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = (hovered || focused) && enabled
    val ink = MaterialTheme.colorScheme.onSurface
    val page = MaterialTheme.colorScheme.background
    val fill by animateColorAsState(
        when {
            solid -> if (lit) lerp(ink, page, 0.12f) else ink
            lit -> PageInk.hover
            else -> PageInk.hover.copy(alpha = 0f)
        },
        tween(PAGE_HOVER_MS), label = "bar fill",
    )
    val tint = if (solid) page else ink
    Row(
        Modifier.pressScale(source).height(40.dp).clip(CircleShape).background(fill)
            .hoverable(source, enabled)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .semantics { contentDescription = label }
            .padding(horizontal = if (labelled) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        if (labelled) {
            Spacer(Modifier.width(7.dp))
            Text(label, style = WebType.bodyEmph, color = tint, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Adds every song of [songs] to a playlist, as the phone's picker does for a selection: a new
 * one, one of yours here (each shows how many are in it already, and gets only the rest), or,
 * signed in, one of your playlists on YouTube Music. Opens over the element it's in.
 */
@Composable
internal fun AddSongsToPlaylistMenu(songs: List<Song>, open: Boolean, onDismiss: () -> Unit, onAdded: () -> Unit = {}) {
    val playlists by Library.playlists.collectAsState()
    // The account's own playlists, as the Library reads them once a sign-in (AccountLibrary).
    val library by AccountLibrary.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    val online = library.signedIn && songs.any { isYouTubeId(it.videoId) }
    val account = remember(library.playlists, library.loaded) {
        AccountLibrary.shownPlaylists(library.playlists).filter { it.browseId?.let(com.opentune.data.innertube.InnertubeParser::isEditablePlaylist) == true }
            .takeIf { library.loaded || it.isNotEmpty() }
    }
    LaunchedEffect(open, online) { if (open && online) AccountLibrary.refreshIfStale() }
    val ids = remember(songs) { songs.mapTo(HashSet()) { it.videoId } }
    val many = if (songs.size == 1) songs.first().title else "${songs.size} songs"
    if (open) {
        WebMenu(MenuAnchor.BelowEnd, onDismiss = onDismiss, label = "Add $many to a playlist") {
            WebMenuItem("New playlist…", Icons.Rounded.Add) { naming = true }
            if (playlists.isNotEmpty()) {
                WebMenuSeparator()
                WebMenuHeader("Your playlists")
            }
            playlists.forEach { p ->
                val have = p.songs.count { it.id in ids }
                val full = have >= ids.size
                WebMenuItem(p.name, Icons.AutoMirrored.Rounded.QueueMusic, enabled = !full, trailing = if (full) "Added" else if (have > 0) "$have of ${ids.size}" else null) {
                    val there = p.songs.mapTo(HashSet()) { it.id }
                    val fresh = songs.filter { it.videoId !in there }.distinctBy { it.videoId }
                    fresh.forEach { Library.addToPlaylist(p.id, it) }
                    Notice.show(if (fresh.size == 1) "Added to ${p.name}" else "Added ${fresh.size} songs to ${p.name}")
                    onAdded()
                }
            }
            if (online) {
                val mine = account
                WebMenuSeparator()
                WebMenuHeader("On YouTube Music")
                when {
                    mine == null -> WebMenuItem("Loading your playlists…", enabled = false) {}
                    mine.isEmpty() -> WebMenuItem("No playlists to add to", enabled = false) {}
                    else -> mine.forEach { p ->
                        WebMenuItem(p.title, Icons.AutoMirrored.Rounded.PlaylistPlay) {
                            AccountPlaylists.add(p, songs)
                            onAdded()
                        }
                    }
                }
            }
        }
    }
    if (naming) {
        PageNameDialog("New playlist", "", "Create", onDismiss = { naming = false }) { name ->
            val id = Library.createPlaylist(name)
            songs.distinctBy { it.videoId }.forEach { Library.addToPlaylist(id, it) }
            Notice.show(if (songs.size == 1) "Added to $name" else "Added ${songs.size} songs to $name")
            naming = false
            onAdded()
        }
    }
}

/** Several songs at once to one of the account's playlists on YouTube Music, in one edit, off the window's thread. */
internal object AccountPlaylists {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun add(playlist: ShelfItem, songs: List<Song>) {
        val id = playlist.browseId ?: return
        val online = songs.filter { isYouTubeId(it.videoId) }.map { it.videoId }.distinct()
        if (online.isEmpty()) return
        scope.launch {
            runCatching { com.opentune.data.innertube.Innertube.addToPlaylist(id, online) }
                .onSuccess {
                    Pages.forgetListing(id)
                    Notice.show(if (online.size == 1) "Added to ${playlist.title}" else "Added ${online.size} songs to ${playlist.title}")
                }
                .onFailure { Notice.show("Couldn't add them to ${playlist.title}") }
        }
    }
}

/**
 * The queue's selection acted on: the picked songs played next or moved to the end, keeping
 * their order, or taken out. The song playing is never one of them. An order is the new queue
 * as places in the old one.
 */
internal object QueueEdit {
    /** The queue of [size] with [picked] right after [current], in their order. */
    fun playNext(size: Int, current: Int, picked: Set<Int>): List<Int> {
        val moving = (0 until size).filter { it in picked && it != current }
        val rest = (0 until size).filter { it !in moving }
        val at = rest.indexOf(current) + 1
        return rest.take(at) + moving + rest.drop(at)
    }

    /** The queue of [size] with [picked] at its end, in their order. */
    fun toEnd(size: Int, current: Int, picked: Set<Int>): List<Int> {
        val moving = (0 until size).filter { it in picked && it != current }
        return (0 until size).filter { it !in moving } + moving
    }

    /** Puts the queue in [order], one move at a time so the song playing goes on playing. */
    fun apply(order: List<Int>) {
        // Which original song is at each place now, as the moves go.
        val now = order.sorted().toMutableList()
        order.forEachIndexed { target, original ->
            val from = now.indexOf(original)
            if (from != target) {
                Player.move(from, target)
                now.add(target, now.removeAt(from))
            }
        }
    }

    /** Takes the songs at [indices] out, the song playing aside. */
    fun remove(indices: List<Int>) {
        indices.sortedDescending().forEach(Player::removeAt)
    }
}
