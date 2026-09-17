package com.opentune.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

/**
 * The app's one connection to [PlaybackService], wrapping a [MediaController]
 * behind Compose-friendly state.
 *
 * Resolving a track's stream URL is done here, on the way into [play], rather
 * than inside the service: [StreamResolver.resolve] is a suspend call, and
 * keeping it on this side lets the UI show its own loading state around it
 * without teaching the service about that concern.
 */
class PlayerConnection(private val context: Context) {

    private var controller: MediaController? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = playbackState == Player.STATE_BUFFERING
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _error.value = error.message
        }
    }

    suspend fun connect() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val newController = MediaController.Builder(context, sessionToken)
            .buildAsync()
            .await()
        newController.addListener(listener)
        controller = newController
    }

    fun disconnect() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    /** Resolves [song]'s stream and starts it playing, replacing whatever was queued. */
    suspend fun play(song: Song) {
        val controller = controller ?: return
        _error.value = null
        _isBuffering.value = true
        try {
            val url = StreamResolver.resolve(song.videoId)
            val mediaItem = MediaItem.Builder()
                .setMediaId(song.videoId)
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setArtworkUri(song.thumbnailUrl?.let { android.net.Uri.parse(it) })
                        .build(),
                )
                .build()
            _currentSong.value = song
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        } catch (e: Exception) {
            _isBuffering.value = false
            _error.value = e.message ?: "Couldn't play ${song.title}"
        }
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L
}
