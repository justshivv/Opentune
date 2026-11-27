package com.opentune.playback

/**
 * Keeps a song's position from slipping back. Some Bluetooth outputs (a Xiaomi Watch 5's)
 * correct their audio timestamps backwards every so often while the sound plays on: the
 * player's position steps back a second or two each time, the losses add up, and lyrics
 * and the other devices' clocks fall behind what's heard. A step back of less than
 * [MAX_SLIP_MS] in the same song, with no seek, is such a slip and is added back for
 * the rest of the song; a seek, a new song or a longer step back starts again from the
 * player's own figure.
 */
class PositionSteadier {
    private var item: String? = null
    private var last = NONE
    private var lead = 0L

    /** The position to show for [raw], the player's own reading of [itemId]. */
    @Synchronized
    fun steady(itemId: String?, raw: Long): Long {
        if (itemId != item) {
            item = itemId
            reset()
        }
        if (last != NONE) {
            val shown = raw + lead
            if (shown < last && last - shown < MAX_SLIP_MS) lead += last - shown
            else if (shown < last) lead = 0
        }
        val out = raw + lead
        last = out
        return out
    }

    /** A seek or a jump: the player's figure is right again. */
    @Synchronized
    fun reset() {
        last = NONE
        lead = 0
    }

    companion object {
        const val MAX_SLIP_MS = 6_000L
        private const val NONE = Long.MIN_VALUE
    }
}
