package com.opentune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val currentSong by viewModel.currentSong.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val hasNext by viewModel.hasNext.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val playbackError by viewModel.playbackError.collectAsState()
    var showNowPlaying by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(playbackError) {
        playbackError?.let { snackbarHostState.showSnackbar(it) }
    }

    val song = currentSong
    BackHandler(enabled = song != null && showNowPlaying) { showNowPlaying = false }

    if (song != null && showNowPlaying) {
        NowPlayingScreen(
            song = song,
            queue = queue,
            currentIndex = currentIndex,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            hasNext = hasNext,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSkipNext = viewModel::skipNext,
            onSkipPrevious = viewModel::skipPrevious,
            onQueueItemClick = viewModel::playQueueItem,
            onQueueItemRemove = viewModel::removeQueueItem,
            onBack = { showNowPlaying = false },
            currentPositionMs = { viewModel.player.currentPositionMs() },
            durationMs = { viewModel.player.durationMs() },
            onSeek = { viewModel.player.seekTo(it) },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            song?.let {
                MiniPlayer(
                    song = it,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    hasNext = hasNext,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onSkipNext = viewModel::skipNext,
                    onClick = { showNowPlaying = true },
                )
            }
        },
    ) { padding ->
        SearchScreen(
            viewModel = viewModel,
            contentPadding = padding,
            onSongClick = viewModel::play,
            onPlayNext = viewModel::playNext,
            onAddToQueue = viewModel::addToQueue,
        )
    }
}
