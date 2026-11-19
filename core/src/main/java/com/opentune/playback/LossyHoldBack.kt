package com.opentune.playback

import androidx.media3.exoplayer.LoadControl

/**
 * The player's buffering, held to [aheadUs] while [held] says a better copy of the song is
 * being looked for. Otherwise the player fetched the lossy stream (and the next songs') many
 * minutes ahead in seconds, only for the FLAC to replace it: data spent on audio never heard.
 * Everything else is [base]'s, and once the search is over it buffers as far as it likes.
 */
internal class LossyHoldBack(
    private val base: LoadControl,
    private val aheadUs: Long,
    private val held: () -> Boolean,
) : LoadControl by base {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        if (parameters.bufferedDurationUs >= aheadUs && held()) false else base.shouldContinueLoading(parameters)
}
