package com.opentune.ui

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    var showNowPlaying by remember { mutableStateOf(false) }

    val song = currentSong
    if (song != null && showNowPlaying) {
        NowPlayingScreen(
            song = song,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onTogglePlayPause = viewModel::togglePlayPause,
            onBack = { showNowPlaying = false },
            currentPositionMs = { viewModel.player.currentPositionMs() },
            durationMs = { viewModel.player.durationMs() },
            onSeek = { viewModel.player.seekTo(it) },
        )
        return
    }

    Scaffold(
        bottomBar = {
            song?.let {
                MiniPlayer(
                    song = it,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onClick = { showNowPlaying = true },
                )
            }
        },
    ) { padding ->
        SearchScreen(
            viewModel = viewModel,
            contentPadding = padding,
            onSongClick = viewModel::play,
        )
    }
}
