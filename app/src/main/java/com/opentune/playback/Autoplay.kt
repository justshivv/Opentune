package com.opentune.playback

import com.opentune.data.model.Song

/**
 * When to extend the queue with radio, and with what. Kept free of Android
 * types so the rules can be tested without a device.
 */
object Autoplay {

    /**
     * How close to the end of the queue playback gets before more radio is
     * fetched. One track of slack means the fetch normally finishes while the
     * second-to-last track is still playing, so the hand-off is gapless.
     */
    const val TRACKS_BEFORE_END = 1

    /**
     * @param remaining tracks still to play after the current one, in play
     *   order (which differs from queue order while shuffle is on).
     */
    fun shouldExtend(remaining: Int, queueSize: Int): Boolean =
        queueSize > 0 && remaining <= TRACKS_BEFORE_END

    /**
     * The radio tracks worth appending: anything not already queued, once
     * each. The radio for a track always lists that track first, and
     * neighbouring seeds overlap heavily, so most of a second fetch is
     * already in the queue.
     */
    fun newTracks(queuedIds: Set<String>, radio: List<Song>): List<Song> =
        radio.filter { it.videoId !in queuedIds }.distinctBy { it.videoId }
}
