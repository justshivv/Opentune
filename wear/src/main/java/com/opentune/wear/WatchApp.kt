package com.opentune.wear

import android.app.RemoteInput
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import coil3.compose.AsyncImage
import com.opentune.data.account.AccountStore
import com.opentune.data.connect.Connect
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.wear.ui.AccountAsk
import com.opentune.wear.ui.CollectionSongs
import com.opentune.wear.ui.Devices
import com.opentune.wear.ui.Library
import com.opentune.wear.ui.LibraryKind
import com.opentune.wear.ui.LibraryShelf
import com.opentune.wear.ui.Lyrics
import com.opentune.wear.ui.More
import com.opentune.wear.ui.Pair
import com.opentune.wear.ui.Settings
import com.opentune.wear.ui.SleepTimerScreen

internal object Route {
    const val HOME = "home"
    const val PLAYING = "playing"
    const val SEARCH = "search"
    const val LIKED = "liked"
    const val RECENT = "recent"
    const val QUEUE = "queue"
    const val MORE = "more"
    const val DEVICES = "devices"
    const val PAIR = "pair"
    const val SETTINGS = "settings"
    const val LIBRARY = "library"
    const val SHELF = "shelf/{kind}"
    const val COLLECTION = "collection/{id}/{title}"
    const val LYRICS = "lyrics"
    const val SLEEP = "sleep"
    const val DOWNLOADED = "downloaded"

    fun shelf(kind: LibraryKind) = "shelf/${kind.name}"
    fun collection(id: String, title: String) = "collection/${Uri.encode(id)}/${Uri.encode(title)}"
}

/** The watch app: a list of places to start from, and the song that's playing (here, or on the device it controls). */
@Composable
fun WatchApp(player: WatchPlayer) {
    val nav = rememberSwipeDismissableNavController()
    fun play(songs: List<Song>, index: Int) {
        player.playAll(songs, index)
        nav.navigate(Route.PLAYING) { launchSingleTop = true }
    }
    fun open(route: String) = nav.navigate(route) { launchSingleTop = true }
    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = Route.HOME) {
                composable(Route.HOME) { Home(player, onOpen = ::open) }
                composable(Route.PLAYING) { NowPlaying(player, onOpen = ::open) }
                composable(Route.SEARCH) { Search(player, ::play) }
                composable(Route.LIKED) {
                    val liked by LibraryStore.liked.collectAsState()
                    SongList("Liked songs", liked.map { it.toSong() }, empty = "Tap the heart while a song plays to keep it here.", ::play)
                }
                composable(Route.RECENT) {
                    val records by History.records.collectAsState()
                    SongList("Recent", remember(records) { History.recents(records) }, empty = "Nothing played on the watch yet.", ::play)
                }
                composable(Route.QUEUE) {
                    val (queue, current) = player.upNext.collectAsState().value
                    SongList("Up next", queue, empty = "The queue is empty.", { _, i -> player.playQueueItem(i); nav.popBackStack() }, current = current)
                }
                composable(Route.MORE) { More(player, onOpen = ::open) }
                composable(Route.DEVICES) { Devices(onPair = { open(Route.PAIR) }, onDone = { nav.popBackStack() }) }
                composable(Route.PAIR) { Pair(onDone = { nav.popBackStack() }) }
                composable(Route.SETTINGS) { Settings(onPair = { open(Route.PAIR) }) }
                composable(Route.LIBRARY) { Library(onOpen = { open(Route.shelf(it)) }) }
                composable(Route.SHELF, arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
                    val kind = runCatching { LibraryKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(LibraryKind.PLAYLISTS)
                    LibraryShelf(kind, onOpen = { id, title -> open(Route.collection(id, title)) })
                }
                composable(
                    Route.COLLECTION,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("title") { type = NavType.StringType }),
                ) { entry ->
                    CollectionSongs(entry.arguments?.getString("id").orEmpty(), entry.arguments?.getString("title").orEmpty(), ::play)
                }
                composable(Route.LYRICS) { Lyrics(player) }
                composable(Route.SLEEP) { SleepTimerScreen(onDone = { nav.popBackStack() }) }
                composable(Route.DOWNLOADED) { com.opentune.wear.ui.Downloaded(::play) }
            }
        }
    }
}

@Composable
private fun Home(player: WatchPlayer, onOpen: (String) -> Unit) {
    val shown by player.shown.collectAsState()
    val group by Connect.group.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val answer by Connect.accountSync.collectAsState()
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("OpenTune") } }
            if (signedIn && answer == null) item { AccountAsk() }
            shown?.let { s ->
                item {
                    Button(
                        onClick = { onOpen(Route.PLAYING) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(Icons.Rounded.MusicNote, null) },
                        secondaryLabel = { Text(s.on?.let { "On $it" } ?: s.song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        label = { Text(s.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            item { Place("Search", Icons.Rounded.Search) { onOpen(Route.SEARCH) } }
            item { Place("Liked songs", Icons.Rounded.Favorite) { onOpen(Route.LIKED) } }
            item { Place("Recent", Icons.Rounded.History) { onOpen(Route.RECENT) } }
            item {
                val downloads by com.opentune.data.download.Downloads.done.collectAsState(initial = emptyList())
                Place("Downloaded", Icons.Rounded.DownloadDone, secondary = if (downloads.isEmpty()) null else "${downloads.size} on the watch") { onOpen(Route.DOWNLOADED) }
            }
            if (signedIn) item { Place("Library", Icons.Rounded.LibraryMusic) { onOpen(Route.LIBRARY) } }
            if (group == null) item { Place("Pair with phone", Icons.Rounded.QrCode2) { onOpen(Route.PAIR) } }
            else item { Place("Devices", Icons.Rounded.Devices) { onOpen(Route.DEVICES) } }
            item { Place("Settings", Icons.Rounded.Settings) { onOpen(Route.SETTINGS) } }
        }
    }
}

@Composable
internal fun Place(label: String, icon: ImageVector, secondary: String? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(icon, null) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

/** The cover behind the controls; the crown turns the volume, here or on the device being controlled. */
@Composable
private fun NowPlaying(player: WatchPlayer, onOpen: (String) -> Unit) {
    val shown by player.shown.collectAsState()
    val buffering by player.isBuffering.collectAsState()
    val hasNext by player.hasNext.collectAsState()
    val waiting by player.waiting.collectAsState()
    val length by player.durationMs.collectAsState()
    // Where the song is, read twice a second while this screen shows: the ring and the times follow it.
    var position by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(shown?.song?.videoId, shown?.playing) {
        while (true) {
            position = player.positionMs()
            kotlinx.coroutines.delay(500)
        }
    }
    // Where a finger dragging along the ring points, 0 to 1; null while nothing is held.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val volume by player.volume.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val crown = remember { FocusRequester() }
    val knob = remember { WatchVolume.Crown() }
    LaunchedEffect(Unit) { crown.requestFocus() }
    val song = shown?.song
    val playing = shown?.playing == true

    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { e ->
                    val step = knob.step(e.verticalScrollPixels) ?: return@onRotaryScrollEvent true
                    player.crown(step, audio)
                    true
                }
                .focusRequester(crown)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = song?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.35f),
            )
            // How far into the song, around the edge of the screen.
            if (song != null && length > 0) {
                SongProgress(
                    scrub ?: SongTime.fraction(position, length),
                    Modifier.fillMaxSize(),
                    onScrub = { scrub = it },
                    onSeek = { f ->
                        val to = (f * length).toLong()
                        position = to
                        player.seekTo(to)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 22.dp)) {
                shown?.on?.let {
                    Text("Playing on $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    song?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                song?.artist?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (song != null && length > 0) {
                    Text(
                        "${SongTime.text(scrub?.let { (it * length).toLong() } ?: position.coerceAtMost(length))} / ${SongTime.text(length)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    IconButton(onClick = player::skipPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                    FilledIconButton(onClick = player::togglePlayPause, modifier = Modifier.size(56.dp)) {
                        // Waiting on the other device too: the press is on its way, no need to press again.
                        if ((buffering && shown?.on == null) || waiting) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play")
                    }
                    IconButton(onClick = player::skipNext, enabled = hasNext) { Icon(Icons.Rounded.SkipNext, "Next") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    song?.let { s ->
                        val isLiked = liked.any { it.videoId == s.videoId }
                        IconButton(onClick = { LibraryStore.setLiked(s, !isLiked) }) {
                            Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Unlike" else "Like")
                        }
                    }
                    IconButton(onClick = { onOpen(Route.QUEUE) }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Up next") }
                    IconButton(onClick = { onOpen(Route.MORE) }) { Icon(Icons.Rounded.MoreHoriz, "More") }
                }
            }
            volume?.let { VolumeBar(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) }
        }
    }
}

/** The volume the crown just set: a short bar at the bottom of the screen, gone a moment later. */
@Composable
private fun VolumeBar(level: Float, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(64.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Text("${(level * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Search by voice or keyboard, then the songs it found. */
@Composable
private fun Search(player: WatchPlayer, onPlay: (List<Song>, Int) -> Unit) {
    val found by player.found.collectAsState()
    val ask = rememberWords("Search songs") { player.search(it) }
    LaunchedEffect(Unit) { if (found is WatchPlayer.Found.Idle) ask() }

    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Place("Search again", Icons.Rounded.Search) { ask() } }
            when (val f = found) {
                WatchPlayer.Found.Idle -> Unit
                WatchPlayer.Found.Searching -> item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
                is WatchPlayer.Found.Failed -> item { Message("Couldn't search for \"${f.query}\". Check the watch's connection.") }
                is WatchPlayer.Found.Songs -> {
                    item { ListHeader { Text(f.query, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    if (f.songs.isEmpty()) item { Message("No songs found.") }
                    items(f.songs.size) { i -> SongRow(f.songs[i]) { onPlay(f.songs, i) } }
                }
            }
        }
    }
}

/** Asks for words by voice or the watch's keyboard; [onWords] gets what was said. */
@Composable
internal fun rememberWords(label: String, onWords: (String) -> Unit): () -> Unit {
    val launcher: ManagedActivityResultLauncher<Intent, ActivityResult> =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.let(RemoteInput::getResultsFromIntent)?.getCharSequence(QUERY)?.toString()
            if (!text.isNullOrBlank()) onWords(text)
        }
    return {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val input = RemoteInput.Builder(QUERY).setLabel(label).build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        RemoteInputIntentHelper.putTitleExtra(intent, label)
        intent.putExtra("android.support.wearable.input.extra.IME_ACTION", EditorInfo.IME_ACTION_SEARCH)
        launcher.launch(intent)
    }
}

@Composable
internal fun SongList(title: String, songs: List<Song>, empty: String, onPlay: (List<Song>, Int) -> Unit, current: Int = -1, onDownloadAll: (() -> Unit)? = null) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            if (onDownloadAll != null && songs.isNotEmpty()) item { Place("Download all", Icons.Rounded.Download, secondary = "${songs.size} songs") { onDownloadAll() } }
            if (songs.isEmpty()) item { Message(empty) }
            items(songs.size) { i -> SongRow(songs[i], playing = i == current) { onPlay(songs, i) } }
        }
    }
}

@Composable
internal fun SongRow(song: Song, playing: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (playing) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ButtonDefaults.LargeIconSize).clip(CircleShape),
            )
        },
        secondaryLabel = { com.opentune.wear.ui.ArtistWithMark(song) },
        label = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
internal fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private const val QUERY = "query"

/**
 * A thin line showing how far into the song. On a round watch it runs around the edge,
 * leaving a gap at the top where the clock sits. On a square one it is a straight line
 * along the bottom. Dragging along it (or tapping it) moves the song there: [onScrub] follows
 * the finger, [onSeek] gets where it let go.
 */
@Composable
private fun SongProgress(fraction: Float, modifier: Modifier = Modifier, onScrub: (Float?) -> Unit = {}, onSeek: ((Float) -> Unit)? = null) {
    val round = androidx.compose.ui.platform.LocalConfiguration.current.isScreenRound
    val done = MaterialTheme.colorScheme.primary
    val left = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val band = with(androidx.compose.ui.platform.LocalDensity.current) { SongTime.GRAB_DP.dp.toPx() }
    var held by remember { mutableStateOf(false) }
    val seek = onSeek?.let { androidx.compose.runtime.rememberUpdatedState(it) }
    val scrub = androidx.compose.runtime.rememberUpdatedState(onScrub)
    val touch = if (seek == null) Modifier else Modifier.pointerInput(round) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            fun at(p: androidx.compose.ui.geometry.Offset): Float? = SongTime.fractionAt(p.x, p.y, w, h, round, band, density)
            var f = at(down.position) ?: return@awaitEachGesture
            // On the line: this drag is a seek, not the screen's swipe back.
            down.consume()
            held = true
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            scrub.value(f)
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                // Once held, the finger may wander off the line: where it points still counts.
                SongTime.fractionAt(c.position.x, c.position.y, w, h, round, band = Float.MAX_VALUE, density = density)?.let { f = it; scrub.value(it) }
                c.consume()
            }
            held = false
            scrub.value(null)
            seek.value(f)
        }
    }
    androidx.compose.foundation.Canvas(modifier.then(touch)) {
        val stroke = (if (held) 6 else 3).dp.toPx()
        val f = fraction.coerceIn(0f, 1f)
        if (round) {
            val inset = stroke / 2 + 2.dp.toPx()
            val box = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val at = androidx.compose.ui.geometry.Offset(inset, inset)
            val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val start = 270f + SongTime.CLOCK_GAP_DEGREES / 2
            val whole = 360f - SongTime.CLOCK_GAP_DEGREES
            drawArc(left, start, whole, useCenter = false, topLeft = at, size = box, style = style)
            if (f > 0f) drawArc(done, start, whole * f, useCenter = false, topLeft = at, size = box, style = style)
        } else {
            val y = size.height - stroke / 2 - 4.dp.toPx()
            val x0 = SongTime.LINE_INSET_DP.dp.toPx()
            val x1 = size.width - SongTime.LINE_INSET_DP.dp.toPx()
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            drawLine(left, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x1, y), stroke, cap)
            if (f > 0f) drawLine(done, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x0 + (x1 - x0) * f, y), stroke, cap)
        }
    }
}

object SongTime {
    /** The part of the ring left out at the top so the clock doesn't cover it. */
    const val CLOCK_GAP_DEGREES = 48f
    /** How far in from the edge a finger still takes hold of the line. */
    const val GRAB_DP = 30f
    /** The straight line's distance from the sides, on a square watch. */
    const val LINE_INSET_DP = 16f

    /**
     * The place in the song at a touch ([x], [y]) on a [w] by [h] screen: along the ring on a
     * round watch, along the bottom line on a square one; null when the touch is further than
     * [band] from the line. Within the gap at the top it goes to the nearer end.
     */
    fun fractionAt(x: Float, y: Float, w: Float, h: Float, round: Boolean, band: Float, density: Float = 1f): Float? {
        if (!round) {
            if (h - y > band) return null
            val x0 = LINE_INSET_DP * density
            val x1 = w - LINE_INSET_DP * density
            return ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        }
        val dx = x - w / 2
        val dy = y - h / 2
        val radius = minOf(w, h) / 2
        if (radius - kotlin.math.hypot(dx, dy) > band) return null
        // Degrees clockwise from the top.
        var theta = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        if (theta < 0) theta += 360f
        val half = CLOCK_GAP_DEGREES / 2
        return ((theta - half) / (360f - CLOCK_GAP_DEGREES)).coerceIn(0f, 1f)
    }

    fun fraction(positionMs: Long, lengthMs: Long): Float = if (lengthMs <= 0) 0f else (positionMs.toFloat() / lengthMs).coerceIn(0f, 1f)

    /** "1:05", or "1:02:03" past an hour. */
    fun text(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
