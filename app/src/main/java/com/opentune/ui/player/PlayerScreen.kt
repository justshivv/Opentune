package com.opentune.ui.player

import androidx.compose.runtime.produceState
import com.opentune.data.covers.AlbumCovers
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import com.opentune.data.LogExport
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.HighQuality
import com.opentune.playback.PlaybackRequests
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.PlayerStyle
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.LocalBackdrop
import com.opentune.ui.components.LocalHazeState
import com.opentune.ui.components.LocalSongMenu
import com.opentune.ui.components.MenuRow
import com.opentune.ui.components.SongMenuSheet
import com.opentune.ui.components.glass
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.RemixPreset
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.playback.AudioFormatInfo
import com.opentune.data.settings.ThemeSettings
import com.opentune.data.model.Song
import com.opentune.ui.LyricsState
import com.opentune.ui.PlayerViewModel
import com.opentune.data.together.Together
import com.opentune.ui.components.MarqueeText
import com.opentune.ui.theme.PlayerTheme
import com.opentune.ui.theme.rememberArtworkSeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The playback position, refreshed every frame while playing and the caller
 * is on screen (MediaController extrapolates between updates, so this is
 * smooth), and a few times a second while paused, to catch seeks.
 */
@Composable
fun rememberPlaybackPosition(vm: PlayerViewModel): () -> Long {
    val position = remember { mutableLongStateOf(vm.positionMs()) }
    LaunchedEffect(vm) {
        while (true) {
            if (vm.isPlaying.value) withFrameMillis { } else delay(250)
            position.longValue = vm.positionMs()
        }
    }
    return { position.longValue }
}

/** Everything the full player shows, so it can be drawn without a live player. */
data class PlayerUiState(
    val song: Song,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val hasNext: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val durationMs: Long,
    val lyrics: LyricsState,
    val queue: List<Song>,
    val currentIndex: Int,
    val upNext: List<Int>,
    val sound: SoundSettings,
    val theme: ThemeSettings,
    val ui: InterfaceSettings = InterfaceSettings(),
    /** "Playing from" label: an album, a playlist, Search… */
    val source: String? = null,
    val audioFormat: AudioFormatInfo? = null,
)

class PlayerActions(
    val togglePlay: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val seekTo: (Long) -> Unit,
    val playIndex: (Int) -> Unit,
    val removeIndex: (Int) -> Unit,
    val collapse: () -> Unit,
    val moveIndex: (Int, Int) -> Unit = { _, _ -> },
    val openTogether: () -> Unit = {},
)

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onCollapse: () -> Unit,
    /** True while the player sits fully open, not being dragged down. */
    onCovering: (Boolean) -> Unit = {},
    onTogether: () -> Unit = {},
) {
    val song by vm.currentSong.collectAsState()
    val current = song ?: return
    val theme by AppSettings.theme.collectAsState()
    val sound by AppSettings.sound.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    val shuffle by vm.shuffleEnabled.collectAsState()
    val repeat by vm.repeatMode.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    val queue by vm.queue.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val upNext by vm.upNext.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    // A music video's frame or a local file without art: the album's own cover, when MusicBrainz knows it.
    val cover by produceState<String?>(null, current.videoId, ui.albumCovers) {
        value = if (ui.albumCovers && AlbumCovers.wants(current)) AlbumCovers.coverFor(current) else null
    }
    val shown = cover?.let { current.copy(thumbnailUrl = it) } ?: current
    val source by vm.source.collectAsState()
    val audioFormat by vm.audioFormat.collectAsState()
    val actions = remember(vm, onCollapse) {
        PlayerActions(
            togglePlay = vm::togglePlayPause,
            next = vm::skipNext,
            previous = vm::skipPrevious,
            toggleShuffle = vm::toggleShuffle,
            cycleRepeat = vm::cycleRepeatMode,
            seekTo = vm::seekTo,
            playIndex = vm::playQueueItem,
            removeIndex = vm::removeQueueItem,
            collapse = onCollapse,
            moveIndex = vm::moveQueueItem,
            openTogether = onTogether,
        )
    }
    PlayerLayout(
        PlayerUiState(
            shown, isPlaying, isBuffering, hasNext, shuffle, repeat, duration,
            lyrics, queue, currentIndex, upNext, sound, theme, ui, source, audioFormat,
        ),
        position = rememberPlaybackPosition(vm),
        buffered = vm::bufferedPositionMs,
        actions = actions,
        onCovering = onCovering,
    )
}

private enum class Pane { COVER, LYRICS, QUEUE }

@Composable
fun PlayerLayout(
    state: PlayerUiState,
    position: () -> Long,
    buffered: () -> Long,
    actions: PlayerActions,
    initialLyrics: Boolean = false,
    onCovering: (Boolean) -> Unit = {},
) {
    val current = state.song
    val theme = state.theme
    val sound = state.sound

    var paneName by rememberSaveable { mutableStateOf(if (initialLyrics) Pane.LYRICS.name else Pane.COVER.name) }
    val pane = Pane.valueOf(paneName)
    fun toggle(p: Pane) { paneName = if (pane == p) Pane.COVER.name else p.name }
    var showRemix by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showOffset by remember { mutableStateOf(false) }
    var showSignal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    // Lyrics run on their own clock, shifted by this song's offset.
    val lyricsSettings by AppSettings.lyrics.collectAsState()
    val offsetMs = lyricsSettings.offsets[current.videoId] ?: 0L
    val lyricsPosition: () -> Long = { position() + offsetMs }
    // Long-press on the line being sung: shift this song's lyrics so that line starts now.
    val syncLine: (Long) -> Unit = { lineStart ->
        val shift = lineStart - position()
        AppSettings.setLyricsOffset(current.videoId, shift)
        Toast.makeText(context, "Lyrics moved %+.1f s for this song".format(shift / 1000f), Toast.LENGTH_SHORT).show()
    }
    val liked by LibraryStore.liked.collectAsState()
    val isLiked = liked.any { it.videoId == current.videoId }
    val device = rememberOutputDeviceName()
    val sleepLabel = sleepTimerLabel()

    // Drag down anywhere outside the lyrics and queue lists to close.
    val dragOffset = remember { Animatable(0f) }
    val covering by rememberUpdatedState(onCovering)
    LaunchedEffect(dragOffset) {
        snapshotFlow { dragOffset.value == 0f }.distinctUntilChanged().collect { covering(it) }
    }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 140.dp.toPx() }
    val backdrop = rememberLayerBackdrop()
    val style = state.ui.playerStyle
    val minimal = style == PlayerStyle.MINIMAL
    // Full-screen cover only fits the layouts built around a plain square cover.
    val fullCover = state.ui.fullScreenCover && pane == Pane.COVER && (style == PlayerStyle.CLASSIC || style == PlayerStyle.MINIMAL)

    PlayerTheme(seed = rememberArtworkSeed(current.thumbnailUrl), settings = theme) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Dragging down turns the player into a card over the app,
                    // the way iOS sheets do: it shrinks from the top edge and
                    // its corners round, so the screen behind shows around it.
                    val p = (dragOffset.value / (dismissPx * 2)).coerceIn(0f, 1f)
                    translationY = dragOffset.value * 0.9f
                    val scale = 1f - 0.12f * p
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    shape = RoundedCornerShape(40.dp * p)
                    clip = p > 0f
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                    },
                    onDragStopped = { velocity ->
                        if (dragOffset.value > dismissPx || velocity > 2_000f) {
                            // Leave the card as it is: the exit slide carries it away from here.
                            actions.collapse()
                        } else {
                            dragOffset.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 420f))
                        }
                    },
                ),
        ) {
            // The backdrop is the layer the player's Liquid Glass bends.
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                PlayerBackdrop(
                    theme.playerBackground,
                    current.thumbnailUrl,
                    Modifier.fillMaxSize(),
                    animate = !state.ui.reduceAnimation,
                    fullCover = fullCover,
                )
            }

            // The player draws its own dark surface, so it sets its own text and
            // icon color rather than inheriting the app's (light in light theme).
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalBackdrop provides backdrop,
                LocalHazeState provides null,
            ) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 24.dp),
            ) {
                // Grab handle, then "Playing from" or the remix pill.
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                            .clickable(onClick = actions.collapse),
                    )
                    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                        val room by Together.room.collectAsState()
                        AnimatedContent(sound.isDefault, label = "remixPill") { normal ->
                            if (room != null) {
                                TogetherPill(room!!, actions.openTogether)
                            } else if (normal) {
                                if (!state.ui.hideSongStatus) SongStatus(state.source)
                            } else {
                                AssistChip(
                                    onClick = { showRemix = true },
                                    label = { Text(RemixPreset.matching(sound)?.label ?: "Custom remix") },
                                    leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Cover mode: the art fills the space and the title sits below it.
                // Lyrics and queue: a compact header, then the list.
                AnimatedContent(
                    pane,
                    transitionSpec = {
                        (fadeIn(tween(320)) + scaleIn(tween(380, easing = FastOutSlowInEasing), 0.94f)) togetherWith
                            (fadeOut(tween(180)) + scaleOut(tween(220), 0.98f))
                    },
                    label = "pane",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { p ->
                    Column(Modifier.fillMaxSize()) {
                        if (p == Pane.COVER && style == PlayerStyle.LYRICS_FIRST) {
                            // The lyrics are the page; the cover shrinks to the header.
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(current.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(84.dp), RoundedCornerShape(14.dp))
                                Spacer(Modifier.width(16.dp))
                                TitleRow(current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true }, compact = true)
                            }
                            LyricsView(
                                state.lyrics,
                                lyricsPosition,
                                onSeek = { actions.seekTo((it - offsetMs).coerceAtLeast(0)) },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                synced = state.ui.syncedLyrics,
                                blur = state.ui.blurLyrics,
                                onSyncLine = syncLine,
                            )
                        } else if (p == Pane.COVER) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                when {
                                    fullCover ->
                                        ArtworkSwipeArea(onSwipeNext = actions.next, onSwipePrevious = actions.previous, modifier = Modifier.fillMaxSize())
                                    style == PlayerStyle.VINYL ->
                                        VinylPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    style == PlayerStyle.CASSETTE ->
                                        CassettePane(
                                            current, state.isPlaying,
                                            progress = { if (state.durationMs > 0) position() / state.durationMs.toFloat() else 0f },
                                            onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation,
                                        )
                                    style == PlayerStyle.HALO ->
                                        HaloPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    style == PlayerStyle.POLAROID ->
                                        PolaroidPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    else ->
                                        ArtworkPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            TitleRow(current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true })
                        } else {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(current.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(64.dp), RoundedCornerShape(10.dp))
                                Spacer(Modifier.width(14.dp))
                                TitleRow(current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true }, compact = true)
                            }
                            if (p == Pane.LYRICS) {
                                LyricsView(
                                    state.lyrics,
                                    lyricsPosition,
                                    onSeek = { actions.seekTo((it - offsetMs).coerceAtLeast(0)) },
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    synced = state.ui.syncedLyrics,
                                    blur = state.ui.blurLyrics,
                                    onSyncLine = syncLine,
                                )
                            } else {
                                Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                                QueuePane(
                                    queue = state.queue,
                                    currentIndex = state.currentIndex,
                                    upNext = state.upNext,
                                    isPlaying = state.isPlaying,
                                    shuffle = state.shuffle,
                                    onPlayIndex = actions.playIndex,
                                    onRemoveIndex = actions.removeIndex,
                                    onMove = actions.moveIndex,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                if (pane != Pane.LYRICS && state.ui.syncedLyrics && !minimal && !(pane == Pane.COVER && style == PlayerStyle.LYRICS_FIRST)) {
                    LyricPreview(state.lyrics, lyricsPosition, onOpen = { paneName = Pane.LYRICS.name }, Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                SeekBar(position, buffered, state.durationMs, onSeek = actions.seekTo, wavy = state.ui.wavySeekbar && !state.ui.reduceAnimation, playing = state.isPlaying)
                if (state.ui.statsForNerds) {
                    Box(Modifier.clickable { showSignal = true }) { NerdStatsLine(state.audioFormat) }
                }
                PlayerControls(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    hasNext = state.hasNext,
                    onTogglePlay = actions.togglePlay,
                    onNext = actions.next,
                    onPrevious = actions.previous,
                )
                if (!state.ui.hideVolumeBar && !minimal) VolumeBar(Modifier.padding(vertical = 4.dp))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassToggle(Icons.Rounded.Lyrics, "Lyrics", pane == Pane.LYRICS, { toggle(Pane.LYRICS) })
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AnimatedContent(pane == Pane.QUEUE, label = "pill") { inQueue ->
                            if (inQueue) {
                                ModesPill(state.shuffle, state.repeatMode, actions.toggleShuffle, actions.cycleRepeat)
                            } else {
                                Row(
                                    Modifier.height(52.dp).glass(RoundedCornerShape(30.dp), Color.White.copy(alpha = 0.10f)),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GlassToggle(Icons.Rounded.Tune, "Remix", !sound.isDefault, { showRemix = true })
                                    GlassToggle(Icons.Rounded.Bedtime, "Sleep timer", sleepLabel != null, { showSleep = true })
                                }
                            }
                        }
                    }
                    GlassToggle(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", pane == Pane.QUEUE, { toggle(Pane.QUEUE) })
                }
                if (!minimal) Text(
                    sleepLabel ?: device,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                )
            }
            }
        }

        if (showRemix) RemixSheet(onDismiss = { showRemix = false })
        if (showSleep) SleepTimerDialog(onDismiss = { showSleep = false })
        if (showMenu) {
            SongMenuSheet(
                current,
                onDismiss = { showMenu = false },
                top = { close -> MenuRow(Icons.Rounded.HighQuality, "Upgrade quality") { close(); PlaybackRequests.upgradeQuality() } },
                tools = { close ->
                    MenuRow(Icons.Rounded.GraphicEq, "Signal path") { close(); showSignal = true }
                    MenuRow(Icons.Rounded.Groups, "Listen together") { close(); actions.openTogether() }
                    MenuRow(Icons.Rounded.Bedtime, sleepLabel ?: "Sleep timer") { close(); showSleep = true }
                    MenuRow(Icons.Rounded.Tune, "Lyrics offset") { close(); showOffset = true }
                    MenuRow(Icons.Rounded.GraphicEq, "Remix") { close(); showRemix = true }
                },
                end = { close ->
                    MenuRow(Icons.Rounded.BugReport, "Copy log") {
                        close()
                        scope.launch {
                            val log = LogExport.recent()
                            clipboard.setPrimaryClip(ClipData.newPlainText("OpenTune log", log))
                            Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
        if (showOffset) LyricsOffsetDialog(current.videoId, onDismiss = { showOffset = false })
        if (showSignal) SignalPathDialog(state.audioFormat, onDismiss = { showSignal = false })
    }
}

/**
 * Shifts this song's lyrics earlier or later when they run out of step with
 * the audio. Kept per song.
 */
@Composable
private fun LyricsOffsetDialog(videoId: String, onDismiss: () -> Unit) {
    val lyrics by AppSettings.lyrics.collectAsState()
    val ms = lyrics.offsets[videoId] ?: 0L
    fun set(v: Long) = AppSettings.setLyricsOffset(videoId, v.coerceIn(-10_000, 10_000))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics offset") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        ms == 0L -> "In step"
                        ms > 0 -> "%.1f s earlier".format(ms / 1000f)
                        else -> "%.1f s later".format(-ms / 1000f)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Earlier if the words come after the singing, later if they come before.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500L to "−0.5", -100L to "−0.1", 100L to "+0.1", 500L to "+0.5").forEach { (step, label) ->
                        OutlinedButton(onClick = { set(ms + step) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { set(0) }) { Text("Reset") } },
    )
}

/** Title and artist with the heart and "…" glass buttons on the right. */
@Composable
private fun RowScope.TitleRow(song: Song, liked: Boolean, onLike: () -> Unit, onMore: () -> Unit, compact: Boolean = false) {
    Column(Modifier.weight(1f)) {
        MarqueeText(song.title, Modifier.fillMaxWidth(), style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
        // Tapping the artist opens their page.
        val viewArtist = LocalSongMenu.current?.viewArtist
        Text(
            song.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (viewArtist != null && !song.videoId.startsWith("local:")) Modifier.clickable { viewArtist(song) } else Modifier,
        )
    }
    Spacer(Modifier.width(10.dp))
    HeartButton(liked, onLike)
    Spacer(Modifier.width(10.dp))
    Box(Modifier.size(46.dp).glass(CircleShape, Color.White.copy(alpha = 0.16f)).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MoreHoriz, "More", Modifier.size(26.dp))
    }
}

@Composable
private fun TitleRow(song: Song, liked: Boolean, onLike: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TitleRow(song, liked, onLike, onMore, compact = false)
    }
}

/** The heart pops when tapped on. */
@Composable
private fun HeartButton(liked: Boolean, onClick: () -> Unit) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(liked) {
        if (liked) {
            pop.snapTo(0.7f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f))
        }
    }
    Box(
        Modifier.size(46.dp).glass(CircleShape, Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (liked) "Remove from Liked" else "Like",
            Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            tint = if (liked) Color(0xFFFF4D6D) else LocalContentColor.current,
        )
    }
}

/** Mini player wired to the view model; the bottom bar places and sizes it. */
@Composable
fun MiniPlayerBar(vm: PlayerViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier, inline: Boolean = false) {
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val position = rememberPlaybackPosition(vm)
    val s = song ?: return
    MiniPlayer(
        song = s,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        hasNext = hasNext,
        progress = { if (duration > 0) position().toFloat() / duration else 0f },
        onTogglePlay = vm::togglePlayPause,
        onNext = vm::skipNext,
        onPrevious = vm::skipPrevious,
        onClick = onExpand,
        modifier = modifier,
        inline = inline,
    )
}

/** Shown where "Playing from" goes while in a room: whose room, and how many are in. */
@Composable
private fun TogetherPill(room: Together.Room, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                when {
                    room.hosting -> "Your room · ${room.members.size} listening"
                    room.holding -> "Paused for you · ${room.hostName ?: "room"}"
                    room.phase == Together.Phase.Live -> "With ${room.hostName ?: "the host"} · ${room.members.size} listening"
                    else -> "Listening together"
                },
            )
        },
        leadingIcon = { Icon(Icons.Rounded.Groups, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}
