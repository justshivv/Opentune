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
    private var stuckJob: Job? = null

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

    /**
     * Stops mirroring; with [resume], the phone picks up where the device was.
     * With [playOn], the phone goes on playing out loud (casting stopped on
     * purpose: the device was told to stop); without it, the phone is left
     * paused there, still silenced while it pauses.
     */
    fun detach(resume: Boolean = true, playOn: Boolean = true) {
        val r = receiver ?: return
        val last = r.status.value
        statusJob?.cancel()
        loadJob?.cancel()
        stuckJob?.cancel()
        player.removeListener(this)
        receiver = null
        loadedId = null
        // Paused before the sound comes back, so not a moment of it reaches the speaker.
        if (!playOn) player.pause()
        if (resume && last.mediaId == player.currentMediaItem?.mediaId && last.state in listOf(RemoteState.PLAYING, RemoteState.PAUSED)) {
            player.seekTo(last.positionNow())
        }
        if (playOn && waiting) player.play()
        waiting = false
        DspAudioProcessor.silenced = false
    }

    /**
     * The device dropped out on its own: its connection closed, or it was
     * taken over. That doesn't mean it stopped: a Chromecast plays on what it
     * was given from its buffer, and one that can't be reached can't be told
     * to stop. So the phone doesn't take over out loud on top of it; it's
     * left paused at the device's place, to be played again by hand.
     */
    private fun lose(r: Receiver) {
        com.opentune.data.DebugLog.w(TAG, "${r.name} went away while casting; the phone stays paused")
        detach(resume = true, playOn = false)
        Cast.lost(r)
    }

    private fun load(item: MediaItem, positionMs: Long, play: Boolean) {
        val r = receiver ?: return
        loadedId = item.mediaId
        commandAt = SystemClock.elapsedRealtime()
        loadJob?.cancel()
        loadJob = scope.launch {
            // Casting can end while the song is looked up (the device gone, its server
            // stopped); that's no reason to crash, which would leave the device playing alone.
            val media = try {
                Cast.mediaFor(context, item, r)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.opentune.data.DebugLog.w(TAG, "couldn't cast ${item.mediaId}", e)
                null
            }
            if (receiver !== r) return@launch
            if (media == null) {
                Cast.say(com.opentune.i18n.Tr.f("Can't cast {0}", item.mediaMetadata.title ?: com.opentune.i18n.Tr.t("this song")))
                return@launch
            }
            try {
                r.load(media, positionMs, play)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.opentune.data.DebugLog.w(TAG, "${r.name} didn't take ${item.mediaId}", e)
                return@launch
            }
            // The next song's stream is looked up now, so skipping to it doesn't wait for that.
            nextItem()?.let { Cast.warm(it) }
        }
    }

    private fun nextItem(): MediaItem? {
        val i = player.nextMediaItemIndex
        return if (i == androidx.media3.common.C.INDEX_UNSET) null else player.getMediaItemAt(i)
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
            lose(r)
            return
        }
        val current = player.currentMediaItem?.mediaId
        if (s.mediaId != null && s.mediaId != current) return
        val settling = SystemClock.elapsedRealtime() - commandAt < SETTLE_MS
        when (s.state) {
            RemoteState.LOADING, RemoteState.BUFFERING -> {
                if (player.playWhenReady) {
                    waiting = true
                    follow { player.pause() }
                }
                // A song the device never starts (a format it can't play, a stream it won't fetch)
                // left the phone waiting for good; after a while it's taken as failed and skipped.
                if (waiting && stuckJob?.isActive != true) {
                    val song = current
                    stuckJob = scope.launch {
                        kotlinx.coroutines.delay(STUCK_MS)
                        val now = receiver?.status?.value ?: return@launch
                        if (waiting && player.currentMediaItem?.mediaId == song && now.state in listOf(RemoteState.LOADING, RemoteState.BUFFERING)) {
                            Cast.say(com.opentune.i18n.Tr.f("{0} couldn't play {1}", r.name, player.mediaMetadata.title ?: com.opentune.i18n.Tr.t("this song")))
                            waiting = false
                            if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.play() } else follow { player.pause() }
                        }
                    }
                }
            }
            RemoteState.PLAYING -> {
                stuckJob?.cancel()
                if (waiting || (!player.playWhenReady && !settling)) {
                    waiting = false
                    follow { player.play() }
                }
                val drift = player.currentPosition - s.positionNow()
                // A station has no place to keep in step with.
                val live = current?.let(com.opentune.data.radio.Radio::isRadio) == true
                // Only a position the device has shown it keeps: one that says 0:00 throughout would
                // pull the phone, and its seek bar and lyrics, back to the start over and over.
                if (!settling && !live && s.positionKnown && kotlin.math.abs(drift) > DRIFT_MS) follow { player.seekTo(s.positionNow()) }
            }
            // Paused on the device itself, by its remote.
            RemoteState.PAUSED -> if (player.playWhenReady && !settling && !waiting) follow { player.pause() }
            RemoteState.FINISHED -> if (!settling) {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else follow { player.pause() }
            }
            RemoteState.ERROR -> if (!settling) {
                Cast.say(com.opentune.i18n.Tr.f("{0} couldn't play {1}", r.name, player.mediaMetadata.title ?: com.opentune.i18n.Tr.t("this song")))
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "CastMirror"
        /** How long after a command the device's reports are taken as out of date. */
        const val SETTLE_MS = 2_500L
        /** How far apart the phone and the device may get before the phone moves. */
        const val DRIFT_MS = 1_500L
        /** How long a song may sit loading on the device before it's taken as one it can't play. */
        const val STUCK_MS = 15_000L
    }
}
