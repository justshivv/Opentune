package com.opentune.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.opentune.data.MusicRepository
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.lyrics.lyricsOffset
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import com.opentune.playback.SleepTimer
import com.opentune.wear.Message
import com.opentune.wear.Place
import com.opentune.wear.Route
import com.opentune.wear.SongList
import com.opentune.wear.WatchPlayer
import com.opentune.wear.WatchVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** What else the player screen leads to: lyrics, the sleep timer, devices, headphones. */
@Composable
fun More(player: WatchPlayer, onOpen: (String) -> Unit) {
    val controlling by player.controlling.collectAsState()
    val shown by player.shown.collectAsState()
    val timer by SleepTimer.state.collectAsState()
    val context = LocalContext.current
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("More") } }
            item { Place("Lyrics", Icons.Rounded.Lyrics) { onOpen(Route.LYRICS) } }
            // Also for a song playing on another device: it's kept here, for later.
            shown?.song?.takeIf { com.opentune.data.isYouTubeId(it.videoId) }?.let { s -> item { DownloadPlace(s) } }
            // The sleep timer stops this watch's player; another device keeps its own.
            if (controlling == null) item { Place("Sleep timer", Icons.Rounded.Bedtime, secondary = SleepText.of(timer, System.currentTimeMillis())) { onOpen(Route.SLEEP) } }
            item { Place("Devices", Icons.Rounded.Devices) { onOpen(Route.DEVICES) } }
            item { Place("Headphones", Icons.Rounded.Headphones) { context.startActivity(WatchVolume.headphonesIntent()) } }
        }
    }
}

/** The sleep timer's state in a few words, for the button that opens it. */
object SleepText {
    fun of(state: SleepTimer.State, now: Long): String = when (state) {
        SleepTimer.State.Off -> "Off"
        SleepTimer.State.EndOfTrack -> "End of the song"
        is SleepTimer.State.At -> "${((state.endsAtMs - now + 59_999) / 60_000).coerceAtLeast(0)} min left"
    }
}

@Composable
fun SleepTimerScreen(onDone: () -> Unit) {
    val state by SleepTimer.state.collectAsState()
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Sleep timer") } }
            item { Message(SleepText.of(state, System.currentTimeMillis())) }
            item { Place("Off", Icons.Rounded.Bedtime) { SleepTimer.cancel(); onDone() } }
            listOf(15, 30, 60).forEach { m -> item { Place("$m minutes", Icons.Rounded.Bedtime) { SleepTimer.startMinutes(m); onDone() } } }
            item { Place("End of the song", Icons.Rounded.Bedtime) { SleepTimer.endOfTrack(); onDone() } }
        }
    }
}

enum class LibraryKind(val title: String) { PLAYLISTS("Playlists"), ALBUMS("Albums"), MIXES("Mixes") }

/** The account's library, for a watch signed in to YouTube Music. */
@Composable
fun Library(onOpen: (LibraryKind) -> Unit) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Library") } }
            item { Place(LibraryKind.PLAYLISTS.title, Icons.AutoMirrored.Rounded.QueueMusic) { onOpen(LibraryKind.PLAYLISTS) } }
            item { Place(LibraryKind.ALBUMS.title, Icons.Rounded.Album) { onOpen(LibraryKind.ALBUMS) } }
            item { Place(LibraryKind.MIXES.title, Icons.Rounded.Shuffle) { onOpen(LibraryKind.MIXES) } }
        }
    }
}

/** The mixes on YouTube Music's home ("My Mix", "Discover Mix" and the like). */
internal fun mixesIn(shelves: List<com.opentune.data.model.HomeShelf>): List<ShelfItem> =
    shelves.flatMap { it.items }
        .filter { item -> val id = item.browseId ?: return@filter false; item.videoId == null && (item.title.contains("mix", ignoreCase = true) || "RDTMAK" in id || id.startsWith("VLRD")) }
        .distinctBy { it.browseId }

@Composable
fun LibraryShelf(kind: LibraryKind, onOpen: (String, String) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<ShelfItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(kind, attempt) {
        failed = false; items = null
        try {
            items = when (kind) {
                LibraryKind.PLAYLISTS -> MusicRepository.libraryPlaylists()
                LibraryKind.ALBUMS -> MusicRepository.libraryAlbums()
                LibraryKind.MIXES -> mixesIn(MusicRepository.home())
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(kind.title) } }
            val found = items
            when {
                failed -> {
                    item { Message("Couldn't load. Check the watch's connection.") }
                    item { Place("Try again", Icons.Rounded.Refresh) { attempt++ } }
                }
                found == null -> item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
                found.isEmpty() -> item { Message("Nothing here yet.") }
                else -> found.forEach { it ->
                    item {
                        Button(
                            onClick = { onOpen(it.browseId!!, it.title) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            icon = {
                                AsyncImage(
                                    model = it.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(ButtonDefaults.LargeIconSize).clip(RoundedCornerShape(6.dp)),
                                )
                            },
                            secondaryLabel = it.subtitle.takeIf { s -> s.isNotBlank() }?.let { s -> { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            label = { Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

/** A playlist's, album's or mix's songs. */
@Composable
fun CollectionSongs(browseId: String, title: String, onPlay: (List<Song>, Int) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(browseId, attempt) {
        failed = false; songs = null
        try { songs = MusicRepository.collection(browseId).songs } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    val found = songs
    when {
        failed -> SongList(title, emptyList(), empty = "Couldn't load. Check the watch's connection.", onPlay)
        found == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
        else -> SongList(title, found, empty = "No songs here.", onPlay, onDownloadAll = { com.opentune.wear.WatchDownloads.downloadAll(found) })
    }
}

/**
 * Synced lines follow the song: every line in one steady list that glides to
 * the one playing, which is brighter; plain lyrics just scroll. Only a new
 * line redraws the screen.
 */
@Composable
fun Lyrics(player: WatchPlayer) {
    val shown by player.shown.collectAsState()
    val song = shown?.song
    val here = shown?.on == null
    var lyrics by remember { mutableStateOf<com.opentune.data.lyrics.Lyrics?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(song?.videoId) {
        lyrics = null
        loading = song != null
        if (song == null) return@LaunchedEffect
        // The recording's real length picks lyrics timed to it, and only this song's (just after a
        // change the player can still report the last one's).
        val length = if (here) kotlinx.coroutines.withTimeoutOrNull(3_000) { player.player.durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second } else null
        // Off the main thread: weighing the sources against each other kept the watch's busy for
        // seconds, and the system closed the app for not answering.
        val started = System.currentTimeMillis()
        lyrics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { LyricsRepository.lyricsFor(song.videoId, song.title, song.artist, length ?: song.durationMillis()) }
                .onFailure { android.util.Log.w("WatchLyrics", "lookup failed for ${song.videoId}", it) }
                .getOrNull()
        }
        android.util.Log.i("WatchLyrics", "${song.videoId} length=${length ?: song.durationMillis()} (player ${length != null}) found=${lyrics?.let { it::class.simpleName + " " + it.source }} in ${System.currentTimeMillis() - started}ms")
        loading = false
    }
    val synced = lyrics as? com.opentune.data.lyrics.Lyrics.Synced
    var at by remember { mutableIntStateOf(-1) }
    LaunchedEffect(synced) {
        val l = synced ?: return@LaunchedEffect
        val starts = l.lines.map { it.startMs }
        val offset = lyricsOffset(null, l)
        val clock = LyricLines.Steady()
        while (true) {
            // Only a change of line is told, so the list redraws once per line, not on every reading.
            at = LyricLines.currentIndex(starts, clock.next(player.positionMs()) + offset)
            delay(200)
        }
    }
    val list = rememberScalingLazyListState()
    LaunchedEffect(at, synced) {
        // Row 0 is the title, so line i is row i + 1.
        if (synced != null && at >= 0) list.animateScrollToItem(at + 1)
    }
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            when (val l = lyrics) {
                null -> item {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    else Message(if (song == null) "Nothing playing." else "No lyrics for this song.")
                }
                is com.opentune.data.lyrics.Lyrics.Plain -> {
                    item { ListHeader { Text(song?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    val lines = l.text.lines().filter { it.isNotBlank() }
                    items(lines.size, key = { it }) { i -> LyricText(lines[i], current = true) }
                }
                is com.opentune.data.lyrics.Lyrics.Synced -> {
                    item(key = "title") { ListHeader { Text(song?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    items(l.lines.size, key = { it }) { i -> LyricText(l.lines[i].text.ifBlank { "♪" }, current = i == at) }
                }
            }
        }
    }
}

@Composable
private fun LyricText(text: String, current: Boolean) {
    // One size for every line, so a line becoming current doesn't move the others: only its colour changes.
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

object LyricLines {
    /**
     * The player's position as the lyrics follow it: never back, except for a real jump.
     * Between its updates the player guesses where the song is, and on the watch the guess
     * runs a little ahead and is then pulled back; read as it is, a line showed for a blink
     * and the one before came back. A step back of less than [JUMP_MS] is held at the
     * furthest point; a longer one (a seek, a new song) is followed.
     */
    class Steady {
        private var furthest = Long.MIN_VALUE

        fun next(positionMs: Long): Long {
            if (positionMs >= furthest || furthest - positionMs > JUMP_MS) furthest = positionMs
            return furthest
        }
    }

    const val JUMP_MS = 2_500L

    /** The line playing at [positionMs]: the last one started, or -1 before the first. */
    fun currentIndex(starts: List<Long>, positionMs: Long): Int = starts.indexOfLast { it <= positionMs }
}
