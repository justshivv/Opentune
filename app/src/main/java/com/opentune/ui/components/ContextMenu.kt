package com.opentune.ui.components

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import kotlin.math.roundToInt
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PushPin
import com.opentune.data.model.durationMillis
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.isYouTubeId
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.BrowseType
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where a long-pressed item sits on the screen, in px, kept up to date as it
 * lays out, so its menu can open from right there.
 */
class MenuAnchor {
    internal var rect: Rect? = null
}

/** Reports this element's place on the screen to [anchor]. */
fun Modifier.menuAnchor(anchor: MenuAnchor, view: View): Modifier = onGloballyPositioned { c ->
    val at = IntArray(2)
    view.rootView.getLocationOnScreen(at)
    anchor.rect = c.boundsInWindow().translate(at[0].toFloat(), at[1].toFloat())
}

/** One row of a context menu. Rows in [group] 0 sit in the lighter top section. */
class MenuEntry(val label: String, val icon: ImageVector, val group: Int = 1, val onClick: () -> Unit)

/**
 * A long-press menu as BitChord and iOS have it: everything behind blurs, the
 * pressed item lifts out where it was as a card of its own, and the menu opens
 * as a second card right under it (over it when there's no room below). When
 * neither fits, the item slides up just enough for the menu to show whole.
 * Closing plays it all back to where it was. A row runs its action as the
 * menu goes.
 */
@Composable
fun ContextMenu(anchor: Rect, onDismiss: () -> Unit, entries: List<MenuEntry>, preview: @Composable () -> Unit) {
    val ui by AppSettings.ui.collectAsState()
    val still = ui.reduceAnimation
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { open = true }
    val shown by animateFloatAsState(
        if (open) 1f else 0f,
        if (still) tween(0) else if (open) spring(dampingRatio = 0.78f, stiffness = 420f) else tween(CLOSE_MS.toInt()),
        label = "contextMenu",
    )
    fun close(then: (() -> Unit)? = null) {
        if (leaving) return
        leaving = true
        open = false
        scope.launch {
            delay(if (still) 0 else CLOSE_MS)
            then?.invoke()
            onDismiss()
        }
    }
    // Where the menu was opened from: in a sheet, the frost behind keeps the sheet in sight.
    val opener = LocalView.current
    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The screen behind frosted by the app itself; the window's blur only where that can't be done.
        var frost by remember { mutableStateOf(if (ui.reduceBlur) Frost.FAILED else Frost.PENDING) }
        FrostBehind(enabled = !ui.reduceBlur, standIn = frost != Frost.FAILED)
        if (frost != Frost.FAILED) {
            val got = ScreenFrost(shown = { shown }, veil = 0.62f, over = opener)
            LaunchedEffect(got) { frost = got }
        }
        val view = LocalView.current
        val density = LocalDensity.current
        // Where this dialog's own window sits on the screen, to place the item where it was.
        var origin by remember { mutableStateOf<Offset?>(null) }
        val insets = WindowInsets.safeDrawing
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { c ->
                        val at = IntArray(2)
                        view.rootView.getLocationOnScreen(at)
                        origin = c.positionInWindow() + Offset(at[0].toFloat(), at[1].toFloat())
                    }
                    .pointerInput(Unit) { detectTapGestures { close() } },
            ) {
                val o = origin ?: return@Box
                val item = anchor.translate(-o.x, -o.y)
                Layout(
                    content = {
                        // The item, lifted out as a card of its own. Its card (rounding,
                        // shadow, fill, edge) comes and goes with the lift, so on the
                        // way back it lands as the plain row it came from.
                        val fill = MaterialTheme.colorScheme.surfaceContainerLow
                        val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        Box(
                            Modifier
                                .graphicsLayer {
                                    val k = shown.coerceIn(0f, 1f)
                                    shape = RoundedCornerShape(26.dp * k)
                                    clip = true
                                    shadowElevation = 18.dp.toPx() * k
                                }
                                .drawBehind {
                                    val k = shown.coerceIn(0f, 1f)
                                    drawRect(fill.copy(alpha = fill.alpha * k))
                                }
                                .drawWithContent {
                                    drawContent()
                                    val k = shown.coerceIn(0f, 1f)
                                    val r = 26.dp.toPx() * k
                                    drawRoundRect(
                                        edge.copy(alpha = edge.alpha * k),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                                    )
                                }
                                .pointerInput(Unit) { detectTapGestures { } },
                        ) { preview() }
                        MenuCard(entries) { entry -> close(entry.onClick) }
                    },
                ) { measurables, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val margin = 16.dp.roundToPx()
                    val gap = 12.dp.roundToPx()
                    val top = insets.getTop(this) + 8.dp.roundToPx()
                    val bottom = h - insets.getBottom(this) - 12.dp.roundToPx()
                    // A full-width row lifts out as a card inside the side margins, not edge to edge.
                    val itemX = item.left.toInt().coerceAtLeast(margin)
                    val itemW = (minOf(item.right.toInt(), w - margin) - itemX).coerceIn(1, w)
                    val itemH = item.height.toInt().coerceIn(1, h)
                    // Drawn into the margins as it lifts and back out as it lands, so the
                    // last frame matches the row in the list exactly.
                    val k = shown.coerceIn(0f, 1f)
                    val liftX = (item.left + (itemX - item.left) * k).roundToInt()
                    val liftW = (item.width + (itemW - item.width) * k).roundToInt().coerceIn(1, w)
                    val itemPlaceable = measurables[0].measure(Constraints.fixed(liftW, itemH))
                    val menuW = minOf((w * 0.7f).toInt().coerceAtLeast(260.dp.roundToPx()), w - margin * 2)
                    val roomForMenu = (bottom - top - itemH - gap).coerceAtLeast(200.dp.roundToPx())
                    val menuPlaceable = measurables[1].measure(Constraints(minWidth = menuW, maxWidth = menuW, maxHeight = roomForMenu))
                    val menuH = menuPlaceable.height

                    val startY = item.top.toInt()
                    val below = startY + itemH + gap + menuH <= bottom
                    val above = startY - gap - menuH >= top
                    // Where the item settles: in place when the menu fits beside it, else slid just enough.
                    val itemY = when {
                        below || above -> startY.coerceIn(top, (bottom - itemH).coerceAtLeast(top))
                        else -> (bottom - menuH - gap - itemH).coerceAtLeast(top)
                    }
                    val menuY = if (!below && above) itemY - gap - menuH else itemY + itemH + gap
                    val menuX = (if (item.center.x > w / 2f) item.right.toInt() - menuW else item.left.toInt())
                        .coerceIn(margin, (w - margin - menuW).coerceAtLeast(margin))
                    val menuOnTop = !below && above
                    layout(w, h) {
                        // The slide is a float translation, not whole-pixel steps.
                        val slide = (startY - itemY) * (1f - shown)
                        itemPlaceable.placeWithLayer(liftX, itemY) {
                            translationY = slide
                            val lift = 1f + 0.025f * shown
                            scaleX = lift
                            scaleY = lift
                        }
                        // Moves with the item while it slides, and grows out of the edge it hangs from.
                        menuPlaceable.placeWithLayer(menuX, menuY) {
                            translationY = slide
                            alpha = shown.coerceIn(0f, 1f)
                            val s = 0.86f + 0.14f * shown
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(
                                if (item.center.x > w / 2f) 1f else 0f,
                                if (menuOnTop) 1f else 0f,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val CLOSE_MS = 190L

/** The menu's card: the top group on a lighter band, a thick rule, then the rest. Scrolls if it must. */
@Composable
private fun MenuCard(entries: List<MenuEntry>, onPick: (MenuEntry) -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .shadow(18.dp, shape)
            .clip(shape)
            .background(c.surfaceContainerLow, shape)
            .border(1.dp, c.onSurface.copy(alpha = 0.08f), shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState()),
    ) {
        val (first, rest) = entries.partition { it.group == 0 }
        if (first.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(c.onSurface.copy(alpha = 0.045f))) {
                first.forEach { MenuLine(it, onPick) }
            }
            Box(Modifier.fillMaxWidth().height(7.dp).background(c.onSurface.copy(alpha = 0.09f)))
        }
        rest.forEach { MenuLine(it, onPick) }
    }
}

@Composable
private fun MenuLine(entry: MenuEntry, onPick: (MenuEntry) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(entry.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Icon(entry.icon, null, Modifier.size(24.dp))
    }
}

/**
 * A song's long-press menu: like, dislike and add to a playlist on top; then
 * download, radio, play next, queue, its album and artist, share, and the
 * full menu for everything else.
 */
@Composable
fun SongContextMenu(
    song: Song,
    anchor: Rect,
    onDismiss: () -> Unit,
    /** Inside one of your own playlists: takes this song out of it. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    preview: @Composable () -> Unit,
) {
    val actions = LocalSongMenu.current
    val context = LocalContext.current
    val liked by LibraryStore.liked.collectAsState()
    val downloads by Downloads.entries.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val download = downloads[song.videoId]
    val local = !isYouTubeId(song.videoId)
    // What follows the menu once it's gone: a playlist picker, or the full menu.
    var next by remember { mutableStateOf<String?>(null) }
    when (next) {
        "playlist" -> { AddToPlaylistDialog(song, onDone = { next = null; onDismiss() }, onDismiss = { next = null; onDismiss() }); return }
        "more" -> { SongMenuSheet(song, onDismiss = { next = null; onDismiss() }); return }
        "info" -> { com.opentune.ui.player.TrackDetailsDialog(song, song.durationMillis(), onDismiss = { next = null; onDismiss() }); return }
    }
    // Kept audio, separate from a download: the song cache that makes replays instant.
    val cached = remember(song.videoId) { !local && com.opentune.playback.AudioCache.holds(context, song.videoId) }
    val entries = buildList {
        add(MenuEntry(if (isLiked) "Remove from Liked Music" else "Like", if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, 0) { LibraryStore.setLiked(song, !isLiked) })
        if (actions != null) add(MenuEntry("Dislike", Icons.Rounded.ThumbDownOffAlt, 0) { actions.dislike(song) })
        add(MenuEntry("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, 0) { next = "playlist" })
        onRemoveFromPlaylist?.let { add(MenuEntry("Remove from this playlist", Icons.Rounded.RemoveCircleOutline, 0) { it() }) }
        if (!local) add(
            when (download?.state) {
                DownloadState.DONE -> MenuEntry("Remove download", Icons.Rounded.DeleteOutline) { Downloads.remove(context, song.videoId) }
                DownloadState.QUEUED, DownloadState.DOWNLOADING -> MenuEntry("Cancel download", Icons.Rounded.DeleteOutline) { Downloads.remove(context, song.videoId) }
                DownloadState.FAILED -> MenuEntry("Retry download", Icons.Rounded.Download) { Downloads.retry(context, song.videoId) }
                null -> MenuEntry("Download", Icons.Rounded.Download) { Downloads.enqueue(context, song) }
            },
        )
        if (cached && download?.state != DownloadState.DONE) add(MenuEntry("Remove from cache", Icons.Rounded.DeleteSweep) {
            com.opentune.playback.AudioCache.remove(context, song.videoId)
        })
        if (actions != null) {
            if (!local) add(MenuEntry("Start radio", Icons.Rounded.Radio) { actions.startRadio(song) })
            add(MenuEntry("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { actions.playNext(song) })
            add(MenuEntry("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { actions.addToQueue(song) })
            song.albumId?.let { id -> add(MenuEntry("Open album", Icons.Rounded.Album) { actions.openAlbum(id) }) }
            when {
                actions.viewArtist != null && !local -> add(MenuEntry("Open artist", Icons.Rounded.Person) { actions.viewArtist.invoke(song) })
                song.artistId != null -> add(MenuEntry("Open artist", Icons.Rounded.Person) { actions.openArtist(song.artistId!!) })
            }
        }
        add(MenuEntry("Song info", Icons.Rounded.Info) { next = "info" })
        if (!local) add(MenuEntry("Share", Icons.Rounded.Share) {
            val (at, length) = actions?.progressOf?.invoke(song) ?: (0L to 0L)
            com.opentune.ui.share.ShareCard.share(context, song, at, length)
        })
        add(MenuEntry("More options", Icons.Rounded.MoreHoriz) { next = "more" })
    }
    ContextMenu(anchor, onDismiss = { if (next == null) onDismiss() }, entries = entries, preview = preview)
}

/** What a long-press on an album or playlist can do; built by the app shell. */
class CollectionMenuActions(
    val play: (browseId: String, title: String, shuffle: Boolean) -> Unit,
    val playNext: (browseId: String) -> Unit,
    val addToQueue: (browseId: String) -> Unit,
    val downloadAll: (browseId: String) -> Unit,
    val open: (browseId: String, type: BrowseType) -> Unit,
)

val LocalCollectionMenu = staticCompositionLocalOf<CollectionMenuActions?> { null }

/** An album or playlist to open a long-press menu for. */
class MenuList(val browseId: String, val title: String, val subtitle: String, val thumbnailUrl: String?, val type: BrowseType)

/** An album's or playlist's long-press menu: play, shuffle, queue, download, open, share, save. */
@Composable
fun CollectionContextMenu(list: MenuList, anchor: Rect, onDismiss: () -> Unit, preview: @Composable () -> Unit) {
    val actions = LocalCollectionMenu.current
    val context = LocalContext.current
    val saved by LibraryStore.savedLists.collectAsState()
    val isSaved = saved.any { it.browseId == list.browseId }
    val kind = if (list.type == BrowseType.ALBUM) "album" else "playlist"
    val entries = buildList {
        if (actions != null) {
            add(MenuEntry("Play", Icons.Rounded.PlayArrow) { actions.play(list.browseId, list.title, false) })
            add(MenuEntry("Shuffle", Icons.Rounded.Shuffle) { actions.play(list.browseId, list.title, true) })
            add(MenuEntry("Play next", Icons.AutoMirrored.Rounded.PlaylistPlay) { actions.playNext(list.browseId) })
            add(MenuEntry("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { actions.addToQueue(list.browseId) })
            add(MenuEntry("Download all", Icons.Rounded.Download) { actions.downloadAll(list.browseId) })
            add(MenuEntry("Open $kind", Icons.AutoMirrored.Rounded.KeyboardArrowRight) { actions.open(list.browseId, list.type) })
        }
        add(MenuEntry("Share", Icons.Rounded.Share) { com.opentune.ui.share.ListShare.shareList(context, list.title, list.browseId, listOf(list.thumbnailUrl), list.subtitle) })
        add(
            MenuEntry(if (isSaved) "Unpin" else "Pin", if (isSaved) Icons.Rounded.BookmarkRemove else Icons.Rounded.PushPin) {
                LibraryStore.setSaved(com.opentune.data.library.SavedList(list.browseId, list.title, list.subtitle, list.thumbnailUrl), !isSaved)
            },
        )
    }
    ContextMenu(anchor, onDismiss, entries, preview)
}
