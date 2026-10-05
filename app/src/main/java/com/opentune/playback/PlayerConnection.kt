package com.opentune.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.opentune.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

/**
 * The app's one connection to [PlaybackService], wrapping a [MediaController]
 * behind Compose-friendly state.
 *
 * Everything here is read back from the controller rather than tracked on the
 * side, because the queue also changes from places this class doesn't drive:
 * the service appends autoplay radio and skips tracks that fail, and the
 * notification has its own next/previous buttons.
 */
class PlayerConnection(private val context: Context) {

    private var controller: MediaController? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    /** Queue indices still to play after the current one, in play order. */
    private val _upNext = MutableStateFlow<List<Int>>(emptyList())
    val upNext = _upNext.asStateFlow()

    private val _hasNext = MutableStateFlow(false)
    val hasNext = _hasNext.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** Where the queue came from, for the player's "Playing from" line. */
    private val _source = MutableStateFlow<String?>(null)
    val source = _source.asStateFlow()

    /** The selected audio stream, for stats for nerds. */
    private val _audioFormat = MutableStateFlow<AudioFormatInfo?>(null)
    val audioFormat = _audioFormat.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = error.cause?.message ?: error.message
        }

        override fun onTracksChanged(tracks: Tracks) {
            val format = tracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat) }
            _audioFormat.value = format?.let {
                AudioFormatInfo(
                    codec = (it.codecs ?: it.sampleMimeType?.substringAfter('/'))?.uppercase(),
                    bitrateKbps = it.bitrate.takeIf { b -> b > 0 }?.div(1000),
                    sampleRateHz = it.sampleRate.takeIf { r -> r > 0 },
                    channels = it.channelCount.takeIf { c -> c > 0 },
                )
            }
        }
    }

    suspend fun connect() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val newController = MediaController.Builder(context, sessionToken)
            .buildAsync()
            .await()
        newController.addListener(listener)
        controller = newController
        refresh(newController)
    }

    fun disconnect() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private fun refresh(player: Player) {
        _queue.value = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() }
        _currentIndex.value = player.currentMediaItemIndex
        _currentSong.value = player.currentMediaItem?.toSong()
        _upNext.value = player.upcomingPlayOrder().drop(1)
        _hasNext.value = player.hasNextMediaItem()
        _shuffleEnabled.value = player.shuffleModeEnabled
        _repeatMode.value = player.repeatMode
        _durationMs.value = player.duration.takeIf { it > 0 } ?: 0L
        _isPlaying.value = player.isPlaying
        _isBuffering.value = player.playbackState == Player.STATE_BUFFERING
        // A track that started, or a recovery the service made on its own,
        // makes the last error stale.
        if (player.isPlaying) _error.value = null
    }

    /**
     * Starts [song] on its own, replacing the queue. The service fills in
     * radio after it.
     */
    fun play(song: Song, source: String? = null) {
        val controller = controller ?: return
        _error.value = null
        _source.value = source
        controller.setMediaItem(song.toMediaItem())
        controller.prepare()
        controller.play()
    }

    /**
     * Replaces the queue with [songs], starting at [startIndex]. With
     * [shuffle], the list is shuffled up front (so turning shuffle off later
     * keeps the shuffled order) and playback starts from its first track.
     */
    fun playAll(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null) {
        val controller = controller ?: return
        if (songs.isEmpty()) return
        _error.value = null
        _source.value = source
        val ordered = if (shuffle) songs.shuffled() else songs
        controller.shuffleModeEnabled = false
        controller.setMediaItems(
            ordered.map { it.toMediaItem() },
            if (shuffle) 0 else startIndex.coerceIn(songs.indices),
            0L,
        )
        controller.prepare()
        controller.play()
    }

    fun toggleShuffle() {
        val controller = controller ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    /** Off, then repeat the whole queue, then repeat the current track. */
    fun cycleRepeatMode() {
        val controller = controller ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Queues [song] right after the current track. */
    fun playNext(song: Song) {
        val controller = controller ?: return
        if (controller.mediaItemCount == 0) return play(song)
        controller.addMediaItem(controller.currentMediaItemIndex + 1, song.toMediaItem())
    }

    /** Queues [song] at the end. */
    fun addToQueue(song: Song) {
        val controller = controller ?: return
        if (controller.mediaItemCount == 0) return play(song)
        controller.addMediaItem(song.toMediaItem())
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            // After an error the player sits idle; play() alone won't restart it.
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun skipNext() {
        controller?.seekToNext()
    }

    /** Restarts the current track, or goes back one if it has only just begun. */
    fun skipPrevious() {
        controller?.seekToPrevious()
    }

    fun playQueueItem(index: Int) {
        val controller = controller ?: return
        controller.seekToDefaultPosition(index)
        controller.play()
    }

    fun removeQueueItem(index: Int) {
        val controller = controller ?: return
        if (index != controller.currentMediaItemIndex) controller.removeMediaItem(index)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

    fun bufferedPositionMs(): Long = controller?.bufferedPosition ?: 0L
}

data class AudioFormatInfo(val codec: String?, val bitrateKbps: Int?, val sampleRateHz: Int?, val channels: Int?)
