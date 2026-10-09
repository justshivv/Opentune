package com.opentune.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.opentune.cast.Cast
import com.opentune.cast.Receiver
import com.opentune.cast.RemoteState
import com.opentune.cast.RemoteStatus
import com.opentune.playback.dsp.DspAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps a cast device in step with the phone's player. The phone's player
 * goes on running the queue, silenced, so the queue, autoplay, the
 * notification and every button work as they do without casting; what it
 * plays, and when it plays, pauses or seeks, is passed on to the device.
 * The device is the one making the sound, so its clock wins: the phone
 * waits while the device buffers and is nudged to the device's place when
 * they drift apart.
 */
class CastMirror(private val context: Context, private val player: Player, private val scope: CoroutineScope) : Player.Listener {
    private var receiver: Receiver? = null
    private var statusJob: Job? = null
    private var loadJob: Job? = null
    /** The song on the device, by queue id. */
    private var loadedId: String? = null
    /** True while the phone is paused only because the device is buffering. */
    private var waiting = false
    /** Set while the phone is changed to match the device, so the change isn't sent back. */
    private var following = false
    /** When a command last went to the device; its reports lag behind for a moment after. */
    private var commandAt = 0L

    val active: Boolean get() = receiver != null

    fun attach(r: Receiver) {
        if (receiver === r) return
        detach(resume = false)
        receiver = r
        DspAudioProcessor.silenced = true
        player.addListener(this)
        player.currentMediaItem?.let { load(it, player.currentPosition, player.playWhenReady) }
        statusJob = scope.launch { r.status.collect(::onRemote) }
    }

    /** Stops mirroring; with [resume], the phone picks up where the device was. */
    fun detach(resume: Boolean = true) {
        val r = receiver ?: return
        val last = r.status.value
        statusJob?.cancel()
        loadJob?.cancel()
        player.removeListener(this)
        receiver = null
        loadedId = null
        DspAudioProcessor.silenced = false
        if (resume && last.mediaId == player.currentMediaItem?.mediaId && last.state in listOf(RemoteState.PLAYING, RemoteState.PAUSED)) {
            player.seekTo(last.positionNow())
        }
        if (waiting) player.play()
        waiting = false
    }

    private fun load(item: MediaItem, positionMs: Long, play: Boolean) {
        val r = receiver ?: return
        loadedId = item.mediaId
        commandAt = SystemClock.elapsedRealtime()
        loadJob?.cancel()
        loadJob = scope.launch {
            val media = Cast.mediaFor(context, item, r)
            if (media == null) {
                Cast.say("Can't cast ${item.mediaMetadata.title ?: "this song"}")
                return@launch
            }
            r.load(media, positionMs, play)
        }
    }

    private fun send(block: suspend Receiver.() -> Unit) {
        val r = receiver ?: return
        commandAt = SystemClock.elapsedRealtime()
        scope.launch { r.block() }
    }

    private inline fun follow(block: () -> Unit) {
        following = true
        try { block() } finally { following = false }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null || mediaItem.mediaId == loadedId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            if (mediaItem != null) send { seek(0) }
            return
        }
        load(mediaItem, 0, player.playWhenReady || waiting)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (following) return
        waiting = false
        send { if (playWhenReady) play() else pause() }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (following || reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (newPosition.mediaItemIndex != oldPosition.mediaItemIndex) return // a new song, loaded by onMediaItemTransition
        val to = newPosition.positionMs
        send { seek(to) }
    }

    private fun onRemote(s: RemoteStatus) {
        val r = receiver ?: return
        if (s.state == RemoteState.GONE) {
            Cast.lost(r)
            return
        }
        val current = player.currentMediaItem?.mediaId
        if (s.mediaId != null && s.mediaId != current) return
        val settling = SystemClock.elapsedRealtime() - commandAt < SETTLE_MS
        when (s.state) {
            RemoteState.LOADING, RemoteState.BUFFERING -> if (player.playWhenReady) {
                waiting = true
                follow { player.pause() }
            }
            RemoteState.PLAYING -> {
                if (waiting || (!player.playWhenReady && !settling)) {
                    waiting = false
                    follow { player.play() }
                }
                val drift = player.currentPosition - s.positionNow()
                // A station has no place to keep in step with.
                val live = current?.let(com.opentune.data.radio.Radio::isRadio) == true
                if (!settling && !live && kotlin.math.abs(drift) > DRIFT_MS) follow { player.seekTo(s.positionNow()) }
            }
            // Paused on the device itself, by its remote.
            RemoteState.PAUSED -> if (player.playWhenReady && !settling && !waiting) follow { player.pause() }
            RemoteState.FINISHED -> if (!settling) {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else follow { player.pause() }
            }
            RemoteState.ERROR -> if (!settling) {
                Cast.say("${r.name} couldn't play ${player.mediaMetadata.title ?: "this song"}")
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
            else -> Unit
        }
    }

    private companion object {
        /** How long after a command the device's reports are taken as out of date. */
        const val SETTLE_MS = 2_500L
        /** How far apart the phone and the device may get before the phone moves. */
        const val DRIFT_MS = 1_500L
    }
}
