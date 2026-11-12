package com.opentune.wear

import android.app.RemoteInput
import android.content.Intent
import android.media.AudioManager
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
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
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song

private object Route {
    const val HOME = "home"
    const val PLAYING = "playing"
    const val SEARCH = "search"
    const val LIKED = "liked"
    const val RECENT = "recent"
    const val QUEUE = "queue"
}

/** The watch app: a list of places to start from, and the song that's playing. */
@Composable
fun WatchApp(player: WatchPlayer) {
    val nav = rememberSwipeDismissableNavController()
    fun play(songs: List<Song>, index: Int) {
        player.playAll(songs, index)
        nav.navigate(Route.PLAYING) { launchSingleTop = true }
    }
    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = Route.HOME) {
                composable(Route.HOME) { Home(player, onOpen = { nav.navigate(it) }) }
                composable(Route.PLAYING) { NowPlaying(player, onQueue = { nav.navigate(Route.QUEUE) }) }
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
                    val queue by player.queue.collectAsState()
                    val current by player.currentIndex.collectAsState()
                    SongList("Up next", queue, empty = "The queue is empty.", { _, i -> player.playQueueItem(i); nav.popBackStack() }, current = current)
                }
            }
        }
    }
}

@Composable
private fun Home(player: WatchPlayer, onOpen: (String) -> Unit) {
    val song by player.currentSong.collectAsState()
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("WEAV") } }
            song?.let { s ->
                item {
                    Button(
                        onClick = { onOpen(Route.PLAYING) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(Icons.Rounded.MusicNote, null) },
                        secondaryLabel = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        label = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            item { Place("Search", Icons.Rounded.Search) { onOpen(Route.SEARCH) } }
            item { Place("Liked songs", Icons.Rounded.Favorite) { onOpen(Route.LIKED) } }
            item { Place("Recent", Icons.Rounded.History) { onOpen(Route.RECENT) } }
        }
    }
}

@Composable
private fun Place(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(icon, null) },
        label = { Text(label) },
    )
}

/** The cover behind the controls; the crown turns the volume. */
@Composable
private fun NowPlaying(player: WatchPlayer, onQueue: () -> Unit) {
    val song by player.currentSong.collectAsState()
    val playing by player.isPlaying.collectAsState()
    val buffering by player.isBuffering.collectAsState()
    val hasNext by player.hasNext.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val crown = remember { FocusRequester() }
    LaunchedEffect(Unit) { crown.requestFocus() }

    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { e ->
                    val step = WatchVolume.step(e.verticalScrollPixels) ?: return@onRotaryScrollEvent true
                    audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, step, AudioManager.FLAG_SHOW_UI)
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 22.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    IconButton(onClick = player::skipPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                    FilledIconButton(onClick = player::togglePlayPause, modifier = Modifier.size(56.dp)) {
                        if (buffering) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play")
                    }
                    IconButton(onClick = player::skipNext, enabled = hasNext) { Icon(Icons.Rounded.SkipNext, "Next") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { context.startActivity(WatchVolume.headphonesIntent()) }) { Icon(Icons.Rounded.Headphones, "Headphones") }
                    song?.let { s ->
                        val isLiked = liked.any { it.videoId == s.videoId }
                        IconButton(onClick = { LibraryStore.setLiked(s, !isLiked) }) {
                            Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Unlike" else "Like")
                        }
                    }
                    IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Up next") }
                }
            }
        }
    }
}

/** Search by voice or keyboard, then the songs it found. */
@Composable
private fun Search(player: WatchPlayer, onPlay: (List<Song>, Int) -> Unit) {
    val found by player.found.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let(RemoteInput::getResultsFromIntent)?.getCharSequence(QUERY)?.toString()
        if (!text.isNullOrBlank()) player.search(text)
    }
    fun askForWords() {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val input = RemoteInput.Builder(QUERY).setLabel("Search songs").build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        RemoteInputIntentHelper.putTitleExtra(intent, "Search")
        intent.putExtra("android.support.wearable.input.extra.IME_ACTION", EditorInfo.IME_ACTION_SEARCH)
        ask.launch(intent)
    }
    LaunchedEffect(Unit) { if (found is WatchPlayer.Found.Idle) askForWords() }

    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Place("Search again", Icons.Rounded.Search, ::askForWords) }
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

@Composable
private fun SongList(title: String, songs: List<Song>, empty: String, onPlay: (List<Song>, Int) -> Unit, current: Int = -1) {
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(title) } }
            if (songs.isEmpty()) item { Message(empty) }
            items(songs.size) { i -> SongRow(songs[i], playing = i == current) { onPlay(songs, i) } }
        }
    }
}

@Composable
private fun SongRow(song: Song, playing: Boolean = false, onClick: () -> Unit) {
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
        secondaryLabel = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private const val QUERY = "query"
