package com.opentune.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.lyricsOffset
import com.opentune.data.model.Song
import com.opentune.playback.MeasuredBitrate
import com.opentune.playback.dolbyName

/*
 * Now Playing, made for a weak TV box: nothing on it moves on its own. The cover, the song and
 * the buttons redraw only when the song or a button's state changes; the playback position is
 * read in two places alone, the seek bar and the lyrics, and the lyrics redraw only when the
 * line changes. The background is the shell's still tint from the cover (see TvRoot's Backdrop).
 *
 * Focus starts on Play/Pause. Up reaches the seek bar (Left and Right move ten seconds), and from
 * there the queue when it is showing. Queue moves the focus onto the playing song in it, and Back
 * from the queue returns to the Queue button. Pressing Lyrics or Queue again while it shows puts
 * the panel away: the cover slides to the middle, the one movement on this screen, done once.
 */

private enum class Pane { None, Lyrics, Queue }

/** How long the cover takes to slide to the middle and back. */
private const val COVER_SLIDE_MS = 320

/** How far the seek bar and the Rewind and Fast-forward keys move. */
private const val SEEK_STEP_MS = 10_000L

@Composable
fun NowPlayingScreen(nav: TvNav) {
    val song by nav.player.currentSong.collectAsState()
    val current = song
    if (current == null) NothingPlaying(nav) else PlayerBody(nav, current)
}

@Composable
private fun NothingPlaying(nav: TvNav) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = TvSafe.Horizontal, vertical = TvSafe.Vertical),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing is playing", style = TvType.Headline)
        Spacer(Modifier.height(TvSpace.S))
        Text("Pick something from Home, Explore or your Library.", style = TvType.Body, color = TvColors.TextDim)
        Spacer(Modifier.height(TvSpace.XL))
        TvPillButton("Go to Home", { nav.goTab(Dest.Home) }, primary = true, entryFocus = true)
    }
}

@Composable
private fun PlayerBody(nav: TvNav, song: Song) {
    val p = nav.player
    var pane by rememberSaveable { mutableStateOf(Pane.Lyrics) }
    // Bumped by the Queue button: the queue then takes the focus, on the playing song.
    var queueJumps by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }
    val queueButton = remember { FocusRequester() }
    val queueEntry = remember { FocusRequester() }
    // Focus starts on Play/Pause: the one control most visits want.
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    // The panel last shown stays drawn while it fades away, then goes.
    var shown by rememberSaveable { mutableStateOf(Pane.Lyrics) }
    if (pane != Pane.None) shown = pane
    val alone by animateFloatAsState(
        if (pane == Pane.None) 1f else 0f, tween(COVER_SLIDE_MS, easing = FastOutSlowInEasing), label = "coverAlone",
    )

    Column(
        Modifier.fillMaxSize()
            .padding(horizontal = TvSafe.Horizontal, vertical = TvSafe.Vertical)
            .onPreviewKeyEvent { e -> mediaKey(e, p, SEEK_STEP_MS) },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // As tall as the space allows and square: its size is set by the screen, never by the image.
            val side = minOf(maxHeight, maxWidth)
            val middle = (maxWidth - side) / 2
            // Moved by translation alone, so the slide redraws one layer and lays nothing out again.
            Cover(
                song.thumbnailUrl,
                Modifier.size(side).graphicsLayer { translationX = middle.toPx() * alone },
                TvShapes.Card, px = TvArt.Large,
            )
            if (alone < 1f) Column(
                Modifier.fillMaxSize().padding(start = side + TvSpace.XXL).graphicsLayer { alpha = 1f - alone },
            ) {
                SongHeading(p, song)
                Spacer(Modifier.height(TvSpace.L))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (if (pane == Pane.None) shown else pane) {
                        Pane.Queue -> QueuePane(nav, queueEntry, queueJumps, onBack = { runCatching { queueButton.requestFocus() } })
                        else -> LyricsPane(p)
                    }
                }
            }
        }
        Spacer(Modifier.height(TvSpace.L))
        PlayerSeekBar(p, Modifier.focusProperties { down = playFocus })
        Spacer(Modifier.height(TvSpace.M))
        Controls(
            p, song, pane, playFocus, queueButton,
            // Pressed again while its panel shows, each puts the panel away; the focus stays on it.
            onLyrics = { pane = if (pane == Pane.Lyrics) Pane.None else Pane.Lyrics },
            onQueue = { if (pane == Pane.Queue) pane = Pane.None else { pane = Pane.Queue; queueJumps++ } },
        )
    }
}

/**
 * The song's name large, its artist (and album) under it, and a line of what the sound is
 * (Dolby, sample rate, bit depth, bitrate) or "Loading…" while it comes in. Redraws when the
 * song, the format or the loading changes, not as the song goes on.
 */
@Composable
private fun SongHeading(p: TvPlayer, song: Song) {
    Column(Modifier.fillMaxWidth()) {
        Text(song.title, style = TvType.Display, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val by = listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.albumName?.takeIf { it.isNotBlank() && it != song.title })
        Text(
            by.joinToString(" · "), style = TvType.Body, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(TvSpace.M))
        FormatLine(p)
    }
}

/** "[Dolby Atmos] 48 kHz · 24-bit · 768 kbps", or a still "Loading…" tag while the song buffers. Always one line tall. */
@Composable
private fun FormatLine(p: TvPlayer) {
    val buffering by p.isBuffering.collectAsState()
    val format by p.format.collectAsState()
    val measured by MeasuredBitrate.kbps.collectAsState()
    val parts = remember(format, measured) { TvPlayer.formatParts(format, measured) }
    val dolby = remember(format) { format?.let(::dolbyName) }
    Row(Modifier.height(FORMAT_LINE_HEIGHT), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpace.M)) {
        if (buffering) TvTag("Loading…")
        if (dolby != null) TvTag(dolby, strong = true)
        val rest = if (dolby != null) parts.drop(1) else parts
        if (rest.isNotEmpty()) Text(rest.joinToString(" · "), style = TvType.Meta, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val FORMAT_LINE_HEIGHT = 30.dp

/**
 * The buttons: shuffle, previous, play/pause, next and repeat in the middle; like, lyrics and the
 * queue at the right. Lyrics and Queue show which pane is up, and put it away when pressed again.
 */
@Composable
private fun Controls(
    p: TvPlayer,
    song: Song,
    pane: Pane,
    playFocus: FocusRequester,
    queueButton: FocusRequester,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
) {
    val shuffle by p.shuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val likedList by LibraryStore.liked.collectAsState()
    val liked = remember(likedList, song.videoId) { likedList.any { it.videoId == song.videoId } }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpace.M)) {
            TvIconButton(Icons.Rounded.Shuffle, "Shuffle", p::toggleShuffle, active = shuffle, focusKey = "np:shuffle")
            TvIconButton(Icons.Rounded.SkipPrevious, "Previous", p::skipPrevious, focusKey = "np:previous")
            PlayPause(p, playFocus)
            TvIconButton(Icons.Rounded.SkipNext, "Next", p::skipNext, focusKey = "np:next")
            TvIconButton(
                if (repeat == REPEAT_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                if (repeat == REPEAT_ONE) "Repeat one" else "Repeat", p::cycleRepeat, active = repeat != REPEAT_OFF, focusKey = "np:repeat",
            )
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpace.M)) {
            TvIconButton(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like",
                { p.toggleLike(song) }, active = liked, activeTint = TvColors.Like, focusKey = "np:like",
            )
            TvIconButton(Icons.Rounded.Lyrics, "Lyrics", onLyrics, active = pane == Pane.Lyrics, focusKey = "np:lyrics")
            TvIconButton(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue, active = pane == Pane.Queue, focusRequester = queueButton, focusKey = "np:queue")
        }
    }
}

/** Play/Pause, alone in its own scope so that playing and pausing redraw it and nothing else. */
@Composable
private fun PlayPause(p: TvPlayer, focus: FocusRequester) {
    val playing by p.isPlaying.collectAsState()
    TvIconButton(
        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
        p::togglePlayPause, primary = true, focusRequester = focus, focusKey = "np:play", entryFocus = true,
    )
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

/** The seek bar for the player: the one place, with the lyrics, that reads where the song is. */
@Composable
private fun PlayerSeekBar(p: TvPlayer, modifier: Modifier = Modifier) {
    val position by p.position.collectAsState()
    val duration by p.durationMs.collectAsState()
    SeekBar(position, duration, onSeekBy = p::seekBy, onSeekTo = p::seekTo, modifier = modifier)
}

/**
 * Where the song is, with the time gone and the time left under it. Focused, it wears the ring
 * and a lighter fill, its line turns the accent and a dot marks the spot; Left and Right move ten
 * seconds, Up and Down leave it. A phone's cursor can click or drag along it. Nothing animates:
 * the line is drawn, not laid out, so a new position only redraws it.
 */
@Composable
internal fun SeekBar(
    position: Long,
    duration: Long,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // A cursor's tap or drag takes the focus too, so the ring is where the viewer is working.
    val self = remember { FocusRequester() }
    fun take() { if (!focused) runCatching { self.requestFocus() } }
    // Like every button, it takes the focus only from the remote's keys, never in touch mode: so
    // the screen's first focus can't land here ahead of Play.
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val inset = with(LocalDensity.current) { SEEK_INSET.toPx() }
    val at by rememberUpdatedState(fraction)
    fun seekAt(x: Float, width: Int) {
        if (duration <= 0) return
        val f = ((x - inset) / (width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
        onSeekTo((f * duration).toLong())
    }
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier.fillMaxWidth().height(SEEK_HEIGHT)
                // A cursor can click or drag along it, as well as the arrows nudging it.
                .pointerInput(duration) { detectTapGestures { pos -> take(); seekAt(pos.x, size.width) } }
                .pointerInput(duration) { detectHorizontalDragGestures { change, _ -> take(); seekAt(change.position.x, size.width) } }
                .focusRequester(self)
                .tvFocusRing(focused, TvShapes.Pill)
                .background(if (focused) TvColors.SurfaceHigh else Color.Transparent, TvShapes.Pill)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionLeft -> { onSeekBy(-SEEK_STEP_MS); true }
                        Key.DirectionRight -> { onSeekBy(SEEK_STEP_MS); true }
                        else -> false
                    }
                }
                .semantics { contentDescription = "Seek" }
                .focusProperties { canFocus = keys }
                .focusable()
                .drawBehind {
                    val y = size.height / 2
                    val stroke = (if (focused) 8.dp else 6.dp).toPx()
                    val start = inset
                    val end = size.width - inset
                    val x = start + (end - start) * at
                    drawLine(TRACK, Offset(start, y), Offset(end, y), stroke, StrokeCap.Round)
                    if (at > 0f) drawLine(if (focused) TvColors.Accent else TvColors.Text, Offset(start, y), Offset(x, y), stroke, StrokeCap.Round)
                    if (focused) drawCircle(TvColors.Accent, 9.dp.toPx(), Offset(x, y))
                },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = SEEK_INSET - 2.dp, vertical = TvSpace.XS)) {
            Text(clock(position), style = TvType.Meta, color = TvColors.TextDim)
            Spacer(Modifier.weight(1f))
            if (duration > 0) Text("-" + clock((duration - position).coerceAtLeast(0)), style = TvType.Meta, color = TvColors.TextDim)
        }
    }
}

private val SEEK_HEIGHT = 28.dp
/** How far in from the bar's rounded ends its line starts and stops. */
private val SEEK_INSET = 14.dp
/** The bar's empty part: a light line that reads on any cover's tint. */
private val TRACK = Color.White.copy(alpha = 0.22f)

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ---- Lyrics ----

/** The words, following the song: synced lines, plain text, or a quiet line saying why there are none. */
@Composable
private fun LyricsPane(p: TvPlayer) {
    val words by p.words.collectAsState()
    when (val w = words) {
        Words.Looking -> LyricsNote("Setting up the lyrics…")
        Words.None -> LyricsNote("No lyrics for this one")
        is Words.Ready -> {
            // The State itself, not its value: only the lyrics' own scopes read it.
            val position = p.position.collectAsState()
            when (val l = w.lyrics) {
                is Lyrics.Synced -> {
                    val offset = lyricsOffset(null, l)
                    SyncedLyrics(l) { position.value + offset }
                }
                is Lyrics.Plain -> {
                    val duration = p.durationMs.collectAsState()
                    PlainLyrics(l.text, { position.value }, { duration.value })
                }
            }
        }
    }
}

@Composable
private fun LyricsNote(text: String) {
    Text(text, style = TvType.Title, color = TvColors.TextDim, modifier = Modifier.padding(top = TvSpace.S))
}

/** [SyncedLyrics] at a fixed [position], for a still screen. */
@Composable
internal fun SyncedLyrics(lyrics: Lyrics.Synced, position: Long) = SyncedLyrics(lyrics) { position }

/**
 * Synced lines: the one being sung bright, the next ones dim, the ones gone fainter. When the line
 * changes the list jumps so it sits second from the top; nothing eases or glows. [position] is
 * read inside a derived state, so this redraws once a line, not on every tick of the clock.
 */
@Composable
internal fun SyncedLyrics(lyrics: Lyrics.Synced, position: () -> Long) {
    val lines = lyrics.lines
    val now by rememberUpdatedState(position)
    val active by remember(lines) { derivedStateOf { activeLine(lines, now()) } }
    val state = rememberLazyListState()
    LaunchedEffect(active) { state.scrollToItem((active - 1).coerceAtLeast(0)) }
    LazyColumn(
        state = state, modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(TvSpace.L), userScrollEnabled = false,
    ) {
        itemsIndexed(lines, key = { i, l -> "$i:${l.startMs}" }) { i, line -> LyricRow(line, i - active) }
    }
}

/** The line being sung at [position]: the last to have started, or -1 before the first. */
internal fun activeLine(lines: List<LyricLine>, position: Long): Int = lines.indexOfLast { it.startMs <= position }

@Composable
private fun LyricRow(line: LyricLine, distance: Int) {
    // Only the colour changes between states, so a line never reflows as it is reached.
    Text(
        line.text.ifBlank { "♪" },
        style = TvType.Title,
        color = when {
            distance == 0 -> TvColors.Text
            distance < 0 -> TvColors.TextFaint
            else -> TvColors.TextDim
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Words without timing, shown whole and moved down in steps of a few lines as the song goes on, so
 * the part being sung is roughly in view. Each step is a jump, at most every few seconds.
 */
@Composable
private fun PlainLyrics(text: String, position: () -> Long, duration: () -> Long) {
    val scroll = rememberScrollState()
    val step = with(LocalDensity.current) { PLAIN_STEP.roundToPx() }
    val target by remember(scroll, step) {
        derivedStateOf {
            val d = duration()
            if (d <= 0 || scroll.maxValue <= 0) 0
            else {
                val raw = (scroll.maxValue * (position().toFloat() / d).coerceIn(0f, 1f)).toInt()
                (raw - raw % step).coerceAtMost(scroll.maxValue)
            }
        }
    }
    LaunchedEffect(target) { scroll.scrollTo(target) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = false)) {
        Text(text, style = TvType.Title, color = TvColors.TextDim, textAlign = TextAlign.Start)
    }
}

/** About three lines of plain lyrics: how far they jump at a time. */
private val PLAIN_STEP = 102.dp

// ---- Queue ----

/**
 * The queue: what's playing and what follows, opened on the playing song; OK on a song plays it.
 * Coming in from below lands on the playing song; [jumps] going up (the Queue button) puts the
 * focus there too, and Back from the list calls [onBack].
 */
@Composable
private fun QueuePane(nav: TvNav, entry: FocusRequester, jumps: Int, onBack: () -> Unit) {
    val queue by nav.player.queue.collectAsState()
    val index by nav.player.currentIndex.collectAsState()
    if (queue.isEmpty()) { LyricsNote("The queue is empty."); return }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceIn(0, queue.lastIndex))
    var inside by remember { mutableStateOf(false) }
    BackHandler(enabled = inside, onBack = onBack)
    LaunchedEffect(jumps) {
        if (jumps == 0) return@LaunchedEffect
        state.scrollToItem((index - 1).coerceIn(0, queue.lastIndex))
        withFrameNanos { }
        runCatching { entry.requestFocus() }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize()
            .onFocusChanged { inside = it.hasFocus }
            .focusProperties {
                onEnter = { if (requestedFocusDirection != FocusDirection.Enter) runCatching { entry.requestFocus() } }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(TvSpace.XS),
    ) {
        itemsIndexed(queue, key = { i, s -> "$i:${s.videoId}" }) { i, s ->
            val now = i == index
            TvSongRow(
                s, onClick = { nav.player.playQueueItem(i) }, playing = now, focusKey = "queue/$i:${s.videoId}",
                modifier = if (now) Modifier.focusRequester(entry) else Modifier,
            )
        }
    }
}
