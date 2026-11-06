package com.opentune.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.playback.AudioFormatInfo
import com.opentune.playback.MeasuredBitrate
import com.opentune.playback.PlayerConnection
import com.opentune.playback.bestKbps
import com.opentune.playback.dolbyName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The TV app's one connection to the player service, with what the screens
 * draw from it: the song, where it is, its words, and its sound's format.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvPlayer @JvmOverloads constructor(
    application: Application,
    /** False only in tests, which have no player service to reach. */
    connectNow: Boolean = true,
) : AndroidViewModel(application) {
    val player = PlayerConnection(application)
    val currentSong = player.currentSong
    val isPlaying = player.isPlaying
    val isBuffering = player.isBuffering
    val queue = player.queue
    val currentIndex = player.currentIndex
    val durationMs = player.durationMs
    val shuffle = player.shuffleEnabled
    val repeat = player.repeatMode
    val format = player.audioFormat

    private val _position = MutableStateFlow(0L)
    /** Where the song is, in ms, kept up to date while the player is open. */
    val position: StateFlow<Long> = _position.asStateFlow()

    /** The words of the playing song, once found: synced when a source has them in time. */
    val lyrics: StateFlow<Lyrics?> = player.currentSong
        .distinctUntilChangedBy { it?.videoId }
        .flatMapLatest { song ->
            if (song == null) flowOf(null)
            else flow {
                emit(null)
                // The length comes with the stream; a lyrics source wants it to pick the right recording.
                val length = withTimeoutOrNull(4_000) { player.durationMs.first { it > 0 } } ?: 0L
                emitAll(LyricsRepository.lyricsUpdates(song.videoId, song.title, song.artist, length))
            }.catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        if (connectNow) viewModelScope.launch { player.connect() }
        viewModelScope.launch {
            while (true) {
                _position.value = player.currentPositionMs()
                delay(if (player.isPlaying.value) POSITION_TICK_MS else IDLE_TICK_MS)
            }
        }
    }

    fun play(song: Song, source: String? = null) = player.play(song, source)
    fun playAll(songs: List<Song>, index: Int = 0, shuffle: Boolean = false, source: String? = null) =
        player.playAll(songs, index, shuffle, source)
    fun togglePlayPause() = player.togglePlayPause()
    fun skipNext() = player.skipNext()
    fun skipPrevious() = player.skipPrevious()
    fun seekTo(ms: Long) = player.seekTo(ms)
    /** Moves by [deltaMs] within the song, kept inside it. */
    fun seekBy(deltaMs: Long) {
        val length = player.durationMs.value
        val to = (player.currentPositionMs() + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (length > 0) to.coerceAtMost(length) else to)
    }
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeatMode()
    fun playQueueItem(index: Int) = player.playQueueItem(index)
    fun removeQueueItem(index: Int) = player.removeQueueItem(index)
    fun playNext(song: Song) = player.playNext(song)
    fun addToQueue(song: Song) = player.addToQueue(song)

    fun isLiked(videoId: String): Boolean = LibraryStore.isLiked(videoId)
    fun toggleLike(song: Song) = LibraryStore.setLiked(song, !LibraryStore.isLiked(song.videoId))

    override fun onCleared() {
        player.disconnect()
        super.onCleared()
    }

    companion object {
        private const val POSITION_TICK_MS = 200L
        private const val IDLE_TICK_MS = 600L

        /** "Dolby Atmos · 48 kHz · 768 kbps": what the sound is, in one line; empty until known. */
        fun formatLine(info: AudioFormatInfo?, measured: Int? = MeasuredBitrate.kbps.value): String {
            if (info == null) return ""
            val kbps = bestKbps(info, measured, null)
            return listOfNotNull(
                dolbyName(info) ?: info.codec?.uppercase(),
                info.sampleRateHz?.let { hz -> if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0) },
                info.bitDepth?.let { "$it-bit" },
                kbps?.let { "$it kbps" },
            ).joinToString(" · ")
        }
    }
}
