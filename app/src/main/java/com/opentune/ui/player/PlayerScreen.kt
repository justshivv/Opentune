package com.opentune.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.RemixPreset
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.data.model.Song
import com.opentune.ui.LyricsState
import com.opentune.ui.PlayerViewModel
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
)

@Composable
fun PlayerScreen(vm: PlayerViewModel, onCollapse: () -> Unit) {
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
        )
    }
    PlayerLayout(
        PlayerUiState(
            current, isPlaying, isBuffering, hasNext, shuffle, repeat, duration,
            lyrics, queue, currentIndex, upNext, sound, theme,
        ),
        position = rememberPlaybackPosition(vm),
        buffered = vm::bufferedPositionMs,
        actions = actions,
    )
}

@Composable
fun PlayerLayout(
    state: PlayerUiState,
    position: () -> Long,
    buffered: () -> Long,
    actions: PlayerActions,
    initialLyrics: Boolean = false,
) {
    val current = state.song
    val theme = state.theme
    val sound = state.sound
    val isPlaying = state.isPlaying
    val isBuffering = state.isBuffering
    val hasNext = state.hasNext
    val shuffle = state.shuffle
    val repeat = state.repeatMode
    val duration = state.durationMs
    val lyrics = state.lyrics
    val queue = state.queue
    val currentIndex = state.currentIndex
    val upNext = state.upNext

    var showLyrics by rememberSaveable { mutableStateOf(initialLyrics) }
    var showQueue by remember { mutableStateOf(false) }
    var showRemix by remember { mutableStateOf(false) }

    // Drag down anywhere outside the lyrics list to close.
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 140.dp.toPx() }

    PlayerTheme(seed = rememberArtworkSeed(current.thumbnailUrl), settings = theme) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragOffset.value
                    alpha = 1f - (dragOffset.value / (dismissPx * 4)).coerceIn(0f, 0.4f)
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                    },
                    onDragStopped = { velocity ->
                        if (dragOffset.value > dismissPx || velocity > 2_000f) {
                            actions.collapse()
                        }
                        dragOffset.animateTo(0f, spring(dampingRatio = 0.8f))
                    },
                ),
        ) {
            PlayerBackdrop(theme.playerBackground, current.thumbnailUrl, Modifier.fillMaxSize())

            // The player draws its own dark surface, so it sets its own text and
            // icon color rather than inheriting the app's (light in light theme).
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 24.dp),
            ) {
                // Top bar
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.collapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player", Modifier.size(32.dp)) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AnimatedContent(sound.isDefault, label = "remixPill") { normal ->
                            if (normal) {
                                Text("Now playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Spacer(Modifier.size(48.dp))
                }

                // Artwork or lyrics
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        showLyrics,
                        transitionSpec = {
                            (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 12 } + scaleIn(tween(350), 0.97f)) togetherWith
                                fadeOut(tween(200))
                        },
                        label = "pane",
                    ) { lyricsMode ->
                        if (lyricsMode) {
                            LyricsView(lyrics, position, onSeek = actions.seekTo, modifier = Modifier.fillMaxSize())
                        } else {
                            ArtworkPane(current, isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                MarqueeText(current.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
                Text(
                    current.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                SeekBar(position, buffered, duration, onSeek = actions.seekTo)
                Spacer(Modifier.height(8.dp))
                PlayerControls(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    hasNext = hasNext,
                    shuffle = shuffle,
                    repeatMode = repeat,
                    onTogglePlay = actions.togglePlay,
                    onNext = actions.next,
                    onPrevious = actions.previous,
                    onShuffle = actions.toggleShuffle,
                    onRepeat = actions.cycleRepeat,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BottomAction(Icons.Rounded.Lyrics, "Lyrics", selected = showLyrics) { showLyrics = !showLyrics }
                    BottomAction(Icons.Rounded.Tune, "Remix", selected = !sound.isDefault) { showRemix = true }
                    BottomAction(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", selected = false) { showQueue = true }
                }
                Spacer(Modifier.height(8.dp))
            }
            }
        }

        if (showQueue) {
            QueueSheet(
                queue = queue,
                currentIndex = currentIndex,
                upNext = upNext,
                isPlaying = isPlaying,
                shuffle = shuffle,
                repeatMode = repeat,
                onShuffle = actions.toggleShuffle,
                onRepeat = actions.cycleRepeat,
                onPlayIndex = actions.playIndex,
                onRemoveIndex = actions.removeIndex,
                onDismiss = { showQueue = false },
            )
        }
        if (showRemix) RemixSheet(onDismiss = { showRemix = false })
    }
}

@Composable
private fun BottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(onClick = onClick) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, color = color)
    }
}

/** Mini player wired to the view model, shown above the tabs. */
@Composable
fun MiniPlayerBar(vm: PlayerViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val position = rememberPlaybackPosition(vm)
    AnimatedVisibility(song != null, modifier = modifier) {
        song?.let { s ->
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
            )
        }
    }
}
