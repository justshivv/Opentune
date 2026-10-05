package com.opentune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    data object NotFound : LyricsState
}

/** Playback state and actions shared by every screen, plus the current track's lyrics. */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val player = PlayerConnection(application)

    val currentSong = player.currentSong
    val queue = player.queue
    val currentIndex = player.currentIndex
    val upNext = player.upNext
    val hasNext = player.hasNext
    val isPlaying = player.isPlaying
    val isBuffering = player.isBuffering
    val shuffleEnabled = player.shuffleEnabled
    val repeatMode = player.repeatMode
    val durationMs = player.durationMs
    val playbackError = player.error
    val source = player.source
    val audioFormat = player.audioFormat

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Loading)
    val lyrics = _lyrics.asStateFlow()

    init {
        viewModelScope.launch { player.connect() }
        viewModelScope.launch { watchLyrics() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watchLyrics() {
        currentSong.distinctUntilChangedBy { it?.videoId }.collectLatest { song ->
            if (song == null) return@collectLatest
            _lyrics.value = LyricsState.Loading
            // LRCLIB matches on duration, and the player's figure is exact
            // where the row's "3:45" is rounded. Give it a moment to arrive.
            val duration = withTimeoutOrNull(4_000) { durationMs.first { it > 0 } } ?: 0L
            val found = LyricsRepository.lyricsFor(song.videoId, song.title, song.artist, duration)
            _lyrics.value = found?.let(LyricsState::Found) ?: LyricsState.NotFound
        }
    }

    fun play(song: Song, source: String? = null) = player.play(song, source)
    fun playAll(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null) =
        player.playAll(songs, startIndex, shuffle, source)
    fun playNext(song: Song) = player.playNext(song)
    fun addToQueue(song: Song) = player.addToQueue(song)
    fun togglePlayPause() = player.togglePlayPause()
    fun skipNext() = player.skipNext()
    fun skipPrevious() = player.skipPrevious()
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeatMode() = player.cycleRepeatMode()
    fun playQueueItem(index: Int) = player.playQueueItem(index)
    fun removeQueueItem(index: Int) = player.removeQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)
    /** A song on its own; the service follows it with radio. */
    fun startRadio(song: Song) = player.play(song, "${song.title} radio")
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun positionMs(): Long = player.currentPositionMs()
    fun bufferedPositionMs(): Long = player.bufferedPositionMs()

    override fun onCleared() {
        player.disconnect()
        super.onCleared()
    }
}
