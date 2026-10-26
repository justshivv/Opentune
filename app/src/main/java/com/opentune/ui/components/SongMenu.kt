package com.opentune.ui.components

import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.Ringtones
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.isYouTubeId
import com.opentune.data.library.HiddenList
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.PlaylistLinks
import com.opentune.data.library.PlaylistSync
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt

/** What a song's menu can do beyond the library, wired up by the app shell. */
class SongMenuActions(
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    val startRadio: (Song) -> Unit,
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
    /** Marks a song as disliked; the player skips it if it's playing, and drops its queued copies. */
    val dislike: (Song) -> Unit = LibraryStore::dislike,
    /** Opens the song's artist, finding the page when the row didn't say which. */
    val viewArtist: ((Song) -> Unit)? = null,
    /** Opens one of the song's artists by name, when it has several. */
    val viewArtistNamed: ((Song, String) -> Unit)? = null,
    /** Where the song is and how long it runs, when it's the one playing; for the share picture. */
    val progressOf: (Song) -> Pair<Long, Long>? = { null },
    /** "Not interested": keeps the song out of suggestions; the shell offers Undo. */
    val notInterested: (Song) -> Unit = { LibraryStore.hideSong(it) },
    /** "Don't play [artist]": keeps the song's artist out of suggestions. */
    val dontPlayArtist: (Song) -> Unit = { LibraryStore.hideArtist(it) },
    /** Adds a song smart shuffle mixed in to the playlist the queue came from. */
    val keepInPlaylist: (Song, String) -> Unit = { s, id -> LibraryStore.addToPlaylist(id, s); com.opentune.playback.SmartShuffle.keep(s.videoId) },
)

val LocalSongMenu = staticCompositionLocalOf<SongMenuActions?> { null }

private typealias MenuRows = @Composable ColumnScope.(close: () -> Unit) -> Unit

/**
 * The long-press and "…" menu for one song, in the same order everywhere.
 * The player adds its own rows: [top] (Upgrade quality), [tools] (sleep
 * timer, lyrics offset) before Share, and [end] (Copy log) after it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(
    song: Song,
    onDismiss: () -> Unit,
    top: MenuRows? = null,
    tools: MenuRows? = null,
    end: MenuRows? = null,
) {
    val actions = LocalSongMenu.current
    val context = LocalContext.current
    val liked by LibraryStore.liked.collectAsState()
    val downloads by Downloads.entries.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val hidden by LibraryStore.hidden.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val origin by com.opentune.playback.SmartShuffle.origin.collectAsState()
    // A song smart shuffle mixed into one of the app's own playlists can be kept there.
    val keepIn = origin?.playlistId?.takeIf { song.smartShuffle }
        ?.let { id -> playlists.firstOrNull { it.id == id } }
        ?.takeIf { p -> p.songs.none { it.videoId == song.videoId } }
    val download = downloads[song.videoId]
    // Files on this phone and songs on your server: no YouTube actions.
    val local = !isYouTubeId(song.videoId)
    var pickPlaylist by remember { mutableStateOf(false) }
    var ringtone by remember { mutableStateOf(false) }
    val close = onDismiss

    FloatingCard(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        expandable = true,
        // Open fully so the whole menu shows at once; the content lifts to make room, no scrolling needed.
        startExpanded = true,
        // The song and its four quick actions stay put; the rest of the menu scrolls under them.
        header = {
            val card = LocalFloatingCard.current
            val close: () -> Unit = { card?.close() ?: onDismiss() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(56.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // The four things done most often, one tap each.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Liked" else "Like", active = isLiked) {
                    LibraryStore.setLiked(song, !isLiked)
                }
                if (!local) {
                    val (icon, label) = when (download?.state) {
                        DownloadState.DONE -> Icons.Rounded.DeleteOutline to "Remove"
                        DownloadState.QUEUED -> Icons.Rounded.Close to if (Downloads.waitingForWifi(context)) "Needs Wi-Fi" else "Waiting"
                        DownloadState.DOWNLOADING -> Icons.Rounded.Close to "${(download.progress * 100).toInt()}%"
                        DownloadState.FAILED -> Icons.Rounded.Download to "Retry"
                        null -> Icons.Rounded.Download to "Download"
                    }
                    val ring = when (download?.state) {
                        DownloadState.DOWNLOADING -> download.progress
                        DownloadState.QUEUED -> -1f
                        else -> null
                    }
                    QuickAction(icon, label, active = download?.state == DownloadState.DONE, progress = ring) {
                        when (download?.state) {
                            DownloadState.DONE, DownloadState.QUEUED, DownloadState.DOWNLOADING -> Downloads.remove(context, song.videoId)
                            DownloadState.FAILED -> Downloads.retry(context, song.videoId)
                            null -> Downloads.enqueue(context, song)
                        }
                    }
                }
                QuickAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", active = false) { pickPlaylist = true }
                if (!local) {
                    QuickAction(Icons.Rounded.Share, "Share", active = false) {
                        // The link goes with a picture of the song, like a now-playing card.
                        val (at, length) = actions?.progressOf?.invoke(song) ?: (0L to 0L)
                        com.opentune.ui.share.ShareCard.share(context, song, at, length)
                        close()
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        },
    ) {
        // Menu picks play the card out as they act.
        val card = LocalFloatingCard.current
        val close: () -> Unit = { card?.close() ?: onDismiss() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (!local && download != null) {
                val downloading = download.state == DownloadState.QUEUED || download.state == DownloadState.DOWNLOADING
                MenuRow(if (downloading) Icons.Rounded.Close else Icons.Rounded.DeleteOutline,
                    if (downloading) "Cancel download" else "Remove download") {
                    Downloads.remove(context, song.videoId)
                    close()
                }
            }
            if (actions != null) {
                MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { actions.playNext(song); close() }
                MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { actions.addToQueue(song); close() }
                keepIn?.let { p -> MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Keep in playlist") { close(); actions.keepInPlaylist(song, p.id) } }
                if (!local) MenuRow(Icons.Rounded.Radio, "Start radio") { actions.startRadio(song); close() }
                when {
                    actions.viewArtist != null && !local -> MenuRow(Icons.Rounded.Person, "View artist") { close(); actions.viewArtist.invoke(song) }
                    song.artistId != null -> MenuRow(Icons.Rounded.Person, "View artist") { close(); actions.openArtist(song.artistId!!) }
                }
                song.albumId?.let { id -> MenuRow(Icons.Rounded.Album, "Open album") { close(); actions.openAlbum(id) } }
            }
            if (top != null || tools != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                top?.invoke(this, close)
                tools?.invoke(this, close)
            }
            if (download?.state == DownloadState.DONE || (local && Ringtones.canUse(song))) {
                MenuRow(Icons.Rounded.NotificationsActive, "Set as ringtone") { ringtone = true }
            }
            if (actions != null) MenuRow(Icons.Rounded.ThumbDownOffAlt, "Not for me (dislike)") { close(); actions.dislike(song) }
            // Only suggestions are affected: the song still plays when picked, and stays in playlists.
            if (actions != null && !com.opentune.data.radio.Radio.isRadio(song.videoId)) {
                if (hidden.hidesSong(song.videoId)) {
                    MenuRow(Icons.Rounded.Visibility, "Suggest this song again") { LibraryStore.unhideSong(song.videoId); close() }
                } else {
                    MenuRow(Icons.Rounded.VisibilityOff, "Not interested") { close(); actions.notInterested(song) }
                }
                val artist = HiddenList.mainArtist(song.artist)
                if (artist.isNotBlank()) {
                    val entry = hidden.artists.firstOrNull { it.key == HiddenList.normalize(artist) }
                    if (entry != null) {
                        MenuRow(Icons.Rounded.Person, "Suggest $artist again") { LibraryStore.unhideArtist(entry.key); close() }
                    } else {
                        MenuRow(Icons.Rounded.PersonOff, "Don't play $artist") { close(); actions.dontPlayArtist(song) }
                    }
                }
            }
            end?.invoke(this, close)
            Spacer(Modifier.size(12.dp))
        }
    }
    if (ringtone) RingtoneDialog(song, onDismiss = { ringtone = false; close() })
    if (pickPlaylist) AddToPlaylistDialog(song, onDone = { pickPlaylist = false; close() }, onDismiss = { pickPlaylist = false })
}

/** A round quick action with its label underneath; filled when [active]. */
@Composable
/**
 * One of the menu's big buttons. With [progress] (0..1) a ring fills round
 * its edge from the top, as a download goes; below 0 it's waiting, and a
 * short stretch of the ring runs round instead.
 */
private fun QuickAction(icon: ImageVector, label: String, active: Boolean, progress: Float? = null, onClick: () -> Unit) {
    val ringColour = MaterialTheme.colorScheme.primary
    val trackColour = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val shown by androidx.compose.animation.core.animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), androidx.compose.animation.core.tween(300), label = "downloadRing")
    val waiting = progress != null && progress < 0f
    val spin = if (waiting) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "downloadWait")
            .animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_200, easing = androidx.compose.animation.core.LinearEasing)), label = "downloadWaitSpin")
    } else null
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp)
                .then(if (progress == null) Modifier else Modifier.drawWithCache {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    // The button's own outline, starting at the top middle and going clockwise.
                    val corner = 18.dp.toPx() - inset
                    val outline = androidx.compose.ui.graphics.Path().apply {
                        val w = size.width - inset
                        val h = size.height - inset
                        moveTo(size.width / 2, inset)
                        lineTo(w - corner, inset)
                        arcTo(androidx.compose.ui.geometry.Rect(w - 2 * corner, inset, w, inset + 2 * corner), -90f, 90f, false)
                        lineTo(w, h - corner)
                        arcTo(androidx.compose.ui.geometry.Rect(w - 2 * corner, h - 2 * corner, w, h), 0f, 90f, false)
                        lineTo(inset + corner, h)
                        arcTo(androidx.compose.ui.geometry.Rect(inset, h - 2 * corner, inset + 2 * corner, h), 90f, 90f, false)
                        lineTo(inset, inset + corner)
                        arcTo(androidx.compose.ui.geometry.Rect(inset, inset, inset + 2 * corner, inset + 2 * corner), 180f, 90f, false)
                        close()
                    }
                    val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(outline, false) }
                    val length = measure.length
                    val piece = androidx.compose.ui.graphics.Path()
                    val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    onDrawWithContent {
                        drawContent()
                        drawPath(outline, trackColour, style = style)
                        piece.reset()
                        val s = spin?.value
                        if (s != null) {
                            val from = s * length
                            val to = from + length * 0.22f
                            if (to <= length) measure.getSegment(from, to, piece, true)
                            else { measure.getSegment(from, length, piece, true); measure.getSegment(0f, to - length, piece, true) }
                        } else if (shown > 0f) {
                            measure.getSegment(0f, shown * length, piece, true)
                        }
                        drawPath(piece, ringColour, style = style)
                    }
                })
                .clip(RoundedCornerShape(18.dp))
                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
        Text(com.opentune.ui.theme.tr(label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(com.opentune.ui.theme.tr(label), style = MaterialTheme.typography.bodyLarge)
    }
}

/** One playlist to pick in [AddToPlaylistDialog]: its cover, its name, how many songs, and a tick if the song is in it. */
@Composable
private fun PlaylistPickRow(
    title: String,
    subtitle: String,
    art: @Composable () -> Unit,
    added: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))) { art() }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(com.opentune.ui.theme.tr(title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (added) Icon(Icons.Rounded.CheckCircle, "Already in this playlist", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
}

/**
 * Picks one of the playlists made in the app, or makes a new one with [song]
 * in it. Signed in, the account's own YouTube Music playlists are listed too,
 * and a song picked for one of those goes straight there; one linked to a
 * playlist here is listed once, as that one.
 */
@Composable
fun AddToPlaylistDialog(song: Song, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val playlists by LibraryStore.playlists.collectAsState()
    val signedIn by com.opentune.data.account.AccountStore.signedIn.collectAsState()
    val account by com.opentune.data.library.LibraryCache.playlists.collectAsState()
    val owned by com.opentune.data.library.LibraryCache.owned.collectAsState()
    // A file on this phone can't go on YouTube Music.
    val online = signedIn && isYouTubeId(song.videoId)
    val accountPlaylists = if (online) remember(account, owned, playlists) { PlaylistLinks.pickable(account, owned, playlists) } else emptyList()
    // Which of the account's playlists already have the song, looked up as the sheet opens.
    val inAccount = remember(song.videoId) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(accountPlaylists, song.videoId) {
        val gate = kotlinx.coroutines.sync.Semaphore(ACCOUNT_LOOKUPS)
        kotlinx.coroutines.coroutineScope {
            accountPlaylists.take(MAX_ACCOUNT_CHECKED).filter { it.playlistId !in inAccount }.forEach { p ->
                launch {
                    gate.acquire()
                    try {
                        runCatching { com.opentune.data.MusicRepository.collection(p.browseId) }.getOrNull()
                            ?.let { c -> inAccount[p.playlistId] = c.songs.any { it.videoId == song.videoId } }
                    } finally {
                        gate.release()
                    }
                }
            }
        }
    }
    var naming by remember { mutableStateOf(playlists.isEmpty() && accountPlaylists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var saveOnline by remember { mutableStateOf(true) }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (naming) "New playlist" else "Add to playlist") },
        text = {
            if (naming) {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") })
                    if (online) AccountCopyOptions(saveOnline, { saveOnline = it }, privacy, { privacy = it }, Modifier.padding(top = 12.dp))
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        PlaylistPickRow(
                            title = "New playlist",
                            subtitle = "With this song in it",
                            art = {
                                Box(
                                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
                            },
                            onClick = { naming = true },
                        )
                    }
                    items(playlists, key = { it.id }) { p ->
                        val has = p.songs.any { it.videoId == song.videoId }
                        PlaylistPickRow(
                            title = p.name,
                            subtitle = if (has) "Already in it" else if (p.songs.size == 1) "1 song" else "${p.songs.size} songs",
                            art = { PlaylistCover(p.name, p.songs.map { it.thumbnailUrl }, Modifier.fillMaxSize(), RectangleShape) },
                            added = has,
                            onClick = {
                                if (has) {
                                    com.opentune.data.Pills.info("Already in ${p.name}", context)
                                } else {
                                    LibraryStore.addToPlaylist(p.id, song)
                                    com.opentune.data.Pills.done("Added to ${p.name}", context)
                                }
                                onDone()
                            },
                        )
                    }
                    if (accountPlaylists.isNotEmpty()) {
                        item {
                            Text(
                                "On YouTube Music",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(accountPlaylists, key = { "yt:${it.playlistId}" }) { p ->
                            val has = inAccount[p.playlistId] == true
                            PlaylistPickRow(
                                title = p.title,
                                subtitle = if (has) "Already in it" else p.subtitle.ifBlank { "YouTube Music" },
                                art = { Artwork(p.thumbnailUrl, Modifier.fillMaxSize(), RectangleShape, placeholder = Icons.AutoMirrored.Rounded.QueueMusic) },
                                added = has,
                            ) {
                                if (has) {
                                    com.opentune.data.Pills.info("Already in ${p.title}", context)
                                    onDone()
                                    return@PlaylistPickRow
                                }
                                PlaylistSync.addToAccountPlaylist(p.playlistId, song) { ok ->
                                    if (ok) com.opentune.data.Pills.done("Added to ${p.title}", context)
                                    else com.opentune.data.Pills.error("Couldn't add it to ${p.title}", context)
                                }
                                onDone()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (naming) {
                SheetButton("Create", onClick = {
                    LibraryStore.createPlaylist(name, song, privacy.takeIf { online && saveOnline })
                    com.opentune.data.Pills.done("Added to ${name.trim().ifEmpty { "New playlist" }}", context)
                    onDone()
                })
            }
        },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}

/** Account playlists looked up at once, and at most, to mark the ones that already have the song. */
private const val ACCOUNT_LOOKUPS = 4
private const val MAX_ACCOUNT_CHECKED = 30
