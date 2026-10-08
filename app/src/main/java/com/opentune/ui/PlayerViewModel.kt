package com.opentune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.data.together.Together
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LYRICS_RETRY_MS = 15_000L

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
        Together.attach(player)
        viewModelScope.launch { player.connect() }
        viewModelScope.launch { watchLyrics() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watchLyrics() {
        combine(
            currentSong.distinctUntilChangedBy { it?.videoId },
            AppSettings.lyrics.map { it.ordered to it.preferWordSynced }.distinctUntilChanged(),
        ) { song, _ -> song }.collectLatest { song ->
            if (song == null) return@collectLatest
            // A station has no fixed song to look lyrics up for.
            if (com.opentune.data.radio.Radio.isRadio(song.videoId)) {
                _lyrics.value = LyricsState.NotFound
                return@collectLatest
            }
            _lyrics.value = LyricsState.Loading
            // LRCLIB matches on duration, and the player's figure is exact
            // where the row's "3:45" is rounded. Without it any version's
            // lyrics can match, so wait for it while the stream starts.
            val duration = withTimeoutOrNull(20_000) { durationMs.first { it > 0 } } ?: 0L
            val found = LyricsRepository.lyricsFor(song.videoId, song.title, song.artist, duration)
            _lyrics.value = found?.let(LyricsState::Found) ?: LyricsState.NotFound
            // Plain text often means a synced source was busy just then; ask
            // once more a little later and switch over if it answers in time.
            if (found is com.opentune.data.lyrics.Lyrics.Plain) {
                kotlinx.coroutines.delay(LYRICS_RETRY_MS)
                val again = LyricsRepository.lyricsFor(song.videoId, song.title, song.artist, duration)
                if (again is com.opentune.data.lyrics.Lyrics.Synced) _lyrics.value = LyricsState.Found(again)
            }
        }
    }

    /** In someone else's room, picking a song sends it to the room rather than playing it here. */
    private fun toRoom(song: Song?): Boolean = song != null && Together.room.value?.following == true && Together.suggest(song)

    fun play(song: Song, source: String? = null) { if (!toRoom(song)) player.play(song, source) }
    fun playAll(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null) {
        if (!toRoom(songs.getOrNull(if (shuffle) 0 else startIndex))) player.playAll(songs, startIndex, shuffle, source)
    }
    fun playNext(song: Song) { if (!toRoom(song)) player.playNext(song) }
    fun addToQueue(song: Song) { if (!toRoom(song)) player.addToQueue(song) }
    // In someone else's room these go to the host instead (see Together.intercept).
    fun togglePlayPause() { if (!Together.intercept(Together.Control.PlayPause)) player.togglePlayPause() }
    fun skipNext() { if (!Together.intercept(Together.Control.Next)) player.skipNext() }
    fun skipPrevious() { if (!Together.intercept(Together.Control.Previous)) player.skipPrevious() }
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeatMode() = player.cycleRepeatMode()
    fun playQueueItem(index: Int) = player.playQueueItem(index)
    fun removeQueueItem(index: Int) = player.removeQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)
    /** A song on its own; the service follows it with radio. */
    fun startRadio(song: Song) { if (!toRoom(song)) player.play(song, "${song.title} radio") }
    fun seekTo(positionMs: Long) { if (!Together.intercept(Together.Control.Seek(positionMs))) player.seekTo(positionMs) }
    fun positionMs(): Long = player.currentPositionMs()
    fun bufferedPositionMs(): Long = player.bufferedPositionMs()

    override fun onCleared() {
        Together.detach()
        player.disconnect()
        super.onCleared()
    }
}
