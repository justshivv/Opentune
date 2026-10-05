package com.opentune.playback

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.opentune.data.DebugLog as Log
import com.opentune.data.settings.AppSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Blends the end of each song into the start of the next.
 *
 * The main player stays the one the media session, the notification and the
 * history see. When a song is [fadeMs] from its end, a short-lived second
 * player picks up the rest of that song (from the song cache, so it starts at
 * once) and the main player moves on to the next song, muted. Their volumes
 * then cross on equal-power curves, so the sum stays level, and the second
 * player is released. Both share one audio session, so loudness
 * normalization and device effects apply to both.
 *
 * A skip, seek or pause from the listener during a blend ends it straight
 * away: the old song's tail fades out in [BAIL_MS] and the main player is
 * left at full volume.
 */
@OptIn(UnstableApi::class)
class Crossfade(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sources: MediaSource.Factory,
    private val main: ExoPlayer,
) {
    private var tail: ExoPlayer? = null
    private var fade: Job? = null
    private var lastFaded: Pair<Int, String>? = null
    /** True while this class moves the main player itself, so its own transition isn't read as a skip. */
    private var handingOver = false

    private val fadeMs: Long get() = AppSettings.playback.value.crossfadeSeconds * 1000L

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!handingOver && tail != null) bail()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !handingOver && tail != null) bail()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val t = tail ?: return
            if (!isPlaying && !main.playWhenReady) t.pause() else if (main.playWhenReady) t.play()
        }
    }

    fun start() {
        main.addListener(listener)
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                runCatching { check() }.onFailure { Log.w(TAG, "Crossfade check failed", it) }
            }
        }
    }

    fun release() {
        main.removeListener(listener)
        fade?.cancel()
        releaseTail()
        main.volume = 1f
    }

    private fun check() {
        val length = fadeMs
        // Two players mixing is the opposite of bit-perfect.
        if (length <= 0 || tail != null || BitPerfectUsb.active) return
        if (!main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) return
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration < length * 3) return
        val left = duration - main.currentPosition
        if (left > length || left < MIN_LEFT_MS) return
        val item = main.currentMediaItem ?: return
        val key = main.currentMediaItemIndex to item.mediaId
        if (key == lastFaded) return
        lastFaded = key
        begin(item, main.currentPosition, left)
    }

    private fun begin(item: MediaItem, position: Long, left: Long) {
        val t = ExoPlayer.Builder(context)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus= */ false,
            )
            .build()
        if (main.audioSessionId != C.AUDIO_SESSION_ID_UNSET) t.setAudioSessionId(main.audioSessionId)
        t.playbackParameters = main.playbackParameters
        // A little ahead, to cover the time the tail takes to start sounding.
        t.setMediaItem(item, position + START_LEAD_MS)
        t.prepare()
        t.play()
        tail = t
        fade = scope.launch {
            val started = withTimeoutOrNull(START_TIMEOUT_MS) {
                while (!t.isPlaying) delay(10)
                true
            }
            if (started == null) {
                // Not cached or not quick enough: no blend this time, just the normal cut.
                Log.w(TAG, "Crossfade tail didn't start in time")
                releaseTail()
                return@launch
            }
            handingOver = true
            main.volume = 0f
            main.seekToNextMediaItem()
            handingOver = false
            val length = (left - START_LEAD_MS).coerceAtLeast(MIN_LEFT_MS)
            val from = SystemClock.elapsedRealtime()
            while (isActive) {
                val p = ((SystemClock.elapsedRealtime() - from).toFloat() / length).coerceIn(0f, 1f)
                main.volume = sin(p * PI / 2).toFloat()
                t.volume = cos(p * PI / 2).toFloat()
                if (p >= 1f) break
                delay(FRAME_MS)
            }
            main.volume = 1f
            releaseTail()
        }
    }

    /** The listener moved on mid-blend: drop the old song's tail quickly. */
    private fun bail() {
        fade?.cancel()
        val t = tail ?: return
        main.volume = 1f
        fade = scope.launch {
            val v = t.volume
            val from = SystemClock.elapsedRealtime()
            while (isActive) {
                val p = ((SystemClock.elapsedRealtime() - from).toFloat() / BAIL_MS).coerceIn(0f, 1f)
                t.volume = v * (1f - p)
                if (p >= 1f) break
                delay(FRAME_MS)
            }
            releaseTail()
        }
    }

    private fun releaseTail() {
        tail?.run {
            stop()
            release()
        }
        tail = null
    }

    private companion object {
        const val TAG = "Crossfade"
        const val POLL_MS = 200L
        const val FRAME_MS = 16L
        const val MIN_LEFT_MS = 400L
        const val START_LEAD_MS = 120L
        const val START_TIMEOUT_MS = 1_500L
        const val BAIL_MS = 150L
    }
}
