package com.opentune.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.opentune.data.model.Song
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(
    song: Song,
    queue: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasNext: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onQueueItemClick: (Int) -> Unit,
    onQueueItemRemove: (Int) -> Unit,
    onBack: () -> Unit,
    currentPositionMs: () -> Long,
    durationMs: () -> Long,
    onSeek: (Long) -> Unit,
) {
    // Indices into [queue], since the same track can be queued twice.
    val upNext = ((currentIndex + 1) until queue.size).toList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.systemBars.asPaddingValues(),
    ) {
        item(key = "player") {
            PlayerControls(
                song = song,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                hasNext = hasNext,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onBack = onBack,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                onSeek = onSeek,
            )
        }

        item(key = "up-next-header") {
            Text(
                if (upNext.isEmpty()) "Finding more to play…" else "Up next",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        items(upNext, key = { "$it:${queue[it].videoId}" }) { index ->
            SongRow(song = queue[index], onClick = { onQueueItemClick(index) }) {
                IconButton(onClick = { onQueueItemRemove(index) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove from queue")
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasNext: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onBack: () -> Unit,
    currentPositionMs: () -> Long,
    durationMs: () -> Long,
    onSeek: (Long) -> Unit,
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var userSeeking by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var totalMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(song.videoId, isPlaying) {
        while (true) {
            totalMs = durationMs()
            if (!userSeeking) {
                positionMs = currentPositionMs()
                if (totalMs > 0) sliderPosition = positionMs.toFloat() / totalMs
            }
            delay(500)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        )

        Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Text(song.title, style = MaterialTheme.typography.headlineSmall)
            Text(song.artist, style = MaterialTheme.typography.bodyLarge)
        }

        Slider(
            value = sliderPosition.coerceIn(0f, 1f),
            onValueChange = {
                userSeeking = true
                sliderPosition = it
                positionMs = (it * totalMs).toLong()
            },
            onValueChangeFinished = {
                onSeek((sliderPosition * durationMs()).toLong())
                userSeeking = false
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatTime(totalMs), style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            IconButton(onClick = onSkipPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                if (isBuffering) {
                    CircularProgressIndicator()
                } else {
                    FilledIconButton(onClick = onTogglePlayPause, modifier = Modifier.size(64.dp)) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onSkipNext, enabled = hasNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
