package com.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * How far ahead the player fetches, on top of [base]'s own rules:
 * - [aheadUs] while [held] says a better copy of the song is being looked for. Otherwise the
 *   player fetched the lossy stream (and the next songs') many minutes ahead in seconds, only
 *   for the FLAC to replace it: data spent on audio never heard.
 * - [flacAheadUs] while the song being fetched is a FLAC ([flac]). Fetched in pieces (see
 *   [ChunkedDataSource]) and only this far ahead, a song skipped partway has used the data
 *   for the part heard and a minute more, not the whole file.
 * Otherwise, and once the search is over, [base] buffers as far as it likes.
 */
// The older forms Media3 still calls on some paths are passed on too.
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
internal class LossyHoldBack(
    private val base: LoadControl,
    private val aheadUs: Long,
    private val flacAheadUs: Long = Long.MAX_VALUE,
    private val flac: (MediaItem) -> Boolean = { false },
    private val held: () -> Boolean,
) : LoadControl {
    // Kept for the playback thread, the only one that calls this.
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    // Every call is passed on to [base] by hand. Kotlin's `by` delegation skips the methods Java's
    // LoadControl gives default bodies, and those defaults throw "not implemented": the player's
    // own onPrepared went to one of them and the app crashed as it opened.
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)
    override fun onPrepared() = base.onPrepared()
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        base.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onTracksSelected(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        base.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    override fun onTracksSelected(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        base.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    override fun onTracksSelected(renderers: Array<out Renderer>, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        base.onTracksSelected(renderers, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)
    override fun onStopped() = base.onStopped()
    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)
    override fun onReleased() = base.onReleased()
    override fun getAllocator(playerId: PlayerId): Allocator = base.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)
    override fun getBackBufferDurationUs(): Long = base.backBufferDurationUs
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)
    override fun retainBackBufferFromKeyframe(): Boolean = base.retainBackBufferFromKeyframe()
    override fun shouldContinueLoading(playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float): Boolean =
        base.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = base.shouldStartPlayback(parameters)
    override fun shouldStartPlayback(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long,
        playbackSpeed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long): Boolean =
        base.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
    override fun shouldStartPlayback(bufferedDurationUs: Long, playbackSpeed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long): Boolean =
        base.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val ahead = parameters.bufferedDurationUs
        if (ahead >= aheadUs && held()) return false
        if (ahead >= flacAheadUs && loading(parameters)?.let(flac) == true) return false
        return base.shouldContinueLoading(parameters)
    }

    /** The song the player is fetching now. */
    private fun loading(p: LoadControl.Parameters): MediaItem? {
        val timeline = p.timeline
        if (timeline.isEmpty) return null
        val index = timeline.getIndexOfPeriod(p.mediaPeriodId.periodUid)
        if (index == C.INDEX_UNSET) return null
        return timeline.getWindow(timeline.getPeriod(index, period).windowIndex, window).mediaItem
    }
}
