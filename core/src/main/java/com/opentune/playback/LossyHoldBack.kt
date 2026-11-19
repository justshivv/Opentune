package com.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl

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
internal class LossyHoldBack(
    private val base: LoadControl,
    private val aheadUs: Long,
    private val flacAheadUs: Long = Long.MAX_VALUE,
    private val flac: (MediaItem) -> Boolean = { false },
    private val held: () -> Boolean,
) : LoadControl by base {
    // Kept for the playback thread, the only one that calls this.
    private val period = Timeline.Period()
    private val window = Timeline.Window()

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
