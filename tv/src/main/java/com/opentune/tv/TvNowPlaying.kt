package com.opentune.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.lyricsOffset
import com.opentune.data.model.Song
import com.opentune.playback.MeasuredBitrate
import com.opentune.playback.dolbyName

private enum class Pane { Lyrics, Queue }

/** How far the seek bar and the Rewind and Fast-forward keys move. */
private const val SEEK_STEP_MS = 10_000L

@Composable
fun NowPlayingScreen(nav: TvNav) {
    val song by nav.player.currentSong.collectAsState()
    val current = song
    if (current == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing is playing", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Text("Pick something from Home, Explore or your Library.", style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim)
            Spacer(Modifier.height(28.dp))
            TvButton("Go to Home", { nav.goTab(Dest.Home) }, primary = true)
        }
    } else PlayerBody(nav, current)
}

@Composable
private fun PlayerBody(nav: TvNav, song: Song) {
    val p = nav.player
    val playing by p.isPlaying.collectAsState()
    val buffering by p.isBuffering.collectAsState()
    val position by p.position.collectAsState()
    val duration by p.durationMs.collectAsState()
    val format by p.format.collectAsState()
    val measured by MeasuredBitrate.kbps.collectAsState()
    val shuffle by p.shuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val likedList by LibraryStore.liked.collectAsState()
    val liked = remember(likedList, song.videoId) { likedList.any { it.videoId == song.videoId } }
    var pane by remember { mutableStateOf(Pane.Lyrics) }
    val playFocus = remember { FocusRequester() }
    // Focus starts on Play/Pause: the one control most visits want.
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Column(
        Modifier.fillMaxSize()
            .padding(horizontal = TvSafe.Horizontal, vertical = TvSafe.Vertical)
            .onPreviewKeyEvent { e -> mediaKey(e, p, SEEK_STEP_MS) },
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Cover(song.thumbnailUrl, Modifier.size(380.dp), RoundedCornerShape(20.dp))
            Spacer(Modifier.width(64.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Crossfade(pane, label = "pane") { which ->
                    when (which) {
                        Pane.Lyrics -> LyricsPane(p, song, position, duration)
                        Pane.Queue -> QueuePane(nav)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val line = TvPlayer.formatLine(format, measured)
            if (line.isNotEmpty()) {
                val dolby = dolbyName(format)
                if (dolby != null) Text(
                    dolby, style = MaterialTheme.typography.labelSmall, color = Color.Black,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(line, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, modifier = Modifier.padding(start = 12.dp), maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        SeekBar(position, duration, onSeekBy = p::seekBy, onSeekTo = p::seekTo)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            TvIconButton(Icons.Rounded.Shuffle, "Shuffle", p::toggleShuffle, active = shuffle)
            TvIconButton(Icons.Rounded.SkipPrevious, "Previous", p::skipPrevious)
            Box(contentAlignment = Alignment.Center) {
                TvIconButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", p::togglePlayPause, primary = true, focusRequester = playFocus)
                if (buffering) CircularProgressIndicator(Modifier.size(92.dp), color = Color.White.copy(alpha = 0.6f), strokeWidth = 3.dp)
            }
            TvIconButton(Icons.Rounded.SkipNext, "Next", p::skipNext)
            TvIconButton(if (repeat == REPEAT_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", p::cycleRepeat, active = repeat != REPEAT_OFF)
            Spacer(Modifier.width(30.dp))
            TvIconButton(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like", { p.toggleLike(song) }, active = liked, activeTint = TvColors.Like)
            TvIconButton(Icons.Rounded.Lyrics, "Lyrics", { pane = Pane.Lyrics }, active = pane == Pane.Lyrics)
            TvIconButton(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", { pane = Pane.Queue }, active = pane == Pane.Queue)
        }
    }
}

/** Media3's repeat modes: off, one song, all. */
private const val REPEAT_OFF = 0
private const val REPEAT_ONE = 1

/** The remote's own media keys, used while the player is on screen; true when one was handled. */
private fun mediaKey(e: androidx.compose.ui.input.key.KeyEvent, p: TvPlayer, stepMs: Long): Boolean {
    if (e.type != KeyEventType.KeyDown) return when (e.key) {
        // Swallow the release of a key we acted on, so the system doesn't act on it too.
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaNext, Key.MediaPrevious,
        Key.MediaFastForward, Key.MediaRewind -> true
        else -> false
    }
    return when (e.key) {
        Key.MediaPlayPause -> { p.togglePlayPause(); true }
        Key.MediaPlay -> { if (!p.isPlaying.value) p.togglePlayPause(); true }
        Key.MediaPause -> { if (p.isPlaying.value) p.togglePlayPause(); true }
        Key.MediaNext -> { p.skipNext(); true }
        Key.MediaPrevious -> { p.skipPrevious(); true }
        Key.MediaFastForward -> { p.seekBy(stepMs); true }
        Key.MediaRewind -> { p.seekBy(-stepMs); true }
        else -> false
    }
}

/** Where the song is: Left and Right on it move ten seconds; Up and Down leave it. */
@Composable
internal fun SeekBar(position: Long, duration: Long, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val thick by animateFloatAsState(if (focused) 10f else 5f, tween(120), label = "seekThick")
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(24.dp)
                // A cursor can click or drag along it, as well as the arrows nudging it.
                .pointerInput(duration) {
                    detectTapGestures { at -> if (duration > 0) onSeekTo((at.x / size.width * duration).toLong().coerceIn(0, duration)) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures { change, _ ->
                        if (duration > 0) onSeekTo((change.position.x / size.width * duration).toLong().coerceIn(0, duration))
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionLeft -> { onSeekBy(-SEEK_STEP_MS); true }
                        Key.DirectionRight -> { onSeekBy(SEEK_STEP_MS); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(thick.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)))
            Box(Modifier.fillMaxWidth(fraction).height(thick.dp).clip(CircleShape).background(if (focused) Color.White else TvColors.Accent))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(clock(position), style = MaterialTheme.typography.labelSmall, color = TvColors.TextDim)
            Spacer(Modifier.weight(1f))
            if (duration > 0) Text("-" + clock((duration - position).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = TvColors.TextDim)
        }
    }
}

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The words, following the song: the line being sung large and bright, the rest dim around it. */
@Composable
private fun LyricsPane(p: TvPlayer, song: Song, position: Long, duration: Long) {
    val lyrics by p.lyrics.collectAsState()
    when (val l = lyrics) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Column {
                Text(song.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.headlineSmall, color = TvColors.TextDim, maxLines = 1)
            }
        }
        is Lyrics.Synced -> SyncedLyrics(l, position + lyricsOffset(null, l))
        is Lyrics.Plain -> PlainLyrics(l.text, position, duration)
    }
}

@Composable
internal fun SyncedLyrics(lyrics: Lyrics.Synced, position: Long) {
    val lines = lyrics.lines
    val active by remember(lines) { derivedStateOf { lines.indexOfLast { it.startMs <= position }.coerceAtLeast(0) } }
    val state = rememberLazyListState()
    // The line being sung sits a little below the top, with the next ones coming up beneath it.
    LaunchedEffect(active) { state.animateScrollToItem((active - 1).coerceAtLeast(0)) }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), userScrollEnabled = false) {
        itemsIndexed(lines, key = { i, l -> "$i:${l.startMs}" }) { i, line -> LyricRow(line, i - active) }
    }
}

@Composable
private fun LyricRow(line: LyricLine, distance: Int) {
    val on = distance == 0
    val alpha by animateFloatAsState(
        when { on -> 1f; distance < 0 -> 0.28f; distance == 1 -> 0.6f; else -> 0.4f }, tween(260), label = "lyricAlpha",
    )
    Text(
        line.text,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().alpha(alpha).graphicsLayer {
            val s = if (on) 1f else 0.88f
            scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
        },
    )
}

/** Words without timing: shown whole, and slid up in step with the song so the eye can follow. */
@Composable
private fun PlainLyrics(text: String, position: Long, duration: Long) {
    val scroll = rememberScrollState()
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    LaunchedEffect(fraction, scroll.maxValue) { scroll.animateScrollTo((scroll.maxValue * fraction).toInt()) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = false)) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = TvColors.Text.copy(alpha = 0.85f))
    }
}

/** The queue: what's playing and what follows; OK on a song plays it. */
@Composable
private fun QueuePane(nav: TvNav) {
    val queue by nav.player.queue.collectAsState()
    val index by nav.player.currentIndex.collectAsState()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))
    if (queue.isEmpty()) { TvNote("The queue is empty."); return }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(queue, key = { i, s -> "$i:${s.videoId}" }) { i, s ->
            TvTrackRow(
                number = i + 1, title = s.title, subtitle = s.artist, length = s.durationText,
                onClick = { nav.player.playQueueItem(i) }, playing = i == index, imageUrl = s.thumbnailUrl,
            )
        }
    }
}
