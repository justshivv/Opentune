package com.opentune.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.FlagSet
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.TRANSPORT_FADE_MAX_MS
import com.opentune.data.settings.TRANSPORT_FADE_MIN_MS
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fades on pause, play and a skip by hand (Settings › Fade on pause and skip), on the main
 * player's volume. [TransportFade] decides; this carries it out on the player.
 *
 * Only the listener's own presses fade (see [FadingSessionPlayer]). Whatever else moves the
 * player acts at once and the fade lets go at full volume: the system pausing for a call or
 * for headphones coming out, the sleep timer (whose own fade is on the effects' gain, so the
 * two never fight), a seek. [canFade] is false where the volume isn't this class's to move:
 * bit-perfect output, casting, a blend or quality switch under way.
 */
@OptIn(UnstableApi::class)
internal class TransportFades(
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val canFade: () -> Boolean,
    /** Tries a skip to an entry as a short blend on the crossfade's second player; see [Crossfade.blendSkip]. */
    private val blend: (target: Int, lengthMs: Long, fallback: () -> Unit) -> Boolean = { _, _, _ -> false },
) {
    private val fade = TransportFade()
    /** The skips asked for, made once the sound is down. */
    private val waiting = ArrayList<() -> Unit>()
    private var job: Job? = null
    /** True while this class moves the player, so its own changes aren't read as another's. */
    private var acting = false

    /** Told when what the session shows for play/pause changes without the player saying so. */
    var onShownChanged: () -> Unit = {}

    val active: Boolean get() = fade.phase != TransportFade.Phase.IDLE

    /** Play/pause as the session shows it: paused from the moment pause is pressed. */
    fun shownPlayWhenReady(): Boolean = fade.shownPlayWhenReady(player.playWhenReady)

    private val settings get() = AppSettings.playback.value
    private val lengthMs: Long get() = settings.transportFadeMs.coerceIn(TRANSPORT_FADE_MIN_MS, TRANSPORT_FADE_MAX_MS).toLong()
    private fun fadeOk() = settings.transportFade && player.mediaItemCount > 0 && canFade()

    // Through every fade the player is meant to play: paused by anything else (audio focus,
    // headphones unplugged, the sleep timer), the fade lets go and the system has its way.
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!acting && !playWhenReady && active) act { fade.interrupt() }
        }
    }

    init {
        player.addListener(listener)
    }

    fun pause() = act { fade.pause(lengthMs, fadeOk(), player.playWhenReady && player.isPlaying) }

    fun play() = act { fade.play(lengthMs, fadeOk(), player.playWhenReady) }

    /**
     * A skip by hand, made by [change]; [target] is the entry it goes to, when it goes to
     * another. With crossfade or Automix on it's a short blend where one can be made.
     */
    fun skip(target: Int?, change: () -> Unit) {
        val phase = fade.phase
        if (target != null && target != player.currentMediaItemIndex && fadeOk() && player.isPlaying &&
            (phase == TransportFade.Phase.IDLE || phase == TransportFade.Phase.RISING)) {
            val from = fade.level
            if (blend(target, lengthMs) { act { waiting += change; fade.skip(lengthMs, fadeOk(), player.isPlaying) } }) {
                // The blend has the volume now, from where this fade had it.
                job?.cancel()
                fade.release()
                waiting.clear()
                player.volume = from
                return
            }
        }
        act {
            waiting += change
            fade.skip(lengthMs, fadeOk(), player.isPlaying)
        }
    }

    /**
     * Something else is about to move the player: any fade lets go, the pause or skip it was
     * making made now. Not for what this class's own moves set off (a song that resumes where
     * it was left, once a skip lands on it): the fade carries on through those.
     */
    fun cancel() {
        if (active && !acting) act { fade.interrupt() }
    }

    fun release() {
        player.removeListener(listener)
        job?.cancel()
        if (active) {
            fade.release()
            player.volume = 1f
        }
        waiting.clear()
    }

    /** Runs one decision and carries it out: the volume down before the player is moved, up after. */
    private fun act(decide: () -> TransportFade.Step) {
        val shown = shownPlayWhenReady()
        val was = active
        val step = decide()
        val touch = was || active
        if (touch && fade.level < player.volume) player.volume = fade.level
        acting = true
        try {
            if (step.change) {
                val changes = waiting.toList()
                waiting.clear()
                changes.forEach { runCatching(it) }
            }
            if (step.pause) player.pause()
            if (step.play) {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                player.play()
            }
        } finally {
            acting = false
        }
        if (touch) player.volume = fade.level
        if (active) tick() else job?.cancel()
        if (shown != shownPlayWhenReady()) onShownChanged()
    }

    private fun tick() {
        if (job?.isActive == true) return
        job = scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive && active) {
                delay(FRAME_MS)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - last
                last = now
                act { fade.tick(elapsed, player.isPlaying) }
            }
        }
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}

/**
 * The player the media session drives (the app's controls, the notification, the lock screen,
 * headphones, a car), so their pause, play and skips fade: [exo] itself, with those few calls
 * going through [fades] while they come from a controller ([fromController]). Anything the
 * service does to the player itself acts at once, as before.
 *
 * From the moment pause is pressed it reads as paused (and tells the session's listeners so),
 * so the notification shows Play while the song fades away.
 */
@OptIn(UnstableApi::class)
internal class FadingSessionPlayer(
    private val exo: ExoPlayer,
    private val fades: TransportFades,
    private val fromController: () -> Boolean,
) : ExoPlayer by exo {
    private val listeners = CopyOnWriteArrayList<Shown>()
    /** True while one of this player's own calls runs, so whatever it sets off acts at once. */
    private var inside = false

    init {
        fades.onShownChanged = ::announce
    }

    private fun pressed() = !inside && fromController()

    private inline fun direct(block: () -> Unit) {
        val was = inside
        inside = true
        try { block() } finally { inside = was }
    }

    override fun play() {
        if (pressed()) direct { fades.play() } else direct { fades.cancel(); exo.play() }
    }

    override fun pause() {
        if (pressed()) direct { fades.pause() } else direct { fades.cancel(); exo.pause() }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

    override fun getPlayWhenReady(): Boolean = fades.shownPlayWhenReady()

    override fun isPlaying(): Boolean = exo.isPlaying && fades.shownPlayWhenReady()

    override fun seekToNext() {
        val target = exo.nextMediaItemIndex.takeIf { exo.hasNextMediaItem() }
        skip(target) { if (target != null) to(target, androidx.media3.common.C.TIME_UNSET) else exo.seekToNext() }
    }

    override fun seekToNextMediaItem() {
        val target = exo.nextMediaItemIndex.takeIf { exo.hasNextMediaItem() } ?: return direct { fades.cancel(); exo.seekToNextMediaItem() }
        skip(target) { to(target, androidx.media3.common.C.TIME_UNSET) }
    }

    override fun seekToPrevious() {
        // Like the player's own: back a song only in a song's first seconds, else to its start.
        val back = exo.hasPreviousMediaItem() && exo.currentPosition <= exo.maxSeekToPreviousPosition
        val target = exo.previousMediaItemIndex.takeIf { back }
        skip(target) { if (target != null) to(target, androidx.media3.common.C.TIME_UNSET) else exo.seekTo(0L) }
    }

    override fun seekToPreviousMediaItem() {
        val target = exo.previousMediaItemIndex.takeIf { exo.hasPreviousMediaItem() } ?: return direct { fades.cancel(); exo.seekToPreviousMediaItem() }
        skip(target) { to(target, androidx.media3.common.C.TIME_UNSET) }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (mediaItemIndex == exo.currentMediaItemIndex) direct { fades.cancel(); exo.seekToDefaultPosition(mediaItemIndex) }
        else skip(mediaItemIndex) { to(mediaItemIndex, androidx.media3.common.C.TIME_UNSET) }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == exo.currentMediaItemIndex) direct { fades.cancel(); exo.seekTo(mediaItemIndex, positionMs) }
        else skip(mediaItemIndex) { to(mediaItemIndex, positionMs) }
    }

    override fun seekTo(positionMs: Long) = direct { fades.cancel(); exo.seekTo(positionMs) }

    override fun seekToDefaultPosition() = direct { fades.cancel(); exo.seekToDefaultPosition() }

    override fun stop() = direct { fades.cancel(); exo.stop() }

    /** A skip pressed fades (or blends); one from anything else is made at once. */
    private fun skip(target: Int?, change: () -> Unit) {
        if (pressed()) direct { fades.skip(target, change) } else direct { fades.cancel(); change() }
    }

    /** To entry [index] at [positionMs] (its start for unset), if it's still in the queue. */
    private fun to(index: Int, positionMs: Long) {
        if (index !in 0 until exo.mediaItemCount) return
        if (positionMs == androidx.media3.common.C.TIME_UNSET) exo.seekToDefaultPosition(index) else exo.seekTo(index, positionMs)
    }

    override fun addListener(listener: Player.Listener) {
        val shown = Shown(listener)
        listeners += shown
        exo.addListener(shown)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.filter { it.inner == listener }.forEach {
            listeners -= it
            exo.removeListener(it)
        }
        // Added to the player itself rather than through here.
        exo.removeListener(listener)
    }

    /** Tells the session's listeners play/pause as shown now, for a change the player itself hasn't made. */
    private fun announce() {
        val playWhenReady = playWhenReady
        val playing = isPlaying
        val events = Player.Events(FlagSet.Builder().addAll(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_IS_PLAYING_CHANGED).build())
        for (l in listeners) {
            l.inner.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            l.inner.onIsPlayingChanged(playing)
            l.inner.onEvents(this, events)
        }
    }

    // Not a Java default method, so not passed on by the delegation above.
    override fun getAudioSessionId(): Int = exo.audioSessionId

    /**
     * A listener of the session's, told play/pause as shown, and this player as the one that
     * changed. Every callback is passed on by hand: Kotlin's delegation skips Java's default methods.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class Shown(val inner: Player.Listener) : Player.Listener {
        private val me get() = this@FadingSessionPlayer
        override fun onEvents(player: Player, events: Player.Events) = inner.onEvents(me, events)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = inner.onPlayWhenReadyChanged(me.playWhenReady, reason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = inner.onIsPlayingChanged(me.isPlaying)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = inner.onPlayerStateChanged(me.playWhenReady, playbackState)
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = inner.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = inner.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = inner.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = inner.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaylistMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = inner.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = inner.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = inner.onLoadingChanged(isLoading)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = inner.onAvailableCommandsChanged(availableCommands)
        override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) = inner.onTrackSelectionParametersChanged(parameters)
        override fun onPlaybackStateChanged(playbackState: Int) = inner.onPlaybackStateChanged(playbackState)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = inner.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = inner.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = inner.onPlayerError(error)
        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) = inner.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = inner.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = inner.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = inner.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = inner.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = inner.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: androidx.media3.common.AudioAttributes) = inner.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = inner.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) = inner.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = inner.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = inner.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = inner.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
        override fun onCues(cues: MutableList<androidx.media3.common.text.Cue>) = inner.onCues(cues)
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) = inner.onCues(cueGroup)
        override fun onMetadata(metadata: androidx.media3.common.Metadata) = inner.onMetadata(metadata)
    }
}
