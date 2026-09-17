package com.opentune.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
    isPlaying: Boolean,
    isBuffering: Boolean,
    onTogglePlayPause: () -> Unit,
    onBack: () -> Unit,
    currentPositionMs: () -> Long,
    durationMs: () -> Long,
    onSeek: (Long) -> Unit,
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var userSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(song.videoId, isPlaying) {
        while (true) {
            if (!userSeeking) {
                val duration = durationMs()
                if (duration > 0) {
                    sliderPosition = currentPositionMs().toFloat() / duration
                }
            }
            delay(500)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 16.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            },
            onValueChangeFinished = {
                onSeek((sliderPosition * durationMs()).toLong())
                userSeeking = false
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        if (isBuffering) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        } else {
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.padding(top = 16.dp),
                colors = IconButtonDefaults.iconButtonColors(),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.aspectRatio(1f),
                )
            }
        }
    }
}
