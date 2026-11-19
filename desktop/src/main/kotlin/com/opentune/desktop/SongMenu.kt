package com.opentune.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.opentune.data.isYouTubeId
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opens a page from anywhere in the window: an artist or album ([ShelfItem]), a playlist, a mood. */
internal val LocalOpen = staticCompositionLocalOf<(Any) -> Unit> { {} }

/** A short line at the bottom of the window saying what just happened: "Playing next", "Link copied". */
internal object Notice {
    private val _shown = MutableStateFlow<Pair<String, Long>?>(null)
    val shown: StateFlow<Pair<String, Long>?> = _shown.asStateFlow()
    fun show(text: String) { _shown.value = text to System.nanoTime() }
    fun clear(which: Pair<String, Long>) { _shown.compareAndSet(which, null) }
}

@Composable
internal fun NoticePill(modifier: Modifier = Modifier) {
    val shown by Notice.shown.collectAsState()
    LaunchedEffect(shown) { shown?.let { delay(2_200); Notice.clear(it) } }
    val last = rememberLast(shown)
    AnimatedVisibility(
        shown != null, modifier,
        enter = fadeIn(Motion.fade(160)) + slideInVertically(Motion.soft()) { it / 2 },
        exit = fadeOut(Motion.fade(200)) + slideOutVertically { it / 3 },
    ) {
        Text(
            last?.first.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.shadow(12.dp, CircleShape).clip(CircleShape)
                .background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * One thing to do with a song, in the right-click menu and the ⋯ menu alike. [group] puts it
 * with its kind (playing, keeping, going to), and the menus draw a hairline between groups.
 */
internal data class SongAction(val label: String, val icon: ImageVector, val group: Int = 0, val run: () -> Unit)

/**
 * What can be done with [song], as on the phone and the web player: play it next or last,
 * a radio from it, like it, add it to a playlist, go to its artist or album, copy its link.
 * [queueIndex] is its place when it's a row of the queue, which adds moving and removing it.
 */
@Composable
internal fun songActions(song: Song, queueIndex: Int? = null, onAddToPlaylist: () -> Unit): List<SongAction> {
    val open = LocalOpen.current
    val openArtist = rememberOpenArtist()
    val openAlbum = rememberOpenAlbum()
    val liked by Library.liked.collectAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val youTube = isYouTubeId(song.videoId)
    val room by com.opentune.data.together.Together.room.collectAsState()
    return buildList {
        // In a room: the song goes to the room's queue, for everyone.
        if (room != null && queueIndex == null) add(SongAction("Add to the room's queue", Icons.Rounded.Groups) {
            if (com.opentune.data.together.Together.suggest(song)) Notice.show("Added to the room")
        })
        if (queueIndex == null) {
            add(SongAction("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { Player.playNext(listOf(song)); Notice.show("Playing next") })
            add(SongAction("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { Player.addToQueue(listOf(song)); Notice.show("Added to queue") })
        } else if (queueIndex != index) {
            add(SongAction("Play", Icons.Rounded.PlayArrow) { Player.playAt(queueIndex) })
            if (queueIndex != index + 1) add(SongAction("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) {
                Player.move(queueIndex, if (queueIndex < index) index else index + 1)
            })
            if (queueIndex > 0) add(SongAction("Move up", Icons.Rounded.ArrowUpward) { Player.move(queueIndex, queueIndex - 1) })
            if (queueIndex < queue.lastIndex) add(SongAction("Move down", Icons.Rounded.ArrowDownward) { Player.move(queueIndex, queueIndex + 1) })
            add(SongAction("Remove from queue", Icons.Rounded.Close) { Player.removeAt(queueIndex) })
        }
        if (youTube) add(SongAction("Start radio", Icons.Rounded.Radio, 0) { Player.startRadio(song); Notice.show("Radio from ${song.title}") })
        add(
            if (isLiked) SongAction("Remove from Liked songs", Icons.Rounded.Favorite, 1) { Library.setLiked(song, false) }
            else SongAction("Like", Icons.Rounded.FavoriteBorder, 1) { Library.setLiked(song, true) },
        )
        add(SongAction("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd, 1, onAddToPlaylist))
        // Found first when the song doesn't carry its artist's or album's id (see SongLinks).
        if (song.artistId?.startsWith("UC") == true || (youTube && song.artist.isNotBlank())) {
            add(SongAction("Go to artist", Icons.Rounded.Person, 2) { openArtist(song, null) })
        }
        if (song.albumId != null || youTube) {
            add(SongAction("Go to album", Icons.Rounded.Album, 2) { openAlbum(song) })
        }
        if (youTube) add(SongAction("Copy link", Icons.Rounded.Link, 3) {
            copyText("https://music.youtube.com/watch?v=${song.videoId}")
            Notice.show("Link copied")
        })
    }
}

internal fun copyText(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

/**
 * [content] with [song]'s actions in the web player's menu on a right click, opening at the
 * pointer, or from the keyboard with the menu key or Shift+F10 while the row has focus; and the
 * add-to-playlist menu they can open, in the same place.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SongContextMenu(song: Song, queueIndex: Int? = null, content: @Composable () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf<IntOffset?>(null) }
    var place by remember { mutableStateOf(IntOffset.Zero) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val actions = songActions(song, queueIndex) { adding = true }
    Box(
        Modifier.onSizeChanged { box = it }
            .onPointerEvent(PointerEventType.Press) { e ->
                if (e.buttons.isSecondaryPressed) {
                    val p = e.changes.first().position
                    at = IntOffset(p.x.roundToInt(), p.y.roundToInt())
                    place = at!!
                    e.changes.forEach { it.consume() }
                }
            }
            .onKeyEvent { e ->
                val asked = e.type == KeyEventType.KeyDown && (e.key == Key.Menu || (e.isShiftPressed && e.key == Key.F10))
                if (asked) { at = IntOffset(box.width / 2, box.height / 2); place = at!! }
                asked
            },
    ) {
        content()
        at?.let { point ->
            WebMenu(MenuAnchor.At(point), onDismiss = { at = null }, label = song.title) { SongActionItems(actions) }
        }
        AddToPlaylistMenu(song, adding, at = place) { adding = false }
    }
}

/** A song's actions as menu items, with a hairline between playing, keeping and going to. */
@Composable
private fun SongActionItems(actions: List<SongAction>) {
    actions.forEachIndexed { i, a ->
        if (i > 0 && a.group != actions[i - 1].group) WebMenuSeparator()
        WebMenuItem(a.label, a.icon, onClick = a.run)
    }
}

/** The ⋯ button on a row ([vertical]: ⋮), opening the same actions as a right click, under it at its right edge. */
@Composable
internal fun SongMoreButton(song: Song, queueIndex: Int? = null, size: androidx.compose.ui.unit.Dp = 32.dp, vertical: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val actions = songActions(song, queueIndex) { adding = true }
    Box {
        if (vertical) PageIconButton(Icons.Rounded.MoreVert, "More options for ${song.title}", iconSize = 22.dp, size = size) { open = true }
        else HoverIcon(Icons.Rounded.MoreHoriz, "More options for ${song.title}", size = size, iconSize = 19.dp) { open = true }
        if (open) WebMenu(MenuAnchor.BelowEnd, onDismiss = { open = false }, label = song.title) { SongActionItems(actions) }
        AddToPlaylistMenu(song, adding) { adding = false }
    }
}

/**
 * The queue as it can be changed: the song playing, then what's next, each row dragged by
 * its handle to a new place, or moved, played next or removed from its menu; Clear empties
 * what's next, and AutoPlay keeps the music going when it runs out.
 */
@Composable
internal fun QueueList(queue: List<Song>, index: Int, header: Boolean = true) {
    val ui by AppSettings.playback.collectAsState()
    val playingNow by Player.playing.collectAsState()
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // The queue's own selection, whose bar plays songs next, moves them to the end or takes them out.
    val selection = rememberOwnSelection(forQueue = true)
    Box(Modifier.fillMaxSize()) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSongSelection provides selection) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalPageBottom.current + if (selection.active) 88.dp else 0.dp)) {
        if (header) item(key = "head") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Up next", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val left = (queue.size - index - 1).coerceAtLeast(0)
                    Text(if (left == 1) "1 song after this one" else "$left songs after this one",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("AutoPlay", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Switch(ui.autoplay, onCheckedChange = { AppSettings.setAutoplay(it) })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { Player.clearUpcoming(); Notice.show("Queue cleared") }, enabled = index < queue.lastIndex) { Text("Clear") }
            }
        }
        itemsIndexed(queue, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            val isCurrent = i == index
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            val lifted = dragging == i
            val pick = rememberRowPick(queue, i)
            val picked = pick?.picked == true
            SongContextMenu(song, queueIndex = i) {
                Row(
                    Modifier.zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { if (lifted) { translationY = dragOffset; shadowElevation = 16f } }
                        .padding(horizontal = 8.dp).fillMaxWidth().height(rowHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (picked) keyColour().copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                        .hoverWash(hover, RoundedCornerShape(12.dp))
                        .then(pick?.capture ?: Modifier)
                        .clickable(interactionSource = hover, indication = null) { pick?.click { Player.playAt(i) } ?: Player.playAt(i) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pick != null) PickSlot(pick, song.title, hover)
                    Box(contentAlignment = Alignment.Center) {
                        Cover(song.thumbnailUrl, 48.dp, MaterialTheme.shapes.small)
                        if (isCurrent) Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) { PlayingBars(playingNow) }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" • "),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.graphicsLayer { alpha = if (hovered || lifted) 1f else 0f }, verticalAlignment = Alignment.CenterVertically) {
                        SongMoreButton(song, queueIndex = i, size = 30.dp)
                        if (!isCurrent) HoverIcon(Icons.Rounded.Close, "Remove", size = 30.dp, iconSize = 17.dp) { Player.removeAt(i) }
                    }
                    // Dragged by its handle: let go, and the song takes the place it's over.
                    Icon(
                        Icons.Rounded.DragIndicator, "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp).padding(4.dp).pointerInput(i, queue.size) {
                            detectDragGestures(
                                onDragStart = { dragging = i; dragOffset = 0f },
                                onDragEnd = {
                                    val to = (i + (dragOffset / rowPx).roundToInt()).coerceIn(0, queue.lastIndex)
                                    dragging = -1; dragOffset = 0f
                                    if (to != i) Player.move(i, to)
                                },
                                onDragCancel = { dragging = -1; dragOffset = 0f },
                            ) { change, amount -> change.consume(); dragOffset += amount.y }
                        },
                    )
                }
            }
        }
    }
    }
    SelectionBar(selection, Modifier.align(Alignment.BottomCenter))
    }
}

/** The Up next page: the queue at the width of a page. */
@Composable
internal fun QueuePage(queue: List<Song>, index: Int) {
    Column(Modifier.fillMaxSize().padding(top = LocalPageTop.current)) {
        if (queue.isEmpty()) {
            Message("The queue is empty", "Play something, or add songs to the queue from any song's menu.", icon = Icons.AutoMirrored.Rounded.QueueMusic)
        } else {
            Box(Modifier.padding(horizontal = GUTTER - 16.dp).widthIn(max = 960.dp).fillMaxSize()) { QueueList(queue, index) }
        }
    }
}

/**
 * For a list's own ⋯ menu, a round button beside its Play: everything in it played next, at the
 * end of the queue, or its link copied; then [more], what else the list allows (rename, delete).
 */
@Composable
internal fun ListMoreButton(songs: List<Song>, link: String?, size: androidx.compose.ui.unit.Dp = 62.dp, more: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        PageRoundButton(Icons.Rounded.MoreHoriz, "More options", size = size, enabled = songs.isNotEmpty() || link != null || more != null) { open = true }
        if (open) WebMenu(MenuAnchor.BelowEnd, onDismiss = { open = false }, label = "More options") {
            WebMenuItem("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay, enabled = songs.isNotEmpty()) { Player.playNext(songs); Notice.show("Playing next") }
            WebMenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic, enabled = songs.isNotEmpty()) { Player.addToQueue(songs); Notice.show("Added to queue") }
            if (link != null) {
                WebMenuSeparator()
                WebMenuItem("Copy link", Icons.Rounded.Link) { copyText(link); Notice.show("Link copied") }
            }
            if (more != null) {
                WebMenuSeparator()
                more()
            }
        }
    }
}

/** The mouse wheel over a volume control turns it up and down, as on the web player. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.volumeWheel(): Modifier = this.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll) { e ->
    val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
    Player.setVolume(Player.volume.value - dy * 0.04)
}
