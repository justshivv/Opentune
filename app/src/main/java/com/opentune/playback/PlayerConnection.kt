package com.opentune.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.PlaybackException
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

    private val _hasNext = MutableStateFlow(false)
    val hasNext = _hasNext.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = error.cause?.message ?: error.message
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
        _hasNext.value = player.hasNextMediaItem()
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
    fun play(song: Song) {
        val controller = controller ?: return
        _error.value = null
        controller.setMediaItem(song.toMediaItem())
        controller.prepare()
        controller.play()
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

    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L
}
