package com.opentune.ui.components

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import com.opentune.ui.theme.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.opentune.ui.theme.Text
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
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

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
    /** Opens the song's album, finding the page when the row didn't say which. */
    val viewAlbum: ((Song) -> Unit)? = null,
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
    /** Shown as a page of its own straight away (the player's "…"), instead of a card rising from the bottom. */
    asPage: Boolean = false,
    /** Whether the card opens already at its full height, or at half height to be pulled up. */
    startExpanded: Boolean = false,
    /** How much of the screen the card takes pulled all the way up. */
    fullFraction: Float = 1f,
    /** Whether the card's expansion stops at the height its content needs. */
    fitContent: Boolean = true,
) {
    val actions = LocalSongMenu.current
    // On a playlist or album page: a song downloaded from here is kept under that list in Downloads.
    val fromList = com.opentune.ui.library.LocalDownloadList.current
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
        ?.takeIf { p -> p.spotifyId == null && p.songs.none { it.videoId == song.videoId } }
    val download = downloads[song.videoId]
    // Lite three-dot menu (Settings): the menu is a plain page, there at once, instead of a rising card.
    val menuAsPage = asPage || com.opentune.data.settings.AppSettings.ui.collectAsState().value.liteThreeDotMenu
    // Files on this phone and songs on your server: no YouTube actions.
    val local = !isYouTubeId(song.videoId)
    var pickPlaylist by remember { mutableStateOf(false) }
    var ringtone by remember { mutableStateOf(false) }
    val close = onDismiss
    // The quick actions wear the song's cover, as its song sheet does: one family of tonal
    // circles from the cover's colour, its clearest colour for what's on.
    val tint = com.opentune.ui.player.rememberSheetTint(song.thumbnailUrl)

    // The song and its four quick actions stay put; the rest of the menu scrolls under them.
    val header: @Composable ColumnScope.() -> Unit = {
        val card = LocalFloatingCard.current
        val close: () -> Unit = { card?.close() ?: onDismiss() }
        // The page shows the song larger itself. The Poster layout centres it: the cover large,
        // the title under it, who it's by in spaced capitals.
        if (!menuAsPage && posterLayout()) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val frame = RoundedCornerShape(18.dp)
            Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX * 3), Modifier.size(132.dp)
                .shadow(18.dp, frame, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f)), shape = frame)
            Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        else if (!menuAsPage) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(56.dp))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // The four things done most often, one tap each.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuickAction(if (isLiked) ImageVector.vectorResource(AppIcons.Favorite) else ImageVector.vectorResource(AppIcons.FavoriteBorder), if (isLiked) "Liked" else "Like", active = isLiked, tint = tint) {
                LibraryStore.setLiked(song, !isLiked)
            }
            if (!local) {
                val (icon, label) = when (download?.state) {
                    DownloadState.DONE -> ImageVector.vectorResource(AppIcons.DeleteOutline) to "Remove"
                    DownloadState.QUEUED -> ImageVector.vectorResource(AppIcons.Close) to if (Downloads.waitingForWifi(context)) "Needs Wi-Fi" else "Waiting"
                    DownloadState.DOWNLOADING -> ImageVector.vectorResource(AppIcons.Close) to "${(download.progress * 100).toInt()}%"
                    DownloadState.FAILED -> ImageVector.vectorResource(AppIcons.Download) to "Retry"
                    null -> ImageVector.vectorResource(AppIcons.Download) to "Download"
                }
                val ring = when (download?.state) {
                    DownloadState.DOWNLOADING -> download.progress
                    DownloadState.QUEUED -> -1f
                    else -> null
                }
                QuickAction(icon, label, active = download?.state == DownloadState.DONE, tint = tint, progress = ring) {
                    when (download?.state) {
                        DownloadState.DONE, DownloadState.QUEUED, DownloadState.DOWNLOADING -> Downloads.remove(context, song.videoId)
                        DownloadState.FAILED -> Downloads.retry(context, song.videoId)
                        null -> { Downloads.enqueue(context, song); fromList?.keepPicked(listOf(song)) }
                    }
                }
            }
            QuickAction(ImageVector.vectorResource(AppIcons.PlaylistAdd), "Playlist", active = false, tint = tint) { pickPlaylist = true }
            if (!local) {
                QuickAction(ImageVector.vectorResource(AppIcons.Share), "Share", active = false, tint = tint) {
                    // The link goes with a picture of the song, like a now-playing card.
                    val (at, length) = actions?.progressOf?.invoke(song) ?: (0L to 0L)
                    com.opentune.ui.share.ShareCard.share(context, song, at, length)
                    close()
                }
            }
        }
        MenuGroupBreak()
    }
    val body: @Composable ColumnScope.() -> Unit = {
        // Menu picks play the card out as they act.
        val card = LocalFloatingCard.current
        val close: () -> Unit = { card?.close() ?: onDismiss() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (!local && download != null) {
                val downloading = download.state == DownloadState.QUEUED || download.state == DownloadState.DOWNLOADING
                // What the saved copy is (format, quality, where it came from), or how far its lossless replacement is.
                val upgrade = download.upgrade
                val summary = when {
                    download.state != DownloadState.DONE -> null
                    upgrade != null -> com.opentune.i18n.Tr.f("Downloading again in lossless · {0}%", (upgrade.progress * 100).toInt())
                    else -> listOfNotNull(com.opentune.data.download.DownloadedCopy.describe(download),
                        download.bytes.takeIf { it > 0 }?.let { android.text.format.Formatter.formatShortFileSize(context, it) }).joinToString(" · ")
                }
                MenuRow(if (downloading) ImageVector.vectorResource(AppIcons.Close) else ImageVector.vectorResource(AppIcons.DeleteOutline),
                    if (downloading) "Cancel download" else "Remove download", summary = summary) {
                    Downloads.remove(context, song.videoId)
                    close()
                }
                if (com.opentune.data.download.DownloadedCopy.canRedownloadLossless(download, com.opentune.data.download.DownloadedCopy.addonCanDownload())) {
                    MenuRow(ImageVector.vectorResource(AppIcons.HighQuality), "Download again in lossless") {
                        if (Downloads.downloadAgainLossless(context, song.videoId))
                            com.opentune.data.Pills.info(com.opentune.i18n.Tr.f("Looking for a lossless copy of {0}", song.title), context)
                        close()
                    }
                }
            }
            if (!local) {
                val watches by com.opentune.data.connect.Connect.watches.collectAsState()
                watches.forEach { w ->
                    MenuRow(ImageVector.vectorResource(AppIcons.Watch), com.opentune.ui.connect.SendToWatch.label(w, watches)) {
                        com.opentune.ui.connect.SendToWatch.song(context, w, song)
                        close()
                    }
                }
            }
            if (actions != null) {
                MenuRow(ImageVector.vectorResource(AppIcons.PlaylistPlay), "Play next") { actions.playNext(song); close() }
                MenuRow(ImageVector.vectorResource(AppIcons.QueueMusic), "Add to queue") { actions.addToQueue(song); close() }
                keepIn?.let { p -> MenuRow(ImageVector.vectorResource(AppIcons.PlaylistAdd), "Keep in playlist") { close(); actions.keepInPlaylist(song, p.id) } }
                if (!local) MenuRow(ImageVector.vectorResource(AppIcons.Radio), "Start radio") { actions.startRadio(song); close() }
                when {
                    actions.viewArtist != null && !local -> MenuRow(ImageVector.vectorResource(AppIcons.Person), "View artist") { close(); actions.viewArtist.invoke(song) }
                    song.artistId != null -> MenuRow(ImageVector.vectorResource(AppIcons.Person), "View artist") { close(); actions.openArtist(song.artistId!!) }
                }
                when {
                    song.albumId != null -> MenuRow(ImageVector.vectorResource(AppIcons.Album), "Open album") { close(); actions.openAlbum(song.albumId!!) }
                    actions.viewAlbum != null && !local -> MenuRow(ImageVector.vectorResource(AppIcons.Album), "Open album") { close(); actions.viewAlbum.invoke(song) }
                }
            }
            if (top != null || tools != null) {
                MenuGroupBreak()
                top?.invoke(this, close)
                tools?.invoke(this, close)
            }
            if (download?.state == DownloadState.DONE || (local && Ringtones.canUse(song))) {
                MenuRow(ImageVector.vectorResource(AppIcons.NotificationsActive), "Set as ringtone") { ringtone = true }
            }
            if (actions != null) MenuRow(ImageVector.vectorResource(AppIcons.ThumbDownOffAlt), "Not for me (dislike)") { close(); actions.dislike(song) }
            // Only suggestions are affected: the song still plays when picked, and stays in playlists.
            if (actions != null && !com.opentune.data.radio.Radio.isRadio(song.videoId)) {
                if (hidden.hidesSong(song.videoId)) {
                    MenuRow(ImageVector.vectorResource(AppIcons.Visibility), "Suggest this song again") { LibraryStore.unhideSong(song.videoId); close() }
                } else {
                    MenuRow(ImageVector.vectorResource(AppIcons.VisibilityOff), "Not interested") { close(); actions.notInterested(song) }
                }
                val artist = HiddenList.mainArtist(song.artist)
                if (artist.isNotBlank()) {
                    val entry = hidden.artists.firstOrNull { it.key == HiddenList.normalize(artist) }
                    if (entry != null) {
                        MenuRow(ImageVector.vectorResource(AppIcons.PersonAdd), com.opentune.i18n.Tr.f("Suggest {0} again", artist)) { LibraryStore.unhideArtist(entry.key); close() }
                    } else {
                        MenuRow(ImageVector.vectorResource(AppIcons.PersonOff), com.opentune.i18n.Tr.f("Don't play {0}", artist)) { close(); actions.dontPlayArtist(song) }
                    }
                }
            }
            end?.invoke(this, close)
            Spacer(Modifier.size(12.dp))
        }
    }
    if (menuAsPage) {
        SongMenuPage(song, onDismiss, header, body)
    } else {
        FloatingCard(
            onDismiss = onDismiss,
            contentPadding = PaddingValues(0.dp),
            expandable = true,
            startExpanded = startExpanded,
            fullFraction = fullFraction,
            fitContent = fitContent,
            header = header,
            content = body,
        )
    }
    if (ringtone) RingtoneDialog(song, onDismiss = { ringtone = false; close() })
    if (pickPlaylist) AddToPlaylistDialog(song, onDone = { pickPlaylist = false; close() }, onDismiss = { pickPlaylist = false })
}

/**
 * The song menu as a page of its own over the player: there at once, with no motion, and gone
 * at once. The cover, softly blurred and dimmed, fills the back; the song sits large at the top
 * with a button to close, then the quick actions and the rest of the menu, which scrolls.
 */
@Composable
private fun SongMenuPage(
    song: com.opentune.data.model.Song,
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    // Picks close the page straight away too, then do what they do.
    val card = remember { FloatingCardScope { after -> (after ?: dismiss)() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(view) {
            // No window animation and no dimming: the page is simply there.
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                window.setWindowAnimations(0)
                window.setDimAmount(0f)
            }
            onDispose { }
        }
        val colors = MaterialTheme.colorScheme
        val dark = colors.background.luminance() < 0.5f
        // Solid, whatever the theme's surfaces are, so the player never shows through.
        val base = colors.background.compositeOver(if (dark) Color.Black else Color.White)
        val canBlur = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceBlur
        // Pulled down by its bar (or by the list once it's at its top), the page follows the
        // finger; let go far enough or fast enough and it slides away, else it springs back.
        var drag by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        var pageHeight by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var settle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        fun release(velocity: Float) {
            settle?.cancel()
            settle = scope.launch {
                if (drag > pageHeight * 0.22f || velocity > 1_600f) {
                    androidx.compose.animation.core.animate(drag, pageHeight, velocity, androidx.compose.animation.core.tween(200)) { v, _ -> drag = v }
                    dismiss()
                } else {
                    androidx.compose.animation.core.animate(drag, 0f, velocity, androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 520f)) { v, _ -> drag = v }
                }
            }
        }
        val pullDown = remember {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    // Pushed back up: the page goes back up before the list scrolls.
                    if (drag <= 0f || available.y >= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    val take = maxOf(available.y, -drag)
                    drag += take
                    return androidx.compose.ui.geometry.Offset(0f, take)
                }
                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    // The list at its top and still pulled down: the page comes with it.
                    if (available.y <= 0f || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                    settle?.cancel()
                    drag += available.y
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (drag <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                    release(available.y)
                    return available
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides colors.onSurface,
            LocalFloatingCard provides card,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { pageHeight = it.height.toFloat().coerceAtLeast(1f) }
                    .graphicsLayer { translationY = drag }
                    .background(base)
                    .nestedScroll(pullDown),
            ) {
                // The cover behind, where the phone can blur it; a plain page where it can't.
                if (canBlur && !song.thumbnailUrl.isNullOrBlank()) {
                    Artwork(
                        song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.fillMaxSize().blur(72.dp),
                        shape = RectangleShape,
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to base.copy(alpha = if (dark) 0.62f else 0.66f),
                                0.45f to base.copy(alpha = if (dark) 0.84f else 0.86f),
                                1f to base.copy(alpha = 0.96f),
                            ),
                        ),
                    )
                }
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    // The bar and the song under it pull the page down.
                    val headerDrag = androidx.compose.foundation.gestures.rememberDraggableState { d -> settle?.cancel(); drag = (drag + d).coerceAtLeast(0f) }
                    Column(
                        Modifier.fillMaxWidth().draggable(
                            headerDrag, androidx.compose.foundation.gestures.Orientation.Vertical,
                            onDragStopped = { v -> release(v) },
                        ),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(28.dp)
                                // Tapping the bar closes the page, with no ripple across the strip.
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Close", role = androidx.compose.ui.semantics.Role.Button, onClick = onDismiss,
                                )
                                .semantics { contentDescription = "Close" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(width = 44.dp, height = 5.dp).clip(CircleShape).background(colors.onSurface.copy(alpha = 0.55f)))
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(84.dp), RoundedCornerShape(16.dp))
                            Spacer(Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    song.artist, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                    header()
                    body()
                }
            }
        }
    }
}

/**
 * One of the menu's four big buttons: a round tonal button of the song's [tint], the same for
 * all four, with its name under it. [active] (liked, downloaded) shows in the icon, in the
 * cover's accent, on a button warmed a little by it. With [progress] (0..1) a ring fills round
 * its edge from the top, as a download goes; below 0 it's waiting, and a short stretch of the
 * ring runs round instead.
 */
@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    active: Boolean,
    tint: com.opentune.ui.player.SheetTint,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    val accent = tint.on
    val track = tint.ink.copy(alpha = 0.12f)
    val container = if (active) accent.copy(alpha = if (tint.dark) 0.2f else 0.16f).compositeOver(tint.raised) else tint.raised
    val shown by androidx.compose.animation.core.animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), androidx.compose.animation.core.tween(300), label = "downloadRing")
    val waiting = progress != null && progress < 0f
    val spin = if (waiting) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "downloadWait")
            .animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_200, easing = androidx.compose.animation.core.LinearEasing)), label = "downloadWaitSpin")
    } else null
    Column(
        Modifier.width(80.dp).clip(RoundedCornerShape(18.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(QUICK_ACTION_SIZE).clip(CircleShape).background(container)
                .then(if (progress == null) Modifier else Modifier.drawWithCache {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    onDrawWithContent {
                        drawContent()
                        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = style)
                        // Read here, while drawing: the ring turning redraws the button, never composes it.
                        val s = spin?.value
                        if (s != null) drawArc(accent, -90f + s * 360f, 80f, false, topLeft, arcSize, style = style)
                        else if (shown > 0f) drawArc(accent, -90f, shown * 360f, false, topLeft, arcSize, style = style)
                    }
                }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (active) accent else tint.ink)
        }
        Text(
            com.opentune.ui.theme.tr(label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
            color = if (active) tint.ink else tint.inkSoft, modifier = Modifier.padding(top = 8.dp), maxLines = 1,
        )
    }
}

private val QUICK_ACTION_SIZE = 56.dp

/**
 * Between a menu's groups: a rule across, or in the Poster layout, whose rows each carry their
 * own hairline, a little space, so no second line sits against a row's.
 */
@Composable
fun MenuGroupBreak() {
    if (posterLayout()) androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
    else HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
fun MenuRow(icon: ImageVector, label: String, summary: String? = null, onClick: () -> Unit) {
    // The Poster layout's rows are a little roomier, their icons quieter, with a hairline under
    // each from the text to the edge, as the songs on its pages are.
    val poster = posterLayout()
    val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .then(if (poster) Modifier.drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(hairline, androidx.compose.ui.geometry.Offset(70.dp.toPx(), y), androidx.compose.ui.geometry.Offset(size.width - 24.dp.toPx(), y), 1.dp.toPx())
            } else Modifier)
            .padding(horizontal = 24.dp, vertical = if (summary == null) (if (poster) 17.dp else 15.dp) else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (poster) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.material3.LocalContentColor.current)
        Column {
            Text(com.opentune.ui.theme.tr(label), style = if (poster) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
    val allPlaylists by LibraryStore.playlists.collectAsState()
    val playlists = remember(allPlaylists) { allPlaylists.filter { it.spotifyId == null } }
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
    PlaylistPicker(
        songs = listOf(song),
        local = playlists.map { p ->
            localTarget(p, if (p.songs.any { it.videoId == song.videoId }) 1 else 0,
                add = { LibraryStore.addToPlaylist(p.id, song) },
                remove = { LibraryStore.removeSongsFromPlaylist(p.id, listOf(song.videoId)) })
        },
        account = accountPlaylists.map { p ->
            accountTarget(p, inAccount[p.playlistId]?.let { if (it) 1 else 0 },
                add = {
                    inAccount[p.playlistId] = true
                    PlaylistSync.addToAccountPlaylist(p.playlistId, song) { ok ->
                        if (!ok) { inAccount[p.playlistId] = false; com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Couldn't add it to {0}", p.title), context) }
                    }
                },
                remove = {
                    inAccount[p.playlistId] = false
                    PlaylistSync.removeFromAccountPlaylist(p.playlistId, listOf(song)) { ok ->
                        if (!ok) { inAccount[p.playlistId] = true; com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Couldn't take it out of {0}", p.title), context) }
                    }
                })
        },
        online = online,
        onCreate = { name, privacy ->
            LibraryStore.createPlaylist(name, song, privacy)
            com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Added to {0}", name), context)
            onDone()
        },
        onDone = onDone,
        onDismiss = onDismiss,
    )
}

/** Account playlists looked up at once, and at most, to mark the ones that already have the song. */
private const val ACCOUNT_LOOKUPS = 4
private const val MAX_ACCOUNT_CHECKED = 30
